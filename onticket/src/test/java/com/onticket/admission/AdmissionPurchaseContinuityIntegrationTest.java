package com.onticket.admission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onticket.concert.controller.ReservationController;
import com.onticket.concert.domain.*;
import com.onticket.concert.repository.*;
import com.onticket.concert.service.*;
import com.onticket.user.jwt.JwtUtil;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real Redis + MariaDB services. MockMvc is not a running HTTP server; only JWT and PG are mocked. */
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.show-sql=false",
        "spring.datasource.hikari.maximum-pool-size=4", "onticket.ticket.virtual-seat-unit-price=30000"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SeatHoldService.class, CheckoutService.class, CheckoutPreparationTransactionService.class,
        CheckoutExpirationService.class, CheckoutCancellationService.class,
        CheckoutVerifiedReservationService.class, CheckoutPaymentVerificationTransactionService.class,
        VerifiedReservationTransactionService.class, SeatReservationService.class, VirtualTicketPricePolicy.class,
        AdmissionPurchaseContinuityIntegrationTest.TimeConfiguration.class})
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Timeout(60)
class AdmissionPurchaseContinuityIntegrationTest {
    private static final String CONCERT = "LOAD-TEST-continuity";
    private static final LocalDateTime NOW = LocalDateTime.of(2030, 1, 1, 12, 0);
    @Container static final MariaDBContainer<?> DB = new MariaDBContainer<>("mariadb:10.11.8")
            .withDatabaseName("admission_continuity").withUsername("onticket").withPassword("onticket")
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(512L * 1024 * 1024));
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.7-alpine")
            .withExposedPorts(6379).withCommand("redis-server", "--maxmemory", "64mb", "--maxmemory-policy", "noeviction")
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(128L * 1024 * 1024));
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
        registry.add("spring.datasource.driver-class-name", DB::getDriverClassName);
    }
    @Autowired ConcertRepository concerts;
    @Autowired ConcertTimeRepository times;
    @Autowired SeatRepository seats;
    @Autowired CheckoutRepository checkouts;
    @Autowired CheckoutRequestKeyRepository keys;
    @Autowired CheckoutSeatAssignmentRepository assignments;
    @Autowired BookingRepository bookings;
    @Autowired ReservationRepository reservations;
    @Autowired PaymentRepository payments;
    @Autowired SeatHoldService holdService;
    @Autowired CheckoutService checkoutService;
    @Autowired CheckoutCancellationService cancellationService;
    @Autowired CheckoutVerifiedReservationService verificationService;
    @MockBean PaymentVerificationPort pg;
    @MockBean JwtUtil jwt;
    LettuceConnectionFactory firstConnection;
    LettuceConnectionFactory secondConnection;
    RedisAdmissionStore firstStore;
    RedisAdmissionStore secondStore;
    StringRedisTemplate redis;
    AdmissionSettings settings;
    MockMvc mvc;
    long timeId;
    ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();

    @BeforeEach void prepare() {
        // Both containers are disposable and exclusively owned by this test class.
        payments.deleteAllInBatch(); reservations.deleteAllInBatch(); assignments.deleteAllInBatch();
        keys.deleteAllInBatch(); checkouts.deleteAllInBatch(); bookings.deleteAllInBatch();
        seats.deleteAllInBatch(); times.deleteAllInBatch(); concerts.deleteAllInBatch();
        var concert = new Concert(); concert.setConcertId(CONCERT); concert.setConcertName("연속성 fixture");
        concerts.saveAndFlush(concert);
        var time = new ConcertTime(); time.setConcert(concert); time.setDate(LocalDate.of(2030, 1, 10));
        time.setStartTime(LocalTime.of(19, 0)); time.setSeatAmount(3);
        timeId = times.saveAndFlush(time).getId();
        for (String number : List.of("A1", "A2", "A3")) {
            var seat = new Seat(); seat.setSeatNumber(number); seat.setConcertTime(time); seats.saveAndFlush(seat);
        }
        settings = new AdmissionSettings(true, CONCERT, timeId, "continuity-g1", Instant.EPOCH,
                1, 4, Duration.ofMinutes(5), Duration.ofMinutes(5), 1, REDIS.getMappedPort(6379));
        // Same production loopback factory: one-second connect/command timeouts, no fallback.
        firstConnection = connection(); secondConnection = connection();
        redis = new StringRedisTemplate(firstConnection);
        firstStore = new RedisAdmissionStore(redis, settings.policy());
        secondStore = new RedisAdmissionStore(new StringRedisTemplate(secondConnection), settings.policy());
        firstStore.bootstrapFresh();
        ObjectProvider<RedisAdmissionStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(secondStore);
        var access = new AdmissionAccess(settings, provider, times);
        when(jwt.validateToken("alice-cookie")).thenReturn(true);
        when(jwt.getUsernameFromToken("alice-cookie")).thenReturn("alice");
        when(jwt.validateToken("bob-cookie")).thenReturn(true);
        when(jwt.getUsernameFromToken("bob-cookie")).thenReturn("bob");
        // Direct legacy verification is not used; all tested purchase services are actual Spring beans.
        mvc = MockMvcBuilders.standaloneSetup(new AdmissionController(access, jwt),
                new ReservationController(mock(VerifiedReservationService.class), holdService, checkoutService,
                        cancellationService, verificationService, jwt, access))
                .setControllerAdvice(new AdmissionErrorAdvice())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    }
    @AfterEach void disconnect() {
        if (secondConnection != null) secondConnection.destroy();
        if (firstConnection != null) firstConnection.destroy();
    }
    private LettuceConnectionFactory connection() {
        assertThat(REDIS.getHost()).isIn("localhost", "127.0.0.1");
        var factory = AdmissionRedisConnections.loopback(REDIS.getMappedPort(6379));
        factory.afterPropertiesSet(); factory.start(); return factory;
    }
    private String base() { return "/main/detail/" + CONCERT; }
    private String admission() { return base() + "/times/" + timeId + "/admission"; }
    private Cookie alice() { return new Cookie("accessToken", "alice-cookie"); }
    private String body(String seat) { return "{\"concertTimeId\":" + timeId + ",\"seatNumberList\":[\"" + seat + "\"]}"; }
    private AdmissionSnapshot admit() {
        firstStore.join("alice"); firstStore.tick(); return firstStore.get("alice").orElseThrow();
    }
    private void hold(String seat, AdmissionSnapshot entry) throws Exception {
        mvc.perform(post(base() + "/seat-holds").cookie(alice()).header(AdmissionAccess.TOKEN_HEADER, entry.token())
                .contentType(MediaType.APPLICATION_JSON).content(body(seat))).andExpect(status().isOk());
    }
    private String prepareCheckout(AdmissionSnapshot entry) throws Exception {
        hold("A1", entry);
        String result = mvc.perform(post(base() + "/checkouts").cookie(alice()).header("Idempotency-Key", "prepared")
                .header(AdmissionAccess.TOKEN_HEADER, entry.token()).contentType(MediaType.APPLICATION_JSON).content(body("A1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(result).get("merchantUid").asText();
    }
    private List<Object> snapshot() {
        return List.of(times.findById(timeId).orElseThrow().getSeatAmount(),
                seats.findAll().stream().sorted(java.util.Comparator.comparing(Seat::getSeatNumber))
                        .map(seat -> List.of(seat.getSeatNumber(), seat.isReserved(),
                        String.valueOf(seat.getHeldBy()), String.valueOf(seat.getHeldUntil()))).toList(),
                checkouts.count(), keys.count(), assignments.count(), bookings.count(), reservations.count(), payments.count());
    }
    private void assertNoReservations(int remaining) {
        assertThat(times.findById(timeId).orElseThrow().getSeatAmount()).isEqualTo(remaining);
        assertThat(bookings.count()).isZero(); assertThat(reservations.count()).isZero(); assertThat(payments.count()).isZero();
        assertThat(seats.findAll()).noneMatch(Seat::isReserved);
    }
    private AutoCloseable pauseOwnRedis() {
        var client = DockerClientFactory.instance().client();
        client.pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            assertThat(client.inspectContainerCmd(REDIS.getContainerId()).exec().getState().getPaused()).isTrue();
        } catch (RuntimeException | AssertionError failure) {
            client.unpauseContainerCmd(REDIS.getContainerId()).exec();
            throw failure;
        }
        return () -> client.unpauseContainerCmd(REDIS.getContainerId()).exec();
    }

    @Test void admissionIsSharedAcrossIndependentConnectionsButBoundToOwner() throws Exception {
        var entry = admit();
        var shared = secondStore.get("alice").orElseThrow();
        assertThat(shared.queueEntryId()).isEqualTo(entry.queueEntryId());
        assertThat(shared.expiresAt()).isEqualTo(entry.expiresAt());
        hold("A1", entry); // created through first store, verified through second connection + actual MariaDB write
        var before = snapshot();
        mvc.perform(post(base() + "/seat-holds").cookie(new Cookie("accessToken", "bob-cookie"))
                .header(AdmissionAccess.TOKEN_HEADER, entry.token()).contentType(MediaType.APPLICATION_JSON).content(body("A2")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMISSION_REQUIRED"));
        assertThat(snapshot()).isEqualTo(before);
        assertThat(secondStore.get("alice").orElseThrow().expiresAt()).isEqualTo(entry.expiresAt());
        assertNoReservations(3);
    }

    @Test void redisPauseBlocksNewPurchasesButAllowsReplayCancellationAndRelease() throws Exception {
        var entry = admit(); var merchant = prepareCheckout(entry); hold("A2", entry);
        var before = snapshot();
        try (var paused = pauseOwnRedis()) {
            mvc.perform(get(admission()).cookie(alice())).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("ADMISSION_UNAVAILABLE"));
            mvc.perform(post(base() + "/seat-holds").cookie(alice()).header(AdmissionAccess.TOKEN_HEADER, entry.token())
                    .contentType(MediaType.APPLICATION_JSON).content(body("A3"))).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("ADMISSION_UNAVAILABLE"));
            mvc.perform(post(base() + "/checkouts").cookie(alice()).header("Idempotency-Key", "new-during-outage")
                    .header(AdmissionAccess.TOKEN_HEADER, entry.token()).contentType(MediaType.APPLICATION_JSON).content(body("A2")))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("ADMISSION_UNAVAILABLE"));
            assertThat(snapshot()).isEqualTo(before); // blocked requests did not create or change purchase state
            mvc.perform(post(base() + "/checkouts").cookie(alice()).header("Idempotency-Key", "prepared")
                    .contentType(MediaType.APPLICATION_JSON).content(body("A1")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.merchantUid").value(merchant));
            assertThat(snapshot()).isEqualTo(before);
            for (int i = 0; i < 2; i++) {
                mvc.perform(delete(base() + "/checkouts/" + merchant).cookie(alice()))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELED"));
                mvc.perform(delete(base() + "/seat-holds").cookie(alice()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("A2"))).andExpect(status().isNoContent());
            }
            assertThat(checkouts.count()).isEqualTo(1);
            assertThat(assignments.findAll()).allSatisfy(assignment -> assertThat(assignment.getActiveSeatId()).isNull());
            assertThat(seats.findAll()).allSatisfy(seat -> {
                assertThat(seat.getHeldBy()).isNull(); assertThat(seat.getHeldUntil()).isNull();
            });
            assertNoReservations(3); verifyNoInteractions(pg);
        }
        var restored = secondStore.get("alice").orElseThrow();
        assertThat(restored.queueEntryId()).isEqualTo(entry.queueEntryId());
        assertThat(restored.expiresAt()).isEqualTo(entry.expiresAt());
        assertThat(redis.opsForHash().get("admission:{" + timeId + ":continuity-g1}:meta", "seq")).isEqualTo("1");
        hold("A3", entry);
        assertThat(seats.findAll().stream().filter(seat -> "A3".equals(seat.getSeatNumber())).findFirst().orElseThrow().getHeldBy())
                .isEqualTo("alice");
    }

    @Test void redisPauseDoesNotPreventPreparedCheckoutMockPaymentOrReplay() throws Exception {
        var entry = admit(); var merchant = prepareCheckout(entry);
        when(pg.verify("mock-payment")).thenReturn(new PaymentApproval("mock-payment", merchant, null, 30000, true, NOW));
        var verificationBody = body("A1").replace("}", ",\"paymentId\":\"mock-payment\"}");
        try (var paused = pauseOwnRedis()) {
            // Prove Redis is unavailable during the same window, not merely unused by the test.
            mvc.perform(get(admission()).cookie(alice())).andExpect(status().isServiceUnavailable());
            for (int i = 0; i < 2; i++) mvc.perform(post(base() + "/checkouts/" + merchant + "/verified-reservation")
                    .cookie(alice()).header("Idempotency-Key", "verification")
                    .contentType(MediaType.APPLICATION_JSON).content(verificationBody)).andExpect(status().isOk());
            assertThat(checkouts.findByMerchantUid(merchant).orElseThrow().getStatus()).isEqualTo(CheckoutStatus.RESERVATION_CONFIRMED);
            assertThat(bookings.count()).isEqualTo(1); assertThat(reservations.count()).isEqualTo(1); assertThat(payments.count()).isEqualTo(1);
            assertThat(seats.countByConcertTimeIdAndReservedTrue(timeId)).isEqualTo(1);
            assertThat(times.findById(timeId).orElseThrow().getSeatAmount()).isEqualTo(2);
            assertThat(seats.findAll()).allSatisfy(seat -> {
                assertThat(seat.getHeldBy()).isNull(); assertThat(seat.getHeldUntil()).isNull();
            });
            verify(pg, times(1)).verify("mock-payment");
        }
        assertThat(secondStore.get("alice").orElseThrow().expiresAt()).isEqualTo(entry.expiresAt());
    }

    @TestConfiguration static class TimeConfiguration {
        @Bean @Primary Clock testClock() { return Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC); }
    }
}
