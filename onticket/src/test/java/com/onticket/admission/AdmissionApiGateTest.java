package com.onticket.admission;

import com.onticket.concert.controller.ReservationController;
import com.onticket.concert.domain.ConcertTime;
import com.onticket.concert.dto.CheckoutRequest;
import com.onticket.concert.repository.ConcertTimeRepository;
import com.onticket.concert.service.*;
import com.onticket.loadtest.LoadTestController;
import com.onticket.loadtest.LoadTestFixtureService;
import com.onticket.user.jwt.JwtUtil;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.*;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
class AdmissionApiGateTest {
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.7-alpine")
            .withExposedPorts(6379).withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(128L * 1024 * 1024));
    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;
    static final String CONCERT = "LOAD-TEST-gate";
    static final String BODY = "{\"concertTimeId\":7,\"seatNumberList\":[\"A1\"]}";
    AtomicLong time;
    RedisAdmissionStore store;
    AdmissionAccess access;
    JwtUtil jwt;
    SeatHoldService holds;
    CheckoutService checkouts;
    CheckoutCancellationService cancellations;
    CheckoutVerifiedReservationService verification;
    VerifiedReservationService direct;
    LoadTestFixtureService fixtures;
    MockMvc mvc;

    @BeforeAll static void connect() {
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet(); factory.start();
        redis = new StringRedisTemplate(factory); redis.afterPropertiesSet();
    }
    @AfterAll static void disconnect() { if (factory != null) factory.destroy(); }
    @BeforeEach void setup() {
        // Only this class's disposable container is cleared; no persisted/local fixture data.
        try (var connection = factory.getConnection()) { connection.serverCommands().flushDb(); }
        time = new AtomicLong(1000);
        var settings = new AdmissionSettings(true, CONCERT, 7, "api-g1", Instant.ofEpochMilli(1000),
                1, 2, Duration.ofSeconds(30), Duration.ofSeconds(10), 1, 16379, 5000);
        store = new RedisAdmissionStore(redis, settings.policy(), time::get); store.bootstrap();
        ObjectProvider<RedisAdmissionStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(store);
        var times = mock(ConcertTimeRepository.class);
        when(times.findByIdAndConcert_ConcertId(7L, CONCERT)).thenReturn(Optional.of(new ConcertTime()));
        when(times.findByIdAndConcert_ConcertId(8L, CONCERT)).thenReturn(Optional.of(new ConcertTime()));
        access = new AdmissionAccess(settings, provider, times);
        jwt = mock(JwtUtil.class);
        when(jwt.validateToken("alice-token")).thenReturn(true);
        when(jwt.getUsernameFromToken("alice-token")).thenReturn("alice");
        when(jwt.validateToken("bob-token")).thenReturn(true);
        when(jwt.getUsernameFromToken("bob-token")).thenReturn("bob");
        holds = mock(SeatHoldService.class); checkouts = mock(CheckoutService.class);
        cancellations = mock(CheckoutCancellationService.class);
        verification = mock(CheckoutVerifiedReservationService.class); direct = mock(VerifiedReservationService.class);
        fixtures = mock(LoadTestFixtureService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AdmissionController(access, jwt),
                        new ReservationController(direct, holds, checkouts, cancellations, verification, jwt, access),
                        new LoadTestController(fixtures, jwt, access))
                .setControllerAdvice(new AdmissionErrorAdvice())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(Jackson2ObjectMapperBuilder.json()
                        .featuresToDisable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build()))
                .build();
    }
    String admissionPath() { return "/main/detail/" + CONCERT + "/times/7/admission"; }
    Cookie alice() { return new Cookie("accessToken", "alice-token"); }
    Cookie bob() { return new Cookie("accessToken", "bob-token"); }
    void loseMarker() { redis.delete("admission:{7:api-g1}:meta"); }
    AdmissionSnapshot admitAlice() { store.join("alice"); store.tick(); return store.get("alice").orElseThrow(); }

    @Test void authenticationPrecedesRedisAndNoClientUserCanSelectAnotherOwner() throws Exception {
        mvc.perform(post(admissionPath())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mvc.perform(post(admissionPath()).cookie(alice()).param("username", "bob"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.admission.state").value("WAITING"))
                .andExpect(jsonPath("$.admission.token").isEmpty()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.serverNow").value("1970-01-01T00:00:01Z"));
        assertThat(store.get("alice")).isPresent(); assertThat(store.get("bob")).isEmpty();
        mvc.perform(get(admissionPath()).cookie(bob())).andExpect(status().isNotFound());
    }
    @Test void wrongConcertAndInactivePolicyCannotCreateQueueEntry() throws Exception {
        mvc.perform(post("/main/detail/OTHER/times/7/admission").cookie(alice())).andExpect(status().isNotFound());
        mvc.perform(get("/main/detail/" + CONCERT + "/times/8/admission-policy"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(post("/main/detail/" + CONCERT + "/times/8/admission").cookie(alice())).andExpect(status().isConflict());
        assertThat(store.get("alice")).isEmpty();
    }
    @Test void pollingDoesNotRenewExpiryAndOnlyAdmittedOwnerGetsCredentials() throws Exception {
        var waiting = store.join("alice"); time.set(2000);
        mvc.perform(get(admissionPath()).cookie(alice())).andExpect(status().isOk())
                .andExpect(jsonPath("$.admission.expiresAt").value("1970-01-01T00:00:31Z"));
        assertThat(store.get("alice").orElseThrow().expiresAt()).isEqualTo(waiting.expiresAt());
        store.tick(); var admitted = store.get("alice").orElseThrow();
        mvc.perform(get(admissionPath()).cookie(alice())).andExpect(status().isOk())
                .andExpect(jsonPath("$.admission.token").value(admitted.token()));
        mvc.perform(get(admissionPath()).cookie(bob())).andExpect(status().isNotFound());
    }
    @Test void fullQueueReturnsRetryAfterWithoutCreatingEntry() throws Exception {
        store.join("u1"); store.join("u2");
        mvc.perform(post(admissionPath()).cookie(alice())).andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "5"));
        assertThat(store.get("alice")).isEmpty();
    }
    @Test void oldEntryAndGenerationCannotRemoveCurrentEntry() throws Exception {
        var old = store.join("alice"); store.leave("alice", old.queueEntryId());
        var current = store.join("alice");
        mvc.perform(delete(admissionPath()).cookie(alice()).param("generation", "api-g1")
                        .param("queueEntryId", old.queueEntryId())).andExpect(status().isNoContent());
        mvc.perform(delete(admissionPath()).cookie(alice()).param("generation", "old")
                        .param("queueEntryId", current.queueEntryId())).andExpect(status().isNoContent());
        assertThat(store.get("alice").orElseThrow().state()).isEqualTo(AdmissionSnapshot.State.WAITING);
    }
    @Test void leaveIsOwnerBoundAndIdempotent() throws Exception {
        var entry = store.join("alice");
        mvc.perform(delete(admissionPath()).cookie(bob()).param("generation", "api-g1")
                        .param("queueEntryId", entry.queueEntryId())).andExpect(status().isNoContent());
        assertThat(store.get("alice").orElseThrow().state()).isEqualTo(AdmissionSnapshot.State.WAITING);
        for (int i = 0; i < 2; i++) mvc.perform(delete(admissionPath()).cookie(alice()).param("generation", "api-g1")
                .param("queueEntryId", entry.queueEntryId())).andExpect(status().isNoContent());
        assertThat(store.get("alice").orElseThrow().state()).isEqualTo(AdmissionSnapshot.State.LEFT);
    }
    @Test void noAdmissionOrAnotherOwnersTokenCannotReachHoldService() throws Exception {
        var admitted = admitAlice();
        mvc.perform(post("/main/detail/" + CONCERT + "/seat-holds").cookie(alice())
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        mvc.perform(post("/main/detail/" + CONCERT + "/seat-holds").cookie(bob())
                .header(AdmissionAccess.TOKEN_HEADER, admitted.token()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(holds);
    }
    @Test void validAdmissionReachesHoldButExpiryRevokesFurtherWrites() throws Exception {
        var admitted = admitAlice();
        mvc.perform(post("/main/detail/" + CONCERT + "/seat-holds").cookie(alice())
                .header(AdmissionAccess.TOKEN_HEADER, admitted.token()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());
        verify(holds).hold(eq("alice"), eq(CONCERT), any()); clearInvocations(holds);
        time.set(11000);
        mvc.perform(post("/main/detail/" + CONCERT + "/seat-holds").cookie(alice())
                .header(AdmissionAccess.TOKEN_HEADER, admitted.token()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(holds);
    }
    @Test void missingMarkerFailsClosedWithoutReinitialization() throws Exception {
        var admitted = admitAlice(); loseMarker();
        mvc.perform(get(admissionPath()).cookie(alice())).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ADMISSION_UNAVAILABLE"));
        mvc.perform(post("/main/detail/" + CONCERT + "/seat-holds").cookie(alice())
                .header(AdmissionAccess.TOKEN_HEADER, admitted.token()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable());
        verifyNoInteractions(holds); assertThat(redis.hasKey("admission:{7:api-g1}:meta")).isFalse();
    }
    @Test void newCheckoutGateExecutesBeforeCreationAndBlocksWithoutAdmission() throws Exception {
        when(checkouts.prepare(anyString(), anyString(), any(CheckoutRequest.class), anyString(), any(Runnable.class)))
                .thenAnswer(invocation -> { invocation.getArgument(4, Runnable.class).run(); return null; });
        mvc.perform(post("/main/detail/" + CONCERT + "/checkouts").cookie(alice()).header("Idempotency-Key", "new")
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        var admitted = admitAlice();
        mvc.perform(post("/main/detail/" + CONCERT + "/checkouts").cookie(alice()).header("Idempotency-Key", "new")
                .header(AdmissionAccess.TOKEN_HEADER, admitted.token()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());
    }
    @Test void directReservationAndLoadtestMutationCannotBypassGate() throws Exception {
        mvc.perform(post("/main/detail/" + CONCERT + "/verified-reservation").cookie(alice())
                .header("Idempotency-Key", "direct").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CHECKOUT_REQUIRED"));
        mvc.perform(post("/loadtest/runs").param("runId", "gate")).andExpect(status().isForbidden());
        mvc.perform(post("/loadtest/seat-holds/reset").param("runId", "gate")).andExpect(status().isForbidden());
        mvc.perform(get("/loadtest/tokens").param("runId", "gate")).andExpect(status().isForbidden());
        verifyNoInteractions(direct, fixtures);
        mvc.perform(post("/main/detail/" + CONCERT + "/reservation").cookie(alice()))
                .andExpect(status().isGone());
    }
    @Test void cleanupAndCheckoutVerificationRemainIndependentOfRedis() throws Exception {
        loseMarker();
        mvc.perform(delete("/main/detail/" + CONCERT + "/seat-holds").cookie(alice())
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isNoContent());
        mvc.perform(delete("/main/detail/" + CONCERT + "/checkouts/existing").cookie(alice())).andExpect(status().isOk());
        mvc.perform(post("/main/detail/" + CONCERT + "/checkouts/existing/verified-reservation").cookie(alice())
                .header("Idempotency-Key", "payment").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());
        verify(holds).release(eq("alice"), eq(CONCERT), any());
        verify(cancellations).cancel("alice", CONCERT, "existing");
        verify(verification).reserve(eq("alice"), eq(CONCERT), any(), eq("payment"));
    }
    @Test void inactiveTimeKeepsExistingPurchasePathEvenWithLostRedis() throws Exception {
        loseMarker();
        mvc.perform(post("/main/detail/" + CONCERT + "/seat-holds").cookie(alice())
                .contentType(MediaType.APPLICATION_JSON).content(BODY.replace(":7", ":8"))).andExpect(status().isOk());
        verify(holds).hold(eq("alice"), eq(CONCERT), any());
        mvc.perform(post("/main/detail/" + CONCERT + "/checkouts").cookie(alice()).header("Idempotency-Key", "inactive")
                .contentType(MediaType.APPLICATION_JSON).content(BODY.replace(":7", ":8"))).andExpect(status().isOk());
        verify(checkouts).prepare(eq("alice"), eq(CONCERT), any(), eq("inactive"));
    }
}
