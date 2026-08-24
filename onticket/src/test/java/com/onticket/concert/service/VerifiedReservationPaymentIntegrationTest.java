package com.onticket.concert.service;

import com.onticket.concert.domain.Booking;
import com.onticket.concert.domain.Concert;
import com.onticket.concert.domain.ConcertDetail;
import com.onticket.concert.domain.ConcertTime;
import com.onticket.concert.domain.Payment;
import com.onticket.concert.domain.PaymentStatus;
import com.onticket.concert.domain.Seat;
import com.onticket.concert.dto.VerifiedReservRequest;
import com.onticket.concert.repository.BookingRepository;
import com.onticket.concert.repository.ConcertDetailRepository;
import com.onticket.concert.repository.ConcertRepository;
import com.onticket.concert.repository.ConcertTimeRepository;
import com.onticket.concert.repository.PaymentRepository;
import com.onticket.concert.repository.ReservationRepository;
import com.onticket.concert.repository.SeatRepository;
import com.onticket.user.jwt.JwtUtil;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.show-sql=false",
        "onticket.ticket.virtual-seat-unit-price=30000"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        SeatReservationService.class,
        VerifiedReservationService.class,
        VerifiedReservationTransactionService.class,
        VirtualTicketPricePolicy.class,
        VerifiedReservationPaymentIntegrationTest.BookingRepositoryBarrierConfiguration.class
})
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class VerifiedReservationPaymentIntegrationTest {

    private static final int TOTAL_SEATS = 24;
    private static final String CONCERT_ID = "PAYMENT-CONCERT";
    private static final String USERNAME = "payment-user";
    private static final LocalDateTime APPROVED_AT = LocalDateTime.of(2030, 1, 1, 12, 0);
    private static final AtomicReference<CyclicBarrier> BOOKING_LOOKUP_BARRIER = new AtomicReference<>();
    private static final AtomicInteger BOOKING_LOOKUP_COUNT = new AtomicInteger();

    @Container
    static final MariaDBContainer<?> MARIA_DB = new MariaDBContainer<>("mariadb:10.11.8")
            .withDatabaseName("onticket_payment")
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
    private VerifiedReservationService verifiedReservationService;

    @Autowired
    private ConcertRepository concertRepository;

    @Autowired
    private ConcertDetailRepository concertDetailRepository;

    @Autowired
    private ConcertTimeRepository concertTimeRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private EntityManager entityManager;

    @MockBean
    private PaymentVerificationPort paymentVerificationPort;

    @MockBean
    private JwtUtil jwtUtil;

    private Long concertTimeId;

    @BeforeEach
    void setUp() {
        deleteFixture();
        BOOKING_LOOKUP_BARRIER.set(null);
        BOOKING_LOOKUP_COUNT.set(0);
        concertTimeId = createFixture();
    }

    @AfterEach
    void tearDown() {
        deleteFixture();
    }

    @Test
    void approvedPaymentWithServerCalculatedAmountConfirmsReservation() throws Exception {
        String paymentId = "payment-success";
        when(paymentVerificationPort.verify(paymentId))
                .thenReturn(approved(paymentId, USERNAME, 60_000));

        LocalDateTime result = verifiedReservationService.reserve(
                USERNAME,
                CONCERT_ID,
                request(paymentId, "A1", "A2"),
                "verified-success-key"
        );

        assertThat(result).isNotNull();
        assertThat(paymentRepository.findAll()).singleElement().satisfies(payment -> {
            assertThat(payment.getProviderPaymentId()).isEqualTo(paymentId);
            assertThat(payment.getUsername()).isEqualTo(USERNAME);
            assertThat(payment.getApprovedAmount()).isEqualTo(60_000);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.RESERVATION_CONFIRMED);
        });
        assertInventory(22, 2, 2);
        assertThat(bookingRepository.count()).isEqualTo(1);
    }

    @Test
    void declinedPaymentIsRejectedWithoutDatabaseChanges() {
        String paymentId = "payment-declined";
        when(paymentVerificationPort.verify(paymentId))
                .thenReturn(new PaymentApproval(paymentId, USERNAME, 30_000, false, APPROVED_AT));

        assertRejectedWithoutChanges(
                request(paymentId, "A1"),
                "declined-key",
                "승인된 결제가 아닙니다."
        );
    }

    @Test
    void amountMismatchIsRejectedWithoutDatabaseChanges() {
        String paymentId = "payment-wrong-amount";
        when(paymentVerificationPort.verify(paymentId))
                .thenReturn(approved(paymentId, USERNAME, 100));

        assertRejectedWithoutChanges(
                request(paymentId, "A1"),
                "wrong-amount-key",
                "서버 주문 금액과 승인 금액이 일치하지 않습니다."
        );
    }

    @Test
    void paymentOwnerMismatchIsRejectedWithoutDatabaseChanges() {
        String paymentId = "payment-wrong-owner";
        when(paymentVerificationPort.verify(paymentId))
                .thenReturn(approved(paymentId, "another-user", 30_000));

        assertRejectedWithoutChanges(
                request(paymentId, "A1"),
                "wrong-owner-key",
                "결제자와 예약자가 일치하지 않습니다."
        );
    }

    @Test
    void paymentIdentifierMismatchIsRejectedWithoutDatabaseChanges() {
        String requestedPaymentId = "payment-requested";
        when(paymentVerificationPort.verify(requestedPaymentId))
                .thenReturn(approved("payment-returned", USERNAME, 30_000));

        assertRejectedWithoutChanges(
                request(requestedPaymentId, "A1"),
                "wrong-payment-id-key",
                "결제 식별자가 일치하지 않습니다."
        );
    }

    @Test
    void successfulReplayReturnsOriginalResultWithoutReverification() throws Exception {
        String paymentId = "payment-replay";
        when(paymentVerificationPort.verify(paymentId))
                .thenReturn(approved(paymentId, USERNAME, 30_000));
        VerifiedReservRequest request = request(paymentId, "A1");

        LocalDateTime first = verifiedReservationService.reserve(
                USERNAME, CONCERT_ID, request, "replay-key");
        LocalDateTime replay = verifiedReservationService.reserve(
                USERNAME, CONCERT_ID, request, "replay-key");

        assertThat(replay).isEqualTo(first);
        verify(paymentVerificationPort, times(1)).verify(paymentId);
        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(bookingRepository.count()).isEqualTo(1);
        assertInventory(23, 1, 1);
    }

    @Test
    void paymentCannotBeReusedForAnotherReservation() throws Exception {
        String paymentId = "payment-reused";
        when(paymentVerificationPort.verify(paymentId))
                .thenReturn(approved(paymentId, USERNAME, 30_000));

        verifiedReservationService.reserve(
                USERNAME, CONCERT_ID, request(paymentId, "A1"), "first-key");

        assertThatThrownBy(() -> verifiedReservationService.reserve(
                USERNAME, CONCERT_ID, request(paymentId, "A2"), "second-key"))
                .isExactlyInstanceOf(PaymentAlreadyUsedException.class);

        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(bookingRepository.count()).isEqualTo(1);
        assertInventory(23, 1, 1);
    }

    @RepeatedTest(3)
    void concurrentSameIdempotencyKeyReturnsOriginalVerifiedResult() throws Exception {
        String paymentId = "payment-same-key";
        String idempotencyKey = "same-key";
        when(paymentVerificationPort.verify(paymentId))
                .thenReturn(approved(paymentId, USERNAME, 30_000));
        BOOKING_LOOKUP_BARRIER.set(new CyclicBarrier(2));

        try {
            List<IdempotencyAttemptResult> results = runSameKeyRequestsConcurrently(
                    idempotencyKey,
                    request(paymentId, "A1"),
                    request(paymentId, "A1")
            );

            assertThat(results).allMatch(IdempotencyAttemptResult::success);
            assertThat(results).extracting(IdempotencyAttemptResult::createdAt)
                    .containsOnly(results.getFirst().createdAt());
            assertThat(BOOKING_LOOKUP_COUNT).hasValue(3);
            assertThat(paymentRepository.count()).isEqualTo(1);
            assertThat(bookingRepository.count()).isEqualTo(1);
            assertInventory(23, 1, 1);
        } finally {
            BOOKING_LOOKUP_BARRIER.set(null);
        }
    }

    @Test
    void sameIdempotencyKeyWithDifferentVerifiedPayloadIsRejected() throws Exception {
        String idempotencyKey = "verified-payload-conflict";
        when(paymentVerificationPort.verify("payment-first"))
                .thenReturn(approved("payment-first", USERNAME, 30_000));

        verifiedReservationService.reserve(
                USERNAME,
                CONCERT_ID,
                request("payment-first", "A1"),
                idempotencyKey
        );

        assertThatThrownBy(() -> verifiedReservationService.reserve(
                USERNAME,
                CONCERT_ID,
                request("payment-second", "A2"),
                idempotencyKey
        )).isExactlyInstanceOf(IdempotencyKeyConflictException.class)
                .hasMessage("동일한 멱등 키가 다른 예약 요청에 사용되었습니다.");

        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(bookingRepository.count()).isEqualTo(1);
        assertInventory(23, 1, 1);
    }

    @RepeatedTest(3)
    void concurrentSameIdempotencyKeyWithDifferentPayloadAllowsOneAndRejectsConflict() throws Exception {
        String idempotencyKey = "concurrent-payload-conflict";
        when(paymentVerificationPort.verify("payment-conflict-1"))
                .thenReturn(approved("payment-conflict-1", USERNAME, 30_000));
        when(paymentVerificationPort.verify("payment-conflict-2"))
                .thenReturn(approved("payment-conflict-2", USERNAME, 30_000));
        BOOKING_LOOKUP_BARRIER.set(new CyclicBarrier(2));

        try {
            List<IdempotencyAttemptResult> results = runSameKeyRequestsConcurrently(
                    idempotencyKey,
                    request("payment-conflict-1", "A1"),
                    request("payment-conflict-2", "A2")
            );

            assertThat(results).filteredOn(IdempotencyAttemptResult::success).hasSize(1);
            assertThat(results).filteredOn(result -> !result.success()).singleElement()
                    .extracting(IdempotencyAttemptResult::exceptionType)
                    .isEqualTo(IdempotencyKeyConflictException.class.getSimpleName());
            assertThat(BOOKING_LOOKUP_COUNT).hasValue(3);
            assertThat(paymentRepository.count()).isEqualTo(1);
            assertThat(bookingRepository.count()).isEqualTo(1);
            assertInventory(23, 1, 1);
        } finally {
            BOOKING_LOOKUP_BARRIER.set(null);
        }
    }

    @RepeatedTest(3)
    void concurrentPaymentReuseAllowsOnlyOneReservation() throws Exception {
        String paymentId = "payment-concurrent";
        when(paymentVerificationPort.verify(paymentId))
                .thenReturn(approved(paymentId, USERNAME, 30_000));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            List<Future<AttemptResult>> futures = new ArrayList<>();
            futures.add(executor.submit(() -> attemptReservation(
                    paymentId, "A1", "concurrent-key-1", ready, start)));
            futures.add(executor.submit(() -> attemptReservation(
                    paymentId, "A2", "concurrent-key-2", ready, start)));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<AttemptResult> results = new ArrayList<>();
            for (Future<AttemptResult> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }

            assertThat(results).filteredOn(AttemptResult::success).hasSize(1);
            assertThat(results).filteredOn(result -> !result.success()).singleElement()
                    .extracting(AttemptResult::exceptionType)
                    .isEqualTo(PaymentAlreadyUsedException.class.getSimpleName());
            assertThat(paymentRepository.count()).isEqualTo(1);
            assertThat(bookingRepository.count()).isEqualTo(1);
            assertInventory(23, 1, 1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void reservationFailureRollsBackPaymentConsumptionAndBooking() {
        String paymentId = "payment-reservation-failure";
        when(paymentVerificationPort.verify(paymentId))
                .thenReturn(approved(paymentId, USERNAME, 30_000));

        assertThatThrownBy(() -> verifiedReservationService.reserve(
                USERNAME,
                CONCERT_ID,
                request(paymentId, "UNKNOWN"),
                "failure-key"
        )).isInstanceOf(Exception.class)
                .hasMessage("존재하지 않는 좌석입니다.");

        assertThat(paymentRepository.count()).isZero();
        assertThat(bookingRepository.count()).isZero();
        assertInventory(24, 0, 0);
    }

    @Test
    void lateInventoryFailureRollsBackPaymentBookingReservationsAndSeatChanges() {
        ConcertTime concertTime = concertTimeRepository.findById(concertTimeId).orElseThrow();
        concertTime.setSeatAmount(1);
        concertTimeRepository.saveAndFlush(concertTime);
        entityManager.clear();
        String paymentId = "payment-late-failure";
        when(paymentVerificationPort.verify(paymentId))
                .thenReturn(approved(paymentId, USERNAME, 60_000));

        assertThatThrownBy(() -> verifiedReservationService.reserve(
                USERNAME,
                CONCERT_ID,
                request(paymentId, "A1", "A2"),
                "late-failure-key"
        )).isExactlyInstanceOf(Exception.class)
                .hasMessage("잔여 좌석이 부족합니다.");

        assertThat(paymentRepository.count()).isZero();
        assertThat(bookingRepository.count()).isZero();
        assertInventory(1, 0, 0);
    }

    private List<IdempotencyAttemptResult> runSameKeyRequestsConcurrently(
            String idempotencyKey,
            VerifiedReservRequest firstRequest,
            VerifiedReservRequest secondRequest
    ) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<VerifiedReservRequest> requests = List.of(firstRequest, secondRequest);

        try {
            List<Future<IdempotencyAttemptResult>> futures = new ArrayList<>();
            for (VerifiedReservRequest request : requests) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        return IdempotencyAttemptResult.failure("Timeout", "start latch timeout");
                    }
                    try {
                        LocalDateTime createdAt = verifiedReservationService.reserve(
                                USERNAME,
                                CONCERT_ID,
                                request,
                                idempotencyKey
                        );
                        return IdempotencyAttemptResult.succeeded(createdAt);
                    } catch (Exception exception) {
                        return IdempotencyAttemptResult.failure(
                                exception.getClass().getSimpleName(),
                                exception.getMessage()
                        );
                    }
                }));
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<IdempotencyAttemptResult> results = new ArrayList<>();
            for (Future<IdempotencyAttemptResult> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private AttemptResult attemptReservation(
            String paymentId,
            String seatNumber,
            String idempotencyKey,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                return AttemptResult.failure("Timeout", "start latch timeout");
            }
            verifiedReservationService.reserve(
                    USERNAME,
                    CONCERT_ID,
                    request(paymentId, seatNumber),
                    idempotencyKey
            );
            return AttemptResult.succeeded();
        } catch (Exception exception) {
            return AttemptResult.failure(exception.getClass().getSimpleName(), exception.getMessage());
        }
    }

    private void assertRejectedWithoutChanges(
            VerifiedReservRequest request,
            String idempotencyKey,
            String message
    ) {
        assertThatThrownBy(() -> verifiedReservationService.reserve(
                USERNAME, CONCERT_ID, request, idempotencyKey))
                .isExactlyInstanceOf(InvalidPaymentException.class)
                .hasMessage(message);
        assertThat(paymentRepository.count()).isZero();
        assertThat(bookingRepository.count()).isZero();
        assertInventory(24, 0, 0);
    }

    private PaymentApproval approved(String paymentId, String username, long amount) {
        return new PaymentApproval(paymentId, username, amount, true, APPROVED_AT);
    }

    private VerifiedReservRequest request(String paymentId, String... seatNumbers) {
        VerifiedReservRequest request = new VerifiedReservRequest();
        request.setPaymentId(paymentId);
        request.setConcertTimeId(concertTimeId);
        request.setSeatNumberList(List.of(seatNumbers));
        return request;
    }

    private Long createFixture() {
        Concert concert = new Concert();
        concert.setConcertId(CONCERT_ID);
        concert.setConcertName("가상 결제 검증 공연");
        concert.setPosterUrl("https://example.invalid/payment-poster.jpg");
        concert.setStartDate(LocalDate.of(2030, 1, 1));
        concert.setEndDate(LocalDate.of(2030, 1, 31));

        ConcertDetail concertDetail = new ConcertDetail();
        concertDetail.setConcertId(CONCERT_ID);
        concertDetail.setConcert(concert);
        concert.setConcertDetail(concertDetail);
        concertRepository.saveAndFlush(concert);

        ConcertTime concertTime = new ConcertTime();
        concertTime.setConcert(concert);
        concertTime.setDate(LocalDate.of(2030, 1, 10));
        concertTime.setDayOfWeek("THURSDAY");
        concertTime.setStartTime(LocalTime.of(19, 0));
        concertTime.setSeatAmount(TOTAL_SEATS);
        concertTime = concertTimeRepository.saveAndFlush(concertTime);

        List<Seat> seats = new ArrayList<>();
        for (char row = 'A'; row <= 'C'; row++) {
            for (int number = 1; number <= 8; number++) {
                Seat seat = new Seat();
                seat.setSeatNumber(row + String.valueOf(number));
                seat.setReserved(false);
                seat.setConcertTime(concertTime);
                seats.add(seat);
            }
        }
        seatRepository.saveAllAndFlush(seats);
        entityManager.clear();
        return concertTime.getId();
    }

    private void assertInventory(int remaining, long reserved, long reservations) {
        entityManager.clear();
        long reservedSeats = seatRepository.findByConcertTimeId(concertTimeId).stream()
                .filter(Seat::isReserved)
                .count();
        int remainingSeats = concertTimeRepository.findById(concertTimeId).orElseThrow().getSeatAmount();
        assertThat(remainingSeats).isEqualTo(remaining);
        assertThat(reservedSeats).isEqualTo(reserved);
        assertThat(reservationRepository.count()).isEqualTo(reservations);
    }

    private void deleteFixture() {
        paymentRepository.deleteAllInBatch();
        reservationRepository.deleteAllInBatch();
        bookingRepository.deleteAllInBatch();
        seatRepository.deleteAllInBatch();
        concertTimeRepository.deleteAllInBatch();
        concertDetailRepository.deleteAllInBatch();
        concertRepository.deleteAllInBatch();
        entityManager.clear();
    }

    private record AttemptResult(boolean success, String exceptionType, String message) {
        static AttemptResult succeeded() {
            return new AttemptResult(true, null, null);
        }

        static AttemptResult failure(String exceptionType, String message) {
            return new AttemptResult(false, exceptionType, message);
        }
    }

    private record IdempotencyAttemptResult(
            boolean success,
            LocalDateTime createdAt,
            String exceptionType,
            String message
    ) {
        static IdempotencyAttemptResult succeeded(LocalDateTime createdAt) {
            return new IdempotencyAttemptResult(true, createdAt, null, null);
        }

        static IdempotencyAttemptResult failure(String exceptionType, String message) {
            return new IdempotencyAttemptResult(false, null, exceptionType, message);
        }
    }

    @TestConfiguration
    static class BookingRepositoryBarrierConfiguration {

        @Bean
        static BeanPostProcessor bookingRepositoryBarrierBeanPostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof BookingRepository bookingRepository)) {
                        return bean;
                    }
                    return Proxy.newProxyInstance(
                            BookingRepository.class.getClassLoader(),
                            new Class<?>[]{BookingRepository.class},
                            (proxy, method, args) -> {
                                Object result;
                                try {
                                    result = method.invoke(bookingRepository, args);
                                } catch (InvocationTargetException exception) {
                                    throw exception.getCause();
                                }

                                if (method.getName().equals("findByUsernameAndIdempotencyKey")) {
                                    int lookupCount = BOOKING_LOOKUP_COUNT.incrementAndGet();
                                    CyclicBarrier barrier = BOOKING_LOOKUP_BARRIER.get();
                                    if (barrier != null && lookupCount <= 2) {
                                        try {
                                            barrier.await(5, TimeUnit.SECONDS);
                                        } catch (TimeoutException exception) {
                                            throw new IllegalStateException("멱등 키 조회 barrier 대기 시간을 초과했습니다.", exception);
                                        }
                                    }
                                }
                                return result;
                            }
                    );
                }
            };
        }
    }
}
