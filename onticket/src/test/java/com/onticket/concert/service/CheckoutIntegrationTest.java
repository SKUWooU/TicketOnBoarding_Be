package com.onticket.concert.service;

import com.onticket.concert.domain.Checkout;
import com.onticket.concert.domain.CheckoutSeatAssignment;
import com.onticket.concert.domain.CheckoutStatus;
import com.onticket.concert.domain.Concert;
import com.onticket.concert.domain.ConcertTime;
import com.onticket.concert.domain.Seat;
import com.onticket.concert.dto.CheckoutRequest;
import com.onticket.concert.dto.CheckoutResponse;
import com.onticket.concert.dto.SeatHoldRequest;
import com.onticket.concert.repository.CheckoutRepository;
import com.onticket.concert.repository.CheckoutRequestKeyRepository;
import com.onticket.concert.repository.CheckoutSeatAssignmentRepository;
import com.onticket.concert.repository.ConcertRepository;
import com.onticket.concert.repository.ConcertTimeRepository;
import com.onticket.concert.repository.SeatRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.show-sql=false",
        "onticket.ticket.virtual-seat-unit-price=30000",
        "onticket.ticket.seat-hold-duration=PT5M"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        CheckoutService.class,
        CheckoutPreparationTransactionService.class,
        CheckoutExpirationService.class,
        VirtualTicketPricePolicy.class,
        SeatHoldService.class,
        CheckoutIntegrationTest.ClockConfiguration.class
})
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CheckoutIntegrationTest {

    private static final String CONCERT_ID = "CHECKOUT-CONCERT";
    private static final String USERNAME = "checkout-user";
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2030, 1, 1, 12, 0);

    @Container
    static final MariaDBContainer<?> MARIA_DB = new MariaDBContainer<>("mariadb:10.11.8")
            .withDatabaseName("onticket_checkout")
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
    private CheckoutService checkoutService;

    @Autowired
    private SeatHoldService seatHoldService;

    @Autowired
    private CheckoutRepository checkoutRepository;

    @Autowired
    private CheckoutRequestKeyRepository checkoutRequestKeyRepository;

    @Autowired
    private CheckoutSeatAssignmentRepository checkoutSeatAssignmentRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private ConcertTimeRepository concertTimeRepository;

    @Autowired
    private ConcertRepository concertRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private MutableClock clock;

    private Long concertTimeId;

    @BeforeEach
    void setUp() {
        checkoutSeatAssignmentRepository.deleteAllInBatch();
        checkoutRequestKeyRepository.deleteAllInBatch();
        checkoutRepository.deleteAllInBatch();
        seatRepository.deleteAllInBatch();
        concertTimeRepository.deleteAllInBatch();
        concertRepository.deleteAllInBatch();
        entityManager.clear();
        clock.set(BASE_TIME);
        concertTimeId = createFixture();
    }

    @Test
    void checkoutUsesServerAmountAndEarliestOwnedHoldExpiry() {
        seatHoldService.hold(USERNAME, CONCERT_ID, holdRequest("A1"));
        clock.advanceSeconds(60);
        seatHoldService.hold(USERNAME, CONCERT_ID, holdRequest("A2"));

        CheckoutResponse response = checkoutService.prepare(
                USERNAME,
                CONCERT_ID,
                checkoutRequest("A2", "A1"),
                "checkout-key-1"
        );

        assertThat(response.getMerchantUid()).startsWith("ticket_");
        assertThat(response.getAmount()).isEqualTo(60_000);
        assertThat(response.getExpiresAt()).isEqualTo(BASE_TIME.plusMinutes(5));
        assertThat(response.getStatus()).isEqualTo(CheckoutStatus.READY);
        assertThat(checkoutRepository.count()).isEqualTo(1);
        assertThat(checkoutSeatAssignmentRepository.count()).isEqualTo(2);
    }

    @Test
    void sameIdempotencyKeyReusesResultAndRejectsDifferentPayload() {
        seatHoldService.hold(USERNAME, CONCERT_ID, holdRequest("A1", "A2"));

        CheckoutResponse first = checkoutService.prepare(
                USERNAME,
                CONCERT_ID,
                checkoutRequest("A1"),
                "checkout-key-2"
        );
        CheckoutResponse retry = checkoutService.prepare(
                USERNAME,
                CONCERT_ID,
                checkoutRequest("A1"),
                "checkout-key-2"
        );

        assertThat(retry.getMerchantUid()).isEqualTo(first.getMerchantUid());
        assertThat(retry.getExpiresAt()).isEqualTo(first.getExpiresAt());
        assertThat(checkoutRepository.count()).isEqualTo(1);

        assertThatThrownBy(() -> checkoutService.prepare(
                USERNAME,
                CONCERT_ID,
                checkoutRequest("A2"),
                "checkout-key-2"
        )).isExactlyInstanceOf(IdempotencyKeyConflictException.class);
        assertThat(checkoutRepository.count()).isEqualTo(1);
    }

    @Test
    void differentIdempotencyKeysReuseTheCheckoutForTheSameActiveHold() {
        seatHoldService.hold(USERNAME, CONCERT_ID, holdRequest("A1"));

        CheckoutResponse first = checkoutService.prepare(
                USERNAME,
                CONCERT_ID,
                checkoutRequest("A1"),
                "checkout-hold-key-1"
        );
        CheckoutResponse second = checkoutService.prepare(
                USERNAME,
                CONCERT_ID,
                checkoutRequest("A1"),
                "checkout-hold-key-2"
        );

        assertThat(second.getMerchantUid()).isEqualTo(first.getMerchantUid());
        assertThat(second.getExpiresAt()).isEqualTo(first.getExpiresAt());
        assertThat(checkoutRepository.count()).isEqualTo(1);
        assertThat(checkoutRequestKeyRepository.count()).isEqualTo(2);
        assertThat(checkoutSeatAssignmentRepository.count()).isEqualTo(1);

        CheckoutResponse secondRetry = checkoutService.prepare(
                USERNAME,
                CONCERT_ID,
                checkoutRequest("A1"),
                "checkout-hold-key-2"
        );
        assertThat(secondRetry.getMerchantUid()).isEqualTo(first.getMerchantUid());

        assertThatThrownBy(() -> checkoutService.prepare(
                USERNAME,
                CONCERT_ID,
                checkoutRequest("A2"),
                "checkout-hold-key-2"
        )).isExactlyInstanceOf(IdempotencyKeyConflictException.class);
        assertThat(checkoutRepository.count()).isEqualTo(1);
    }

    @RepeatedTest(3)
    void concurrentDifferentKeysConvergeOnOneCheckoutForTheSameActiveHold() throws Exception {
        seatHoldService.hold(USERNAME, CONCERT_ID, holdRequest("A1"));
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<CheckoutResponse> first = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return checkoutService.prepare(
                        USERNAME,
                        CONCERT_ID,
                        checkoutRequest("A1"),
                        "checkout-concurrent-hold-key-1"
                );
            });
            Future<CheckoutResponse> second = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return checkoutService.prepare(
                        USERNAME,
                        CONCERT_ID,
                        checkoutRequest("A1"),
                        "checkout-concurrent-hold-key-2"
                );
            });
            start.countDown();

            CheckoutResponse firstResult = first.get(10, TimeUnit.SECONDS);
            CheckoutResponse secondResult = second.get(10, TimeUnit.SECONDS);
            assertThat(secondResult.getMerchantUid()).isEqualTo(firstResult.getMerchantUid());
        }

        assertThat(checkoutRepository.count()).isEqualTo(1);
        assertThat(checkoutRequestKeyRepository.count()).isEqualTo(2);
        assertThat(checkoutSeatAssignmentRepository.count()).isEqualTo(1);
    }

    @Test
    void differentSeatSelectionsCreateIndependentCheckouts() {
        seatHoldService.hold(USERNAME, CONCERT_ID, holdRequest("A1", "A2"));

        CheckoutResponse first = checkoutService.prepare(
                USERNAME,
                CONCERT_ID,
                checkoutRequest("A1"),
                "checkout-different-seat-key-1"
        );
        CheckoutResponse second = checkoutService.prepare(
                USERNAME,
                CONCERT_ID,
                checkoutRequest("A2"),
                "checkout-different-seat-key-2"
        );

        assertThat(second.getMerchantUid()).isNotEqualTo(first.getMerchantUid());
        assertThat(checkoutRepository.count()).isEqualTo(2);
        assertThat(checkoutSeatAssignmentRepository.count()).isEqualTo(2);
    }

    @Test
    void activeCheckoutRejectsASecondCheckoutThatAddsAnAssignedSeat() {
        seatHoldService.hold(USERNAME, CONCERT_ID, holdRequest("A1", "A2"));
        CheckoutResponse first = checkoutService.prepare(
                USERNAME,
                CONCERT_ID,
                checkoutRequest("A1"),
                "checkout-overlap-small-first"
        );

        assertThatThrownBy(() -> checkoutService.prepare(
                USERNAME,
                CONCERT_ID,
                checkoutRequest("A1", "A2"),
                "checkout-overlap-large-second"
        )).isExactlyInstanceOf(CheckoutConflictException.class)
                .hasMessageContaining("활성 결제 요청");

        assertThat(checkoutRepository.count()).isEqualTo(1);
        assertThat(checkoutRequestKeyRepository.count()).isEqualTo(1);
        assertThat(checkoutSeatAssignmentRepository.count()).isEqualTo(1);
        assertThat(checkoutRepository.findByMerchantUid(first.getMerchantUid())).isPresent();
    }

    @Test
    void activeCheckoutRejectsASecondCheckoutThatRemovesAnAssignedSeat() {
        seatHoldService.hold(USERNAME, CONCERT_ID, holdRequest("A1", "A2"));
        CheckoutResponse first = checkoutService.prepare(
                USERNAME,
                CONCERT_ID,
                checkoutRequest("A1", "A2"),
                "checkout-overlap-large-first"
        );

        assertThatThrownBy(() -> checkoutService.prepare(
                USERNAME,
                CONCERT_ID,
                checkoutRequest("A1"),
                "checkout-overlap-small-second"
        )).isExactlyInstanceOf(CheckoutConflictException.class)
                .hasMessageContaining("활성 결제 요청");

        assertThat(checkoutRepository.count()).isEqualTo(1);
        assertThat(checkoutRequestKeyRepository.count()).isEqualTo(1);
        assertThat(checkoutSeatAssignmentRepository.count()).isEqualTo(2);
        assertThat(checkoutRepository.findByMerchantUid(first.getMerchantUid())).isPresent();
    }

    @RepeatedTest(3)
    void concurrentPartiallyOverlappingCheckoutsAllowOnlyOneWinner() throws Exception {
        seatHoldService.hold(USERNAME, CONCERT_ID, holdRequest("A1", "A2"));
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<CheckoutAttempt> first = executor.submit(() -> checkoutAttempt(
                    start,
                    checkoutRequest("A1"),
                    "checkout-overlap-concurrent-small"
            ));
            Future<CheckoutAttempt> second = executor.submit(() -> checkoutAttempt(
                    start,
                    checkoutRequest("A1", "A2"),
                    "checkout-overlap-concurrent-large"
            ));
            start.countDown();

            List<CheckoutAttempt> attempts = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS)
            );
            assertThat(attempts).filteredOn(CheckoutAttempt::successful).hasSize(1);
            assertThat(attempts).filteredOn(attempt ->
                    CheckoutConflictException.class.equals(attempt.failureType())).hasSize(1);

            CheckoutAttempt winner = attempts.stream()
                    .filter(CheckoutAttempt::successful)
                    .findFirst()
                    .orElseThrow();
            assertThat(checkoutSeatAssignmentRepository.count()).isEqualTo(winner.seatCount());
        }

        assertThat(checkoutRepository.count()).isEqualTo(1);
        assertThat(checkoutRequestKeyRepository.count()).isEqualTo(1);
    }

    @Test
    void activeCheckoutPreventsExplicitHoldRelease() {
        seatHoldService.hold(USERNAME, CONCERT_ID, holdRequest("A1"));
        CheckoutResponse checkout = checkoutService.prepare(
                USERNAME,
                CONCERT_ID,
                checkoutRequest("A1"),
                "checkout-active-release"
        );

        assertThatThrownBy(() -> seatHoldService.release(
                USERNAME,
                CONCERT_ID,
                holdRequest("A1")
        )).isExactlyInstanceOf(SeatHoldConflictException.class)
                .hasMessageContaining("결제 준비 중");

        Seat seat = seatRepository.findByConcertTimeAndSeatNumber(concertTimeId, "A1");
        assertThat(seat.getHeldBy()).isEqualTo(USERNAME);
        assertThat(seat.getHeldUntil()).isEqualTo(BASE_TIME.plusMinutes(5));
        assertThat(checkoutRepository.findByMerchantUid(checkout.getMerchantUid()).orElseThrow().getStatus())
                .isEqualTo(CheckoutStatus.READY);
        assertThat(checkoutSeatAssignmentRepository.count()).isEqualTo(1);
    }

    @Test
    void earliestCheckoutExpiryEndsEveryAssignmentForStaggeredSeatHolds() {
        seatHoldService.hold(USERNAME, CONCERT_ID, holdRequest("A1"));
        clock.advanceSeconds(60);
        seatHoldService.hold(USERNAME, CONCERT_ID, holdRequest("A2"));
        CheckoutResponse first = checkoutService.prepare(
                USERNAME,
                CONCERT_ID,
                checkoutRequest("A1", "A2"),
                "checkout-staggered-holds"
        );
        Checkout storedFirst = checkoutRepository.findByMerchantUid(first.getMerchantUid()).orElseThrow();

        assertThat(first.getExpiresAt()).isEqualTo(BASE_TIME.plusMinutes(5));
        assertThat(checkoutSeatAssignmentRepository.findByCheckoutId(storedFirst.getId()))
                .hasSize(2)
                .allSatisfy(assignment ->
                        assertThat(assignment.getActiveUntil()).isEqualTo(first.getExpiresAt()));

        clock.set(first.getExpiresAt());
        assertThatThrownBy(() -> checkoutService.prepare(
                USERNAME,
                CONCERT_ID,
                checkoutRequest("A1", "A2"),
                "checkout-staggered-holds"
        )).isExactlyInstanceOf(CheckoutExpiredException.class);
        seatHoldService.release(USERNAME, CONCERT_ID, holdRequest("A2"));
        Seat released = seatRepository.findByConcertTimeAndSeatNumber(concertTimeId, "A2");
        assertThat(released.getHeldBy()).isNull();
        assertThat(released.getHeldUntil()).isNull();

        seatHoldService.hold(USERNAME, CONCERT_ID, holdRequest("A2"));
        CheckoutResponse second = checkoutService.prepare(
                USERNAME,
                CONCERT_ID,
                checkoutRequest("A2"),
                "checkout-staggered-holds-renewed"
        );

        assertThat(second.getExpiresAt()).isEqualTo(BASE_TIME.plusMinutes(10));
        assertThat(checkoutRepository.count()).isEqualTo(2);
        assertThat(checkoutSeatAssignmentRepository.count()).isEqualTo(3);
    }

    @Test
    void newHoldAfterExpiryCreatesANewCheckout() {
        seatHoldService.hold(USERNAME, CONCERT_ID, holdRequest("A1"));
        CheckoutResponse first = checkoutService.prepare(
                USERNAME,
                CONCERT_ID,
                checkoutRequest("A1"),
                "checkout-expiring-hold-key"
        );

        clock.set(first.getExpiresAt());
        assertThatThrownBy(() -> checkoutService.prepare(
                USERNAME,
                CONCERT_ID,
                checkoutRequest("A1"),
                "checkout-expiring-hold-key"
        )).isExactlyInstanceOf(CheckoutExpiredException.class);
        seatHoldService.hold(USERNAME, CONCERT_ID, holdRequest("A1"));

        CheckoutResponse second = checkoutService.prepare(
                USERNAME,
                CONCERT_ID,
                checkoutRequest("A1"),
                "checkout-renewed-hold-key"
        );

        assertThat(second.getMerchantUid()).isNotEqualTo(first.getMerchantUid());
        assertThat(second.getExpiresAt()).isEqualTo(first.getExpiresAt().plusMinutes(5));
        assertThat(checkoutRepository.count()).isEqualTo(2);
        assertThat(checkoutRepository.findByMerchantUid(first.getMerchantUid()).orElseThrow().getStatus())
                .isEqualTo(CheckoutStatus.EXPIRED);
        assertThat(checkoutRepository.findByMerchantUid(second.getMerchantUid()).orElseThrow().getStatus())
                .isEqualTo(CheckoutStatus.READY);
        assertThat(checkoutSeatAssignmentRepository.count()).isEqualTo(2);
    }

    @Test
    void databaseConstraintRejectsDuplicateHoldIdentity() {
        LocalDateTime expiresAt = BASE_TIME.plusMinutes(5);
        Checkout first = Checkout.ready(
                "ticket_direct_1",
                USERNAME,
                "direct-hold-key-1",
                CONCERT_ID,
                concertTimeId,
                "f".repeat(64),
                30_000,
                BASE_TIME,
                expiresAt
        );
        Checkout duplicate = Checkout.ready(
                "ticket_direct_2",
                USERNAME,
                "direct-hold-key-2",
                CONCERT_ID,
                concertTimeId,
                "f".repeat(64),
                30_000,
                BASE_TIME,
                expiresAt
        );
        checkoutRepository.saveAndFlush(first);

        assertThatThrownBy(() -> checkoutRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(checkoutRepository.count()).isEqualTo(1);
    }

    @Test
    void databaseConstraintRejectsDuplicateSeatAssignmentForTheSameHoldWindow() {
        seatHoldService.hold(USERNAME, CONCERT_ID, holdRequest("A1"));
        checkoutService.prepare(
                USERNAME,
                CONCERT_ID,
                checkoutRequest("A1"),
                "checkout-assignment-constraint-first"
        );
        Seat seat = seatRepository.findByConcertTimeAndSeatNumber(concertTimeId, "A1");
        Checkout duplicate = checkoutRepository.saveAndFlush(Checkout.ready(
                "ticket_assignment_duplicate",
                USERNAME,
                "checkout-assignment-constraint-second",
                CONCERT_ID,
                concertTimeId,
                "d".repeat(64),
                30_000,
                BASE_TIME,
                seat.getHeldUntil()
        ));

        assertThatThrownBy(() -> checkoutSeatAssignmentRepository.saveAndFlush(
                CheckoutSeatAssignment.assign(
                        duplicate,
                        seat,
                        "d".repeat(64),
                        seat.getHeldUntil()
                )
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(checkoutSeatAssignmentRepository.count()).isEqualTo(1);
    }

    @Test
    void checkoutRequiresEverySeatToBeActivelyHeldByTheAuthenticatedUser() {
        seatHoldService.hold("another-user", CONCERT_ID, holdRequest("A1"));

        assertThatThrownBy(() -> checkoutService.prepare(
                USERNAME,
                CONCERT_ID,
                checkoutRequest("A1"),
                "checkout-key-other"
        )).isExactlyInstanceOf(CheckoutConflictException.class)
                .hasMessageContaining("본인이 임시 점유");

        assertThatThrownBy(() -> checkoutService.prepare(
                USERNAME,
                CONCERT_ID,
                checkoutRequest("A2"),
                "checkout-key-available"
        )).isExactlyInstanceOf(CheckoutConflictException.class)
                .hasMessageContaining("본인이 임시 점유");
        assertThat(checkoutRepository.count()).isZero();
    }

    @Test
    void retryAtTheExactExpiryPersistsExpiredStatusAndReturnsGone() {
        seatHoldService.hold(USERNAME, CONCERT_ID, holdRequest("A1"));
        CheckoutResponse response = checkoutService.prepare(
                USERNAME,
                CONCERT_ID,
                checkoutRequest("A1"),
                "checkout-key-expired"
        );

        clock.set(response.getExpiresAt());
        assertThatThrownBy(() -> checkoutService.prepare(
                USERNAME,
                CONCERT_ID,
                checkoutRequest("A1"),
                "checkout-key-expired"
        )).isExactlyInstanceOf(CheckoutExpiredException.class);

        entityManager.clear();
        Checkout stored = checkoutRepository.findByMerchantUid(response.getMerchantUid()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(CheckoutStatus.EXPIRED);
    }

    @Test
    void checkoutRejectsMismatchedConcertBeforePersisting() {
        seatHoldService.hold(USERNAME, CONCERT_ID, holdRequest("A1"));

        assertThatThrownBy(() -> checkoutService.prepare(
                USERNAME,
                "ANOTHER-CONCERT",
                checkoutRequest("A1"),
                "checkout-key-mismatch"
        )).isExactlyInstanceOf(InvalidCheckoutRequestException.class)
                .hasMessageContaining("공연과 회차");
        assertThat(checkoutRepository.count()).isZero();
    }

    private SeatHoldRequest holdRequest(String... seatNumbers) {
        SeatHoldRequest request = new SeatHoldRequest();
        request.setConcertTimeId(concertTimeId);
        request.setSeatNumberList(List.of(seatNumbers));
        return request;
    }

    private CheckoutRequest checkoutRequest(String... seatNumbers) {
        CheckoutRequest request = new CheckoutRequest();
        request.setConcertTimeId(concertTimeId);
        request.setSeatNumberList(List.of(seatNumbers));
        return request;
    }

    private CheckoutAttempt checkoutAttempt(
            CountDownLatch start,
            CheckoutRequest request,
            String idempotencyKey
    ) throws InterruptedException {
        start.await(5, TimeUnit.SECONDS);
        try {
            CheckoutResponse response = checkoutService.prepare(
                    USERNAME,
                    CONCERT_ID,
                    request,
                    idempotencyKey
            );
            return new CheckoutAttempt(true, request.getSeatNumberList().size(), response, null);
        } catch (RuntimeException exception) {
            return new CheckoutAttempt(false, request.getSeatNumberList().size(), null, exception.getClass());
        }
    }

    private Long createFixture() {
        Concert concert = new Concert();
        concert.setConcertId(CONCERT_ID);
        concert.setConcertName("가상 Checkout 공연");
        concert.setStartDate(LocalDate.of(2030, 1, 1));
        concert.setEndDate(LocalDate.of(2030, 1, 31));
        concertRepository.saveAndFlush(concert);

        ConcertTime concertTime = new ConcertTime();
        concertTime.setConcert(concert);
        concertTime.setDate(LocalDate.of(2030, 1, 10));
        concertTime.setDayOfWeek("THURSDAY");
        concertTime.setStartTime(LocalTime.of(19, 0));
        concertTime.setSeatAmount(2);
        concertTime = concertTimeRepository.saveAndFlush(concertTime);

        for (String seatNumber : List.of("A1", "A2")) {
            Seat seat = new Seat();
            seat.setSeatNumber(seatNumber);
            seat.setReserved(false);
            seat.setConcertTime(concertTime);
            seatRepository.saveAndFlush(seat);
        }
        entityManager.clear();
        return concertTime.getId();
    }

    @TestConfiguration
    static class ClockConfiguration {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(BASE_TIME.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        }
    }

    static class MutableClock extends Clock {

        private final AtomicReference<Instant> instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = new AtomicReference<>(instant);
            this.zone = zone;
        }

        void set(LocalDateTime dateTime) {
            instant.set(dateTime.atZone(zone).toInstant());
        }

        void advanceSeconds(long seconds) {
            instant.updateAndGet(current -> current.plusSeconds(seconds));
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant(), zone);
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }

    private record CheckoutAttempt(
            boolean successful,
            int seatCount,
            CheckoutResponse response,
            Class<? extends RuntimeException> failureType
    ) {
    }
}
