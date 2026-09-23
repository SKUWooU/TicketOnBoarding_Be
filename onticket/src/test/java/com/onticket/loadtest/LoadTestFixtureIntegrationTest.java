package com.onticket.loadtest;

import com.onticket.concert.config.SeatHoldConfiguration;
import com.onticket.concert.domain.Booking;
import com.onticket.concert.domain.Payment;
import com.onticket.concert.domain.Seat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onticket.concert.repository.BookingRepository;
import com.onticket.concert.repository.ConcertDetailRepository;
import com.onticket.concert.repository.ConcertRepository;
import com.onticket.concert.repository.ConcertTimeRepository;
import com.onticket.concert.repository.PaymentRepository;
import com.onticket.concert.repository.PlaceRepository;
import com.onticket.concert.repository.SeatRepository;
import com.onticket.concert.service.ConcertService;
import com.onticket.concert.service.SeatLayoutQueryService;
import com.onticket.concert.service.VirtualSeatLayoutFactory;
import com.onticket.user.domain.SiteUser;
import com.onticket.user.controller.AuthController;
import com.onticket.user.dto.UserInfoDto;
import com.onticket.user.jwt.JwtUtil;
import com.onticket.user.repository.UserRepository;
import com.onticket.user.service.RefreshTokenService;
import com.onticket.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.show-sql=false",
        "onticket.loadtest.fixture.rows=50",
        "onticket.loadtest.fixture.seats-per-row=40"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("loadtest")
@Import({
        LoadTestFixtureService.class,
        SeatHoldConfiguration.class,
        VirtualSeatLayoutFactory.class,
        SeatLayoutQueryService.class,
        ConcertService.class
})
@Testcontainers
class LoadTestFixtureIntegrationTest {

    @Container
    static final MariaDBContainer<?> MARIA_DB = new MariaDBContainer<>("mariadb:10.11.8")
            .withDatabaseName("onticket_loadtest_fixture")
            .withUsername("onticket")
            .withPassword("onticket");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MARIA_DB::getJdbcUrl);
        registry.add("spring.datasource.username", MARIA_DB::getUsername);
        registry.add("spring.datasource.password", MARIA_DB::getPassword);
        registry.add("spring.datasource.driver-class-name", MARIA_DB::getDriverClassName);
    }

    @Autowired
    private LoadTestFixtureService fixtureService;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private ConcertRepository concertRepository;

    @Autowired
    private ConcertTimeRepository concertTimeRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private SeatLayoutQueryService seatLayoutQueryService;

    @Autowired
    private ConcertService concertService;

    @Autowired
    private PlaceRepository placeRepository;

    @Autowired
    private ConcertDetailRepository concertDetailRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private UserRepository userRepository;

    @Test
    void createsTwoThousandSeatsAndKeepsInitializationIdempotent() {
        LoadTestFixtureService.FixtureMetadata first = fixtureService.initialize("run-a");
        LoadTestFixtureService.FixtureMetadata second = fixtureService.initialize("run-a");
        LoadTestFixtureService.InventorySnapshot snapshot = fixtureService.snapshot("run-a");

        assertThat(first).isEqualTo(second);
        assertThat(first.totalSeats()).isEqualTo(2_000);
        assertThat(first.rows()).isEqualTo(50);
        assertThat(first.seatsPerRow()).isEqualTo(40);
        assertThat(snapshot.actualSeatCount()).isEqualTo(2_000);
        assertThat(snapshot.remainingSeats()).isEqualTo(2_000);
        assertThat(snapshot.reservedSeats()).isZero();
        assertThat(snapshot.reservations()).isZero();
        assertThat(snapshot.invariantSatisfied()).isTrue();
        assertThat(seatLayoutQueryService.getSectionSummary(first.concertId(), first.concertTimeId()).sections())
                .hasSize(10)
                .allSatisfy(section -> {
                    assertThat(section.totalSeats()).isEqualTo(200);
                    assertThat(section.availableSeats()).isEqualTo(200);
                });
        assertThat(seatLayoutQueryService.getSection(first.concertId(), first.concertTimeId(), "S01").seats())
                .hasSize(200);
        assertThat(concertService.getConcertDetail(first.concertId()))
                .satisfies(detail -> {
                    assertThat(detail.getPlaceName()).isEqualTo("가상 부하 공연장");
                    assertThat(detail.getAddr()).contains("run-a fixture");
                    assertThat(detail.getLa()).isEqualByComparingTo("37.5665000");
                    assertThat(detail.getLo()).isEqualByComparingTo("126.9780000");
                    assertThat(detail.getReviewList()).isEmpty();
                });
        assertThat(placeRepository.findByPlaceId("LOAD-TEST-PLACE-run-a")).isNotNull();
    }

    @Test
    void createsIndependentConcertTimeAndInventoryForEachRun() {
        LoadTestFixtureService.FixtureMetadata first = fixtureService.initialize("run-one");
        LoadTestFixtureService.FixtureMetadata second = fixtureService.initialize("run-two");

        assertThat(first.concertId()).isNotEqualTo(second.concertId());
        assertThat(first.concertTimeId()).isNotEqualTo(second.concertTimeId());
        assertThat(fixtureService.snapshot("run-one").actualSeatCount()).isEqualTo(2_000);
        assertThat(fixtureService.snapshot("run-two").actualSeatCount()).isEqualTo(2_000);
        assertThat(fixtureService.snapshot("run-one").invariantSatisfied()).isTrue();
        assertThat(fixtureService.snapshot("run-two").invariantSatisfied()).isTrue();
    }

    @Test
    void repairsAnExistingFixtureThatWasCreatedWithoutPlaceMetadata() {
        LoadTestFixtureService.FixtureMetadata fixture = fixtureService.initialize("legacy-run");
        concertDetailRepository.findById(fixture.concertId()).orElseThrow().setPlaceId(null);
        entityManager.flush();
        placeRepository.deleteAll();
        entityManager.flush();

        LoadTestFixtureService.FixtureMetadata repaired = fixtureService.initialize("legacy-run");

        assertThat(repaired).isEqualTo(fixture);
        assertThat(concertService.getConcertDetail(fixture.concertId()))
                .satisfies(detail -> {
                    assertThat(detail.getPlaceName()).isEqualTo("가상 부하 공연장");
                    assertThat(detail.getAddr()).contains("legacy-run fixture");
                });
        assertThat(placeRepository.findByPlaceId("LOAD-TEST-PLACE-legacy-run")).isNotNull();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void serializesFixtureDetailAfterTheServiceTransactionEnds() throws Exception {
        LoadTestFixtureService.FixtureMetadata fixture = fixtureService.initialize("http-detail-run");

        String json = new ObjectMapper().writeValueAsString(
                concertService.getConcertDetail(fixture.concertId())
        );

        assertThat(json)
                .contains("가상 부하 공연장")
                .contains("http-detail-run fixture")
                .contains("\"reviewList\":[]");
    }

    @Test
    void usesFixedWidthCanonicalSeatNumbers() {
        assertThat(LoadTestFixtureService.seatNumber(1, 1)).isEqualTo("R001-S001");
        assertThat(LoadTestFixtureService.seatNumber(50, 40)).isEqualTo("R050-S040");
    }

    @Test
    void rejectsInvalidRunIdBeforeChangingFixtureData() {
        assertThatThrownBy(() -> fixtureService.initialize("invalid_run_id"))
                .isInstanceOf(InvalidLoadTestRunIdException.class)
                .hasMessage("loadtest runId는 영문·숫자·하이픈 1~32자여야 합니다.");

        assertThat(concertRepository.count()).isZero();
        assertThat(concertTimeRepository.count()).isZero();
        assertThat(seatRepository.count()).isZero();
        assertThat(placeRepository.count()).isZero();
    }

    @Test
    void excludesAnotherLoadtestRunBookingAndPaymentFromSnapshot() {
        Booking unrelatedBooking = new Booking();
        unrelatedBooking.setUsername("load-user-run-a-extra.001");
        unrelatedBooking.setIdempotencyKey("lt-run-a-extra.distributed-1");
        unrelatedBooking.setRequestFingerprint("0".repeat(64));
        unrelatedBooking.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        unrelatedBooking = bookingRepository.saveAndFlush(unrelatedBooking);

        Payment unrelatedPayment = Payment.approved(
                "LT:load-user-run-a-extra.001:30000:distributed-1",
                "load-user-run-a-extra.001",
                30_000,
                LocalDateTime.of(2026, 1, 1, 0, 0),
                unrelatedBooking
        );
        paymentRepository.saveAndFlush(unrelatedPayment);

        fixtureService.initialize("run-a");
        LoadTestFixtureService.InventorySnapshot snapshot = fixtureService.snapshot("run-a");

        assertThat(snapshot.bookings()).isZero();
        assertThat(snapshot.payments()).isZero();
        assertThat(snapshot.invariantSatisfied()).isTrue();
    }

    @Test
    void seatHoldSnapshotAndResetReuseTheSameTwoThousandSeats() {
        LoadTestFixtureService.FixtureMetadata fixture = fixtureService.initialize("hold-run");
        Seat seat = seatRepository.findByConcertTimeAndSeatNumber(fixture.concertTimeId(), "R001-S001");
        LocalDateTime now = LocalDateTime.now();
        seat.holdFor("load-user-hold-run.001", now, now.plusMinutes(5));
        seatRepository.saveAndFlush(seat);

        LoadTestFixtureService.SeatHoldSnapshot held = fixtureService.seatHoldSnapshot("hold-run");
        assertThat(held.actualSeatCount()).isEqualTo(2_000);
        assertThat(held.activeHeldSeats()).isOne();
        assertThat(held.holdRows()).isOne();
        assertThat(held.partialHoldStates()).isZero();
        assertThat(held.reservedSeats()).isZero();
        assertThat(held.invariantSatisfied()).isTrue();

        LoadTestFixtureService.SeatHoldSnapshot reset = fixtureService.resetSeatHolds("hold-run");
        assertThat(reset.actualSeatCount()).isEqualTo(2_000);
        assertThat(reset.activeHeldSeats()).isZero();
        assertThat(reset.holdRows()).isZero();
        assertThat(reset.remainingSeats()).isEqualTo(2_000);
        assertThat(reset.invariantSatisfied()).isTrue();
    }

    @Test
    void createsIsolatedFixtureUsersIdempotently() {
        java.util.List<String> first = fixtureService.ensureUsers("browser-auth", 2);
        java.util.List<String> retry = fixtureService.ensureUsers("browser-auth", 2);
        java.util.List<String> secondRun = fixtureService.ensureUsers("browser-auth-other", 1);

        assertThat(retry).isEqualTo(first);
        assertThat(first).containsExactly("load-user-browser-auth.001", "load-user-browser-auth.002");
        assertThat(secondRun).containsExactly("load-user-browser-auth-other.001");
        assertThat(userRepository.findByUsername(first.get(0)))
                .extracting(SiteUser::getNickname, SiteUser::getCode)
                .containsExactly("loadtest-browser-auth-1", 1);
        assertThat(userRepository.countByUsernameStartingWith("load-user-browser-auth")).isEqualTo(3);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void createsOneFixtureUserForConcurrentRetriesOfTheSameRun() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            java.util.concurrent.Future<java.util.List<String>> first = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return fixtureService.ensureUsers("concurrent-user", 1);
            });
            java.util.concurrent.Future<java.util.List<String>> second = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return fixtureService.ensureUsers("concurrent-user", 1);
            });

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS)).containsExactly("load-user-concurrent-user.001");
            assertThat(second.get(10, TimeUnit.SECONDS)).containsExactly("load-user-concurrent-user.001");
            assertThat(userRepository.countByUsernameStartingWith("load-user-concurrent-user")).isOne();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void validatesFixtureJwtThroughTheAuthContract() {
        String username = fixtureService.ensureUsers("browser-auth-contract", 1).get(0);
        JwtUtil jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "issuer", "loadtest-fixture-test");
        jwtUtil.init();
        AuthController authController = new AuthController(
                org.mockito.Mockito.mock(PasswordEncoder.class),
                org.mockito.Mockito.mock(AuthenticationManager.class),
                jwtUtil,
                org.mockito.Mockito.mock(RefreshTokenService.class),
                userRepository,
                org.mockito.Mockito.mock(UserService.class)
        );

        ResponseEntity<?> response = authController.validateToken(jwtUtil.generateAccessToken(username));

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isInstanceOf(UserInfoDto.class);
        assertThat(((UserInfoDto) response.getBody()).isValid()).isTrue();
        assertThat(((UserInfoDto) response.getBody()).getUserName()).isEqualTo(username);
    }

    @Test
    void rejectsInvalidRunIdBeforeCreatingFixtureUsers() {
        assertThatThrownBy(() -> fixtureService.ensureUsers("invalid_run_id", 1))
                .isInstanceOf(InvalidLoadTestRunIdException.class);

        assertThat(userRepository.countByUsernameStartingWith("load-user-invalid")).isZero();
    }
}
