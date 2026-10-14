package com.onticket.admission;

import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
class RedisAdmissionStoreTest {
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4.7-alpine"))
            .withExposedPorts(6379)
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(128L * 1024 * 1024));
    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;
    AtomicLong time;
    AdmissionPolicy policy;
    RedisAdmissionStore store;

    @BeforeAll static void connect() {
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet(); factory.start();
        redis = new StringRedisTemplate(factory); redis.afterPropertiesSet();
    }
    @AfterAll static void disconnect() { if (factory != null) factory.destroy(); }
    @BeforeEach void setup() {
        // Only this class's newly created disposable Redis database is cleared.
        try (var connection = factory.getConnection()) { connection.serverCommands().flushDb(); }
        time = new AtomicLong(1000);
        policy = policy("g1", 1000, 2, 10, 3);
        store = new RedisAdmissionStore(redis, policy, time::get);
        store.bootstrap();
    }
    static AdmissionPolicy policy(String generation, long opens, int active, int waiting, int batch) {
        return new AdmissionPolicy(7, generation, Instant.ofEpochMilli(opens), active, waiting,
                Duration.ofSeconds(30), Duration.ofSeconds(10), batch);
    }

    @Test void duplicateJoinAndReadPreserveIdentityPositionAndExpiry() {
        var first = store.join("alice"); time.addAndGet(500);
        assertThat(store.join("alice")).isEqualTo(first);
        assertThat(store.get("alice")).contains(first);
        assertThat(first.position()).isEqualTo(1); assertThat(first.token()).isNull();
    }
    @Test void fifoUsesAcceptedSequenceNotClientClockAndNeverAdmitsBeforeOpening() {
        store = new RedisAdmissionStore(redis, policy("future", 2000, 2, 10, 3), time::get); store.bootstrap();
        store.join("alice"); store.join("bob"); store.join("charlie");
        assertThat(store.tick()).isZero(); time.set(2000);
        assertThat(store.tick()).isEqualTo(2);
        assertThat(store.get("alice").orElseThrow().state()).isEqualTo(AdmissionSnapshot.State.ADMITTED);
        assertThat(store.get("bob").orElseThrow().state()).isEqualTo(AdmissionSnapshot.State.ADMITTED);
        assertThat(store.get("charlie").orElseThrow().state()).isEqualTo(AdmissionSnapshot.State.WAITING);
    }
    @Test void concurrentJoinOfSameUserHasOneEntryAndSequence() throws Exception {
        var results = parallel(8, ignored -> store.join("alice"));
        assertThat(results.stream().map(AdmissionSnapshot::queueEntryId).distinct()).hasSize(1);
        assertThat(results.stream().map(AdmissionSnapshot::sequence).distinct()).containsExactly(1L);
        assertThat(redis.opsForHash().size(key("states"))).isEqualTo(1);
    }
    @Test void twoClientsConcurrentTicksRespectLimitAndFifo() throws Exception {
        var joined = parallel(8, i -> store.join("user" + i));
        var secondFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        secondFactory.afterPropertiesSet(); secondFactory.start();
        try {
            var secondClient = new RedisAdmissionStore(new StringRedisTemplate(secondFactory), policy, time::get);
            assertThat(parallel(8, i -> (i % 2 == 0 ? store : secondClient).tick()).stream().mapToInt(Integer::intValue).sum()).isEqualTo(2);
        } finally { secondFactory.destroy(); }
        var firstSequences = joined.stream().map(AdmissionSnapshot::sequence).sorted().limit(2).toList();
        var admitted = new ArrayList<Long>();
        for (int i=0; i<8; i++) {
            var state = store.get("user" + i).orElseThrow();
            if (state.state() == AdmissionSnapshot.State.ADMITTED) admitted.add(state.sequence());
        }
        assertThat(admitted).containsExactlyInAnyOrderElementsOf(firstSequences);
        assertThat(redis.opsForZSet().zCard(key("active"))).isEqualTo(2);
    }
    @Test void tokenIsUserAndGenerationBoundAndExpiresAtExactDeadline() {
        store.join("alice"); store.tick(); var grant = store.get("alice").orElseThrow();
        assertThat(store.isAdmitted("alice", grant.admissionId(), grant.token())).isTrue();
        assertThat(store.isAdmitted("bob", grant.admissionId(), grant.token())).isFalse();
        assertThat(store.isAdmitted("alice", grant.admissionId(), "forged")).isFalse();
        var next = new RedisAdmissionStore(redis, policy("g2", 1000, 2, 10, 3), time::get); next.bootstrap();
        assertThat(next.isAdmitted("alice", grant.admissionId(), grant.token())).isFalse();
        time.set(grant.expiresAt().toEpochMilli());
        assertThat(store.isAdmitted("alice", grant.admissionId(), grant.token())).isFalse();
        assertThat(store.get("alice").orElseThrow().state()).isEqualTo(AdmissionSnapshot.State.EXPIRED);
    }
    @Test void expiredAdmissionsReleaseSlotsToNextUsersWithoutRenewingExistingGrant() {
        store.join("alice"); store.join("bob"); store.join("charlie"); store.tick();
        var original = store.get("alice").orElseThrow(); time.addAndGet(100);
        assertThat(store.join("alice")).isEqualTo(original);
        time.set(11000); assertThat(store.tick()).isEqualTo(1);
        assertThat(store.get("charlie").orElseThrow().state()).isEqualTo(AdmissionSnapshot.State.ADMITTED);
    }
    @Test void expiredWaitingIsSkippedAndWorkPerTickIsBounded() {
        store = new RedisAdmissionStore(redis, policy("batch", 1000, 2, 10, 1), time::get); store.bootstrap();
        store.join("old"); time.set(30000); store.join("new"); time.set(31000);
        assertThat(store.tick()).isZero(); // one expired head consumed this bounded candidate batch
        assertThat(store.tick()).isEqualTo(1);
        assertThat(store.get("new").orElseThrow().state()).isEqualTo(AdmissionSnapshot.State.ADMITTED);
    }
    @Test void oldLeaveCannotDeleteNewEntryOrRenewTerminalRetention() {
        var old = store.join("alice"); store.leave("alice", old.queueEntryId());
        var current = store.join("alice"); store.leave("alice", old.queueEntryId());
        assertThat(store.get("alice")).contains(current);
        store.tick(); store.leave("alice", old.queueEntryId());
        assertThat(store.get("alice").orElseThrow().state()).isEqualTo(AdmissionSnapshot.State.ADMITTED);
    }
    @Test void queueFullAndWrongPolicyDoNotModifyState() {
        store = new RedisAdmissionStore(redis, policy("limited", 1000, 1, 1, 1), time::get); store.bootstrap();
        var first = store.join("alice");
        var before = redis.opsForHash().entries("admission:{7:limited}:meta");
        assertThatThrownBy(() -> store.join("bob")).isInstanceOf(AdmissionQueueFullException.class);
        assertThat(redis.opsForHash().entries("admission:{7:limited}:meta")).isEqualTo(before);
        assertThat(store.get("alice")).contains(first);
        var wrong = new RedisAdmissionStore(redis, policy("limited", 1000, 2, 1, 1), time::get);
        assertThatThrownBy(() -> wrong.join("bob")).isInstanceOf(AdmissionUnavailableException.class);
    }
    @Test void missingMarkerAndPartialDataLossFailClosedWithoutAutomaticBootstrap() {
        store.join("alice"); store.tick(); var grant = store.get("alice").orElseThrow();
        redis.delete(key("active"));
        assertThatThrownBy(() -> store.isAdmitted("alice", grant.admissionId(), grant.token())).isInstanceOf(AdmissionUnavailableException.class);
        assertThatThrownBy(() -> store.join("bob")).isInstanceOf(AdmissionUnavailableException.class);
        redis.delete(key("meta"));
        assertThatThrownBy(() -> store.join("bob")).isInstanceOf(AdmissionUnavailableException.class);
        assertThatThrownBy(store::bootstrap).isInstanceOf(AdmissionUnavailableException.class);
    }
    @Test void wrongRedisTypeAndMalformedStateFailBeforeMutation() {
        store.join("alice");
        var before = redis.opsForHash().entries(key("meta"));
        redis.delete(key("active")); redis.opsForValue().set(key("active"), "wrong-type");
        assertThatThrownBy(store::tick).isInstanceOf(AdmissionUnavailableException.class);
        assertThat(redis.opsForHash().entries(key("meta"))).isEqualTo(before);
        redis.delete(key("active"));
        Object member = redis.opsForHash().keys(key("states")).iterator().next();
        redis.opsForHash().put(key("states"), member, "broken-json");
        assertThatThrownBy(store::tick).isInstanceOf(AdmissionUnavailableException.class);
        assertThat(redis.opsForZSet().zCard(key("waiting"))).isEqualTo(1);
    }
    @Test void terminalStatesAreBoundedAndEventuallyRemoved() {
        for (int i=0; i<30; i++) { var entry = store.join("user"+i); store.leave("user"+i, entry.queueEntryId()); }
        assertThat(redis.opsForHash().size(key("states"))).isLessThanOrEqualTo(12);
        time.set(42000);
        for (int i=0; i<5; i++) store.tick();
        assertThat(redis.opsForHash().size(key("states"))).isZero();
    }
    @Test void reinitializationIsIdempotentAndDoesNotResetExistingSequence() {
        store.join("alice"); store.bootstrap();
        assertThat(store.join("bob").sequence()).isEqualTo(2);
        var recreated = new RedisAdmissionStore(redis, policy, time::get);
        assertThat(recreated.get("alice")).isEqualTo(store.get("alice"));
    }
    @Test void lostAllStateKeysWithMarkerRetainedCannotBecomeAnEmptyQueue() {
        store.join("alice");
        redis.delete(List.of(key("states"),key("waiting"),key("waiting-expiry"),key("active"),key("terminal")));
        assertThatThrownBy(() -> store.join("bob")).isInstanceOf(AdmissionUnavailableException.class);
        assertThatThrownBy(store::bootstrap).isInstanceOf(AdmissionUnavailableException.class);
    }
    @Test void expiryAndLeaveCannotDamageRejoinedState() {
        var previous = store.join("alice"); time.set(31000);
        var current = store.join("alice");
        assertThat(current.queueEntryId()).isNotEqualTo(previous.queueEntryId());
        store.leave("alice", previous.queueEntryId()); store.tick();
        assertThat(store.get("alice").orElseThrow().queueEntryId()).isEqualTo(current.queueEntryId());
    }
    @Test void sequenceNearDoubleBoundaryIsNotRoundedByJson() {
        redis.opsForHash().put(key("meta"), "seq", "9007199254740988");
        assertThat(store.join("alice").sequence()).isEqualTo(9007199254740989L);
        assertThat(store.join("bob").sequence()).isEqualTo(9007199254740990L);
        assertThatThrownBy(() -> store.join("charlie")).isInstanceOf(AdmissionUnavailableException.class);
        assertThat(redis.opsForZSet().zCard(key("waiting"))).isEqualTo(2);
    }
    @Test void nullRedisResultDoesNotFallBackToLocalState() {
        var unavailable = new RedisAdmissionStore(org.mockito.Mockito.mock(StringRedisTemplate.class), policy, time::get);
        assertThatThrownBy(() -> unavailable.join("alice")).isInstanceOf(AdmissionUnavailableException.class);
        assertThatThrownBy(() -> unavailable.get("alice")).isInstanceOf(AdmissionUnavailableException.class);
    }
    @Test void productionConstructorUsesRedisTime() {
        var production = new RedisAdmissionStore(redis, policy);
        long before = System.currentTimeMillis();
        var entry = production.join("alice");
        assertThat(entry.expiresAt().toEpochMilli()).isBetween(before + 29000, System.currentTimeMillis() + 31000);
    }
    @Test void invalidPolicyAndIdentityFailBeforeRedis() {
        assertThatThrownBy(() -> policy("invalid:scope", 1000, 1, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.join(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.leave("alice", "")).isInstanceOf(IllegalArgumentException.class);
    }
    String key(String suffix) { return "admission:{7:g1}:" + suffix; }
    static <T> List<T> parallel(int count, IntFunction<T> action) throws Exception {
        var pool = Executors.newFixedThreadPool(count); var start = new CyclicBarrier(count);
        try {
            var futures = new ArrayList<Future<T>>();
            for (int i=0; i<count; i++) { final int index=i; futures.add(pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return action.apply(index); })); }
            var result = new ArrayList<T>();
            for (var future : futures) result.add(future.get(10, TimeUnit.SECONDS));
            return result;
        } finally { pool.shutdownNow(); }
    }
}
