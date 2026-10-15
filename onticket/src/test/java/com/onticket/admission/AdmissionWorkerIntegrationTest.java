package com.onticket.admission;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@Testcontainers
class AdmissionWorkerIntegrationTest {
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.7-alpine")
            .withExposedPorts(6379).withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(128L * 1024 * 1024));
    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;
    @TempDir Path directory;
    AtomicLong time;
    AdmissionPolicy policy;
    RedisAdmissionStore store;
    @BeforeAll static void connect() {
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet(); factory.start(); redis = new StringRedisTemplate(factory);
    }
    @AfterAll static void disconnect() { if (factory != null) factory.destroy(); }
    @BeforeEach void setup() {
        // This class exclusively owns this disposable Redis database.
        try (var connection = factory.getConnection()) { connection.serverCommands().flushDb(); }
        time = new AtomicLong(1000);
        policy = new AdmissionPolicy(7, "worker-g1", Instant.ofEpochMilli(2000), 2, 20,
                Duration.ofSeconds(30), Duration.ofSeconds(10), 2);
        store = new RedisAdmissionStore(redis, policy, time::get);
    }
    @Test void freshBootstrapRefusesRepeatAndPartialDataWithoutChangingState() {
        store.bootstrapFresh(); store.join("alice");
        var before = store.get("alice").orElseThrow();
        assertThatThrownBy(store::bootstrapFresh).isInstanceOf(AdmissionUnavailableException.class);
        assertThat(store.get("alice")).contains(before);
        redis.delete(key("meta"));
        assertThatThrownBy(store::bootstrapFresh).isInstanceOf(AdmissionUnavailableException.class);
        assertThat(redis.hasKey(key("meta"))).isFalse();
        assertThat(redis.opsForHash().size(key("states"))).isEqualTo(1);
    }
    @Test void concurrentFreshBootstrapOnlyCreatesOneNamespace() throws Exception {
        var pool = Executors.newFixedThreadPool(2); var barrier = new CyclicBarrier(2);
        try {
            Callable<Boolean> call = () -> { barrier.await(5, TimeUnit.SECONDS);
                try { store.bootstrapFresh(); return true; } catch (AdmissionUnavailableException expected) { return false; } };
            var one = pool.submit(call); var two = pool.submit(call);
            assertThat(List.of(one.get(5, TimeUnit.SECONDS), two.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally { pool.shutdownNow(); }
        assertThat(redis.opsForHash().get(key("meta"), "seq")).isEqualTo("0");
    }
    @Test void twoActualWorkersRespectOpeningFifoCapAndExpiry() throws Exception {
        store.bootstrapFresh(); for (int i = 0; i < 8; i++) store.join("user" + i);
        var otherFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        otherFactory.afterPropertiesSet(); otherFactory.start();
        try {
            var second = new RedisAdmissionStore(new StringRedisTemplate(otherFactory), policy, time::get);
            var one = new AdmissionWorker(store, new SimpleMeterRegistry());
            var two = new AdmissionWorker(second, new SimpleMeterRegistry());
            one.tick(); assertThat(redis.opsForZSet().zCard(key("active"))).isZero();
            time.set(2000);
            var pool = Executors.newFixedThreadPool(2); var barrier = new CyclicBarrier(2);
            try {
                var futures = List.of(pool.submit(() -> { barrier.await(5, TimeUnit.SECONDS); one.tick(); return true; }),
                        pool.submit(() -> { barrier.await(5, TimeUnit.SECONDS); two.tick(); return true; }));
                for (var future : futures) future.get(5, TimeUnit.SECONDS);
            } finally { pool.shutdownNow(); }
            assertThat(redis.opsForZSet().zCard(key("active"))).isEqualTo(2);
            assertThat(store.get("user0").orElseThrow().state()).isEqualTo(AdmissionSnapshot.State.ADMITTED);
            assertThat(store.get("user1").orElseThrow().state()).isEqualTo(AdmissionSnapshot.State.ADMITTED);
            var entry = store.get("user0").orElseThrow(); store.leave("user0", entry.queueEntryId()); one.tick();
            assertThat(store.get("user2").orElseThrow().state()).isEqualTo(AdmissionSnapshot.State.ADMITTED);
            time.set(12000); two.tick();
            assertThat(redis.opsForZSet().count(key("active"), 12001, Double.POSITIVE_INFINITY)).isEqualTo(2);
            assertThat(store.get("user1").orElseThrow().state()).isEqualTo(AdmissionSnapshot.State.EXPIRED);
        } finally { otherFactory.destroy(); }
    }
    @Test void missingMarkerNeverBootstrapsAndNewGenerationCanRecoverWithoutDeletingOldData() {
        store.bootstrapFresh(); store.join("alice"); redis.delete(key("meta"));
        var meters = new SimpleMeterRegistry(); var worker = new AdmissionWorker(store, meters);
        worker.tick(); worker.tick();
        assertThat(redis.hasKey(key("meta"))).isFalse();
        assertThat(meters.get("onticket.admission.worker.ticks").tag("result", "unavailable").counter().count()).isEqualTo(2);
        var newPolicy = new AdmissionPolicy(7, "worker-g2", policy.opensAt(), 2, 20,
                policy.waitingTtl(), policy.admissionTtl(), 2);
        var recovered = new RedisAdmissionStore(redis, newPolicy, time::get); recovered.bootstrapFresh(); recovered.join("alice");
        time.set(2000); new AdmissionWorker(recovered, new SimpleMeterRegistry()).tick();
        assertThat(recovered.get("alice").orElseThrow().state()).isEqualTo(AdmissionSnapshot.State.ADMITTED);
        assertThat(redis.opsForHash().size(key("states"))).isEqualTo(1);
    }
    @Test void actualScheduledWorkerPromotesWithoutPollingOrCallingTickManually() {
        store.bootstrapFresh(); store.join("alice"); time.set(2000);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("worker-test", Map.of(
                    "onticket.admission.enabled", "true", "onticket.admission.worker.delay-ms", "250")));
            context.registerBean(RedisAdmissionStore.class, () -> store);
            context.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
            context.register(Scheduling.class, AdmissionWorkerConfiguration.class); context.refresh();
            await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50)).untilAsserted(() ->
                    assertThat(store.get("alice").orElseThrow().state()).isEqualTo(AdmissionSnapshot.State.ADMITTED));
        }
    }
    @Test void operatorPolicyBindingAndProductionRedisTimeMatchServerStore() throws Exception {
        var file = directory.resolve("policy.properties");
        Files.writeString(file, """
                onticket.admission.enabled=true
                onticket.admission.concert-id=LOAD-TEST-cli
                onticket.admission.concert-time-id=7
                onticket.admission.generation=cli-g1
                onticket.admission.opens-at=1970-01-01T00:00:00Z
                onticket.admission.redis-port=%d
                """.formatted(REDIS.getMappedPort(6379)));
        var output = new ArrayList<String>(); var settings = new java.util.concurrent.atomic.AtomicReference<AdmissionSettings>();
        String[] args = {"--policy-file=" + file, "--confirm-new-generation=cli-g1"};
        int status = AdmissionBootstrapCommand.run(args, output::add);
        assertThat(status).isZero();
        // Parse through the same binder seam, then use its policy for the server-side constructor.
        assertThat(AdmissionBootstrapCommand.run(args, settings::set, ignored -> { })).isZero();
        var production = new RedisAdmissionStore(redis, settings.get().policy());
        Long before = redis.execute((org.springframework.data.redis.core.RedisCallback<Long>) connection -> connection.serverCommands().time());
        var joined = production.joinObserved("alice");
        Long after = redis.execute((org.springframework.data.redis.core.RedisCallback<Long>) connection -> connection.serverCommands().time());
        assertThat(joined.serverNow().toEpochMilli()).isBetween(before, after);
        new AdmissionWorker(production, new SimpleMeterRegistry()).tick();
        var admitted = production.getObserved("alice").orElseThrow();
        assertThat(admitted.admission().state()).isEqualTo(AdmissionSnapshot.State.ADMITTED);
        assertThat(admitted.admission().expiresAt().toEpochMilli() - admitted.serverNow().toEpochMilli()).isBetween(1L, 60000L);
        assertThat(AdmissionBootstrapCommand.run(args, output::add)).isEqualTo(1);
        assertThat(production.get("alice")).contains(admitted.admission());
    }
    String key(String suffix) { return "admission:{7:worker-g1}:" + suffix; }
    @Configuration(proxyBeanMethods = false) @EnableScheduling static class Scheduling { }
}
