package com.onticket.concert.service;

import com.onticket.concert.domain.Booking;
import com.onticket.concert.domain.Checkout;
import com.onticket.concert.domain.CheckoutRequestKey;
import com.onticket.concert.domain.CheckoutStatus;
import com.onticket.concert.domain.Concert;
import com.onticket.concert.domain.ConcertTime;
import com.onticket.concert.domain.Payment;
import com.onticket.concert.domain.PaymentStatus;
import com.onticket.concert.domain.Seat;
import com.onticket.concert.dto.CheckoutRequest;
import com.onticket.concert.dto.CheckoutResponse;
import com.onticket.concert.dto.SeatHoldRequest;
import com.onticket.concert.dto.VerifiedReservRequest;
import com.onticket.concert.repository.BookingRepository;
import com.onticket.concert.repository.CheckoutRepository;
import com.onticket.concert.repository.CheckoutRequestKeyRepository;
import com.onticket.concert.repository.ConcertRepository;
import com.onticket.concert.repository.ConcertTimeRepository;
import com.onticket.concert.repository.PaymentRepository;
import com.onticket.concert.repository.ReservationRepository;
import com.onticket.concert.repository.SeatRepository;
import com.onticket.user.jwt.JwtUtil;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.show-sql=false",
        "onticket.ticket.virtual-seat-unit-price=30000",
        "onticket.ticket.seat-hold-duration=PT5M"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        SeatReservationService.class,
        SeatHoldService.class,
        CheckoutService.class,
        CheckoutPreparationTransactionService.class,
        CheckoutExpirationService.class,
        CheckoutVerifiedReservationService.class,
        VerifiedReservationTransactionService.class,
        VirtualTicketPricePolicy.class,
        CheckoutVerifiedReservationIntegrationTest.ClockConfiguration.class,
        CheckoutVerifiedReservationIntegrationTest.CheckoutAliasLockBarrierConfiguration.class
})
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CheckoutVerifiedReservationIntegrationTest {

    private static final String CONCERT_ID = "CHECKOUT-RESERVATION-CONCERT";
    private static final String USERNAME = "checkout-reservation-user";
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2030, 1, 1, 12, 0);
    private static final AtomicReference<CheckoutAliasLockBarrier> CHECKOUT_ALIAS_LOCK_BARRIER =
            new AtomicReference<>();

    @Container
    static final MariaDBContainer<?> MARIA_DB = new MariaDBContainer<>("mariadb:10.11.8")
            .withDatabaseName("onticket_checkout_reservation")
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
    private CheckoutVerifiedReservationService reservationService;

    @Autowired
    private SeatHoldService seatHoldService;

    @Autowired
    private CheckoutRepository checkoutRepository;

    @Autowired
    private CheckoutRequestKeyRepository checkoutRequestKeyRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private ReservationRepository reservationRepository;

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

    @MockBean
    private PaymentVerificationPort paymentVerificationPort;

    @MockBean
    private JwtUtil jwtUtil;

    private Long concertTimeId;

    @BeforeEach
    void setUp() {
        CHECKOUT_ALIAS_LOCK_BARRIER.set(null);
        paymentRepository.deleteAllInBatch();
        reservationRepository.deleteAllInBatch();
        checkoutRequestKeyRepository.deleteAllInBatch();
        checkoutRepository.deleteAllInBatch();
        bookingRepository.deleteAllInBatch();
        seatRepository.deleteAllInBatch();
        concertTimeRepository.deleteAllInBatch();
        concertRepository.deleteAllInBatch();
        entityManager.clear();
        clock.set(BASE_TIME);
        concertTimeId = createFixture();
    }

    @Test
    void approvedPaymentMatchingCheckoutConfirmsReservationOnce() throws Exception {
        CheckoutResponse checkout = prepareCheckout("checkout-prepare-1", "A1", "A2");
        CheckoutResponse reusedCheckout = checkoutService.prepare(
                USERNAME,
                CONCERT_ID,
                checkoutRequest("A2", "A1"),
                "checkout-reused-key"
        );
        assertThat(reusedCheckout.getMerchantUid()).isEqualTo(checkout.getMerchantUid());
        VerifiedReservRequest request = verifiedRequest(
                checkout.getMerchantUid(),
                "payment-success",
                "A2",
                "A1"
        );
        when(paymentVerificationPort.verify("payment-success"))
                .thenReturn(approved("payment-success", checkout.getMerchantUid(), 60_000));

        LocalDateTime first = reservationService.reserve(
                USERNAME,
                CONCERT_ID,
                request,
                "reservation-key-1"
        );
        LocalDateTime retry = reservationService.reserve(
                USERNAME,
                CONCERT_ID,
                request,
                "reservation-key-1"
        );

        assertThat(retry).isEqualTo(first);
        CheckoutResponse confirmedRetry = checkoutService.prepare(
                USERNAME,
                CONCERT_ID,
                checkoutRequest("A1", "A2"),
                "checkout-reused-key"
        );
        assertThat(confirmedRetry.getMerchantUid()).isEqualTo(checkout.getMerchantUid());
        assertThat(confirmedRetry.getStatus()).isEqualTo(CheckoutStatus.RESERVATION_CONFIRMED);
        assertThatThrownBy(() -> checkoutService.prepare(
                USERNAME,
                CONCERT_ID,
                checkoutRequest("A1"),
                "checkout-reused-key"
        )).isExactlyInstanceOf(IdempotencyKeyConflictException.class);
        verify(paymentVerificationPort, times(1)).verify("payment-success");
        assertConfirmedSnapshot(checkout.getMerchantUid(), 0, 2);
    }

    @Test
    void amountOrProviderMerchantMismatchLeavesCheckoutAndInventoryUnchanged() {
        CheckoutResponse checkout = prepareCheckout("checkout-prepare-2", "A1");
        VerifiedReservRequest amountMismatch = verifiedRequest(
                checkout.getMerchantUid(),
                "payment-wrong-amount",
                "A1"
        );
        when(paymentVerificationPort.verify("payment-wrong-amount"))
                .thenReturn(approved("payment-wrong-amount", checkout.getMerchantUid(), 100));

        assertThatThrownBy(() -> reservationService.reserve(
                USERNAME,
                CONCERT_ID,
                amountMismatch,
                "reservation-key-amount"
        )).isExactlyInstanceOf(InvalidPaymentException.class)
                .hasMessageContaining("금액");

        VerifiedReservRequest merchantMismatch = verifiedRequest(
                checkout.getMerchantUid(),
                "payment-wrong-merchant",
                "A1"
        );
        when(paymentVerificationPort.verify("payment-wrong-merchant"))
                .thenReturn(approved("payment-wrong-merchant", "another-merchant", 30_000));

        assertThatThrownBy(() -> reservationService.reserve(
                USERNAME,
                CONCERT_ID,
                merchantMismatch,
                "reservation-key-merchant"
        )).isExactlyInstanceOf(InvalidPaymentException.class)
                .hasMessageContaining("고객사 주문 식별자");

        assertReadySnapshot(checkout.getMerchantUid());
    }

    @Test
    void otherUserOrDifferentPayloadIsRejectedBeforePaymentVerification() {
        CheckoutResponse checkout = prepareCheckout("checkout-prepare-3", "A1");

        assertThatThrownBy(() -> reservationService.reserve(
                "another-user",
                CONCERT_ID,
                verifiedRequest(checkout.getMerchantUid(), "payment-owner", "A1"),
                "reservation-key-owner"
        )).isExactlyInstanceOf(CheckoutConflictException.class)
                .hasMessageContaining("다른 사용자");

        assertThatThrownBy(() -> reservationService.reserve(
                USERNAME,
                CONCERT_ID,
                verifiedRequest(checkout.getMerchantUid(), "payment-payload", "A2"),
                "reservation-key-payload"
        )).isExactlyInstanceOf(CheckoutConflictException.class)
                .hasMessageContaining("payload");

        verifyNoInteractions(paymentVerificationPort);
        assertReadySnapshot(checkout.getMerchantUid());
    }

    @Test
    void expiredCheckoutIsPersistedAndRejectedBeforePaymentVerification() {
        CheckoutResponse checkout = prepareCheckout("checkout-prepare-4", "A1");
        clock.set(checkout.getExpiresAt());

        assertThatThrownBy(() -> reservationService.reserve(
                USERNAME,
                CONCERT_ID,
                verifiedRequest(checkout.getMerchantUid(), "payment-expired", "A1"),
                "reservation-key-expired"
        )).isExactlyInstanceOf(CheckoutExpiredException.class);

        verifyNoInteractions(paymentVerificationPort);
        entityManager.clear();
        assertThat(checkoutRepository.findByMerchantUid(checkout.getMerchantUid()).orElseThrow().getStatus())
                .isEqualTo(CheckoutStatus.EXPIRED);
        assertEmptyReservationSnapshot(2);
    }

    @Test
    void concurrentDifferentKeysConsumeOneCheckoutOnlyOnce() throws Exception {
        CheckoutResponse checkout = prepareCheckout("checkout-prepare-5", "A1");
        VerifiedReservRequest request = verifiedRequest(
                checkout.getMerchantUid(),
                "payment-concurrent",
                "A1"
        );
        when(paymentVerificationPort.verify("payment-concurrent"))
                .thenReturn(approved("payment-concurrent", checkout.getMerchantUid(), 30_000));

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<AttemptResult> first = executor.submit(() -> attempt(
                    request,
                    "reservation-concurrent-1",
                    start
            ));
            Future<AttemptResult> second = executor.submit(() -> attempt(
                    request,
                    "reservation-concurrent-2",
                    start
            ));
            start.countDown();

            List<AttemptResult> results = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS)
            );
            assertThat(results).filteredOn(AttemptResult::success).hasSize(1);
            assertThat(results).filteredOn(result -> !result.success()).singleElement()
                    .extracting(AttemptResult::exceptionType)
                    .isEqualTo(CheckoutConflictException.class.getSimpleName());
        }

        assertConfirmedSnapshot(checkout.getMerchantUid(), 1, 1);
    }

    @RepeatedTest(3)
    void aliasKeyBindingAfterSeatTransactionDoesNotDeadlockWithReservationConfirmation() throws Exception {
        CheckoutResponse checkout = prepareCheckout("checkout-lock-order-first", "A1");
        VerifiedReservRequest reservationRequest = verifiedRequest(
                checkout.getMerchantUid(),
                "payment-lock-order",
                "A1"
        );
        when(paymentVerificationPort.verify("payment-lock-order"))
                .thenReturn(approved("payment-lock-order", checkout.getMerchantUid(), 30_000));

        CheckoutAliasLockBarrier barrier = new CheckoutAliasLockBarrier("checkout-lock-order-alias");
        CHECKOUT_ALIAS_LOCK_BARRIER.set(barrier);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<LocalDateTime> reservation = executor.submit(() -> reservationService.reserve(
                    USERNAME,
                    CONCERT_ID,
                    reservationRequest,
                    "reservation-lock-order"
            ));
            assertThat(barrier.awaitCheckoutLock()).isTrue();

            Future<CheckoutResponse> alias = executor.submit(() -> checkoutService.prepare(
                    USERNAME,
                    CONCERT_ID,
                    checkoutRequest("A1"),
                    "checkout-lock-order-alias"
            ));

            assertThat(reservation.get(10, TimeUnit.SECONDS)).isNotNull();
            CheckoutResponse aliasResult = alias.get(10, TimeUnit.SECONDS);
            assertThat(aliasResult.getMerchantUid()).isEqualTo(checkout.getMerchantUid());
            assertThat(aliasResult.getStatus()).isEqualTo(CheckoutStatus.RESERVATION_CONFIRMED);
        } finally {
            CHECKOUT_ALIAS_LOCK_BARRIER.compareAndSet(barrier, null);
        }

        assertThat(checkoutRepository.count()).isEqualTo(1);
        assertThat(checkoutRequestKeyRepository.count()).isEqualTo(2);
        assertConfirmedSnapshot(checkout.getMerchantUid(), 1, 1);
    }

    private AttemptResult attempt(
            VerifiedReservRequest request,
            String idempotencyKey,
            CountDownLatch start
    ) {
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                return AttemptResult.failure("Timeout", "start latch timeout");
            }
            reservationService.reserve(USERNAME, CONCERT_ID, request, idempotencyKey);
            return AttemptResult.succeeded();
        } catch (Exception exception) {
            return AttemptResult.failure(exception.getClass().getSimpleName(), exception.getMessage());
        }
    }

    private CheckoutResponse prepareCheckout(
            String idempotencyKey,
            String... seatNumbers
    ) {
        SeatHoldRequest holdRequest = new SeatHoldRequest();
        holdRequest.setConcertTimeId(concertTimeId);
        holdRequest.setSeatNumberList(List.of(seatNumbers));
        seatHoldService.hold(USERNAME, CONCERT_ID, holdRequest);

        CheckoutRequest checkoutRequest = new CheckoutRequest();
        checkoutRequest.setConcertTimeId(concertTimeId);
        checkoutRequest.setSeatNumberList(List.of(seatNumbers));
        return checkoutService.prepare(USERNAME, CONCERT_ID, checkoutRequest, idempotencyKey);
    }

    private CheckoutRequest checkoutRequest(String... seatNumbers) {
        CheckoutRequest request = new CheckoutRequest();
        request.setConcertTimeId(concertTimeId);
        request.setSeatNumberList(List.of(seatNumbers));
        return request;
    }

    private VerifiedReservRequest verifiedRequest(
            String merchantUid,
            String paymentId,
            String... seatNumbers
    ) {
        VerifiedReservRequest request = new VerifiedReservRequest();
        request.setConcertTimeId(concertTimeId);
        request.setSeatNumberList(List.of(seatNumbers));
        request.setPaymentId(paymentId);
        request.setMerchantUid(merchantUid);
        return request;
    }

    private PaymentApproval approved(String paymentId, String merchantUid, long amount) {
        return new PaymentApproval(
                paymentId,
                merchantUid,
                null,
                amount,
                true,
                BASE_TIME.plusMinutes(1)
        );
    }

    private void assertConfirmedSnapshot(String merchantUid, int remaining, long reservations) {
        entityManager.clear();
        Checkout checkout = checkoutRepository.findByMerchantUid(merchantUid).orElseThrow();
        assertThat(checkout.getStatus()).isEqualTo(CheckoutStatus.RESERVATION_CONFIRMED);
        assertThat(checkout.getBooking()).isNotNull();
        assertThat(bookingRepository.count()).isEqualTo(1);
        assertThat(paymentRepository.count()).isEqualTo(1);
        Payment payment = paymentRepository.findAll().getFirst();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.RESERVATION_CONFIRMED);
        assertThat(reservationRepository.count()).isEqualTo(reservations);
        assertThat(seatRepository.countByConcertTimeIdAndReservedTrue(concertTimeId))
                .isEqualTo(reservations);
        assertThat(concertTimeRepository.findById(concertTimeId).orElseThrow().getSeatAmount())
                .isEqualTo(remaining);
    }

    private void assertReadySnapshot(String merchantUid) {
        entityManager.clear();
        assertThat(checkoutRepository.findByMerchantUid(merchantUid).orElseThrow().getStatus())
                .isEqualTo(CheckoutStatus.READY);
        assertEmptyReservationSnapshot(2);
    }

    private void assertEmptyReservationSnapshot(int remaining) {
        assertThat(bookingRepository.count()).isZero();
        assertThat(paymentRepository.count()).isZero();
        assertThat(reservationRepository.count()).isZero();
        assertThat(seatRepository.countByConcertTimeIdAndReservedTrue(concertTimeId)).isZero();
        assertThat(concertTimeRepository.findById(concertTimeId).orElseThrow().getSeatAmount())
                .isEqualTo(remaining);
    }

    private Long createFixture() {
        Concert concert = new Concert();
        concert.setConcertId(CONCERT_ID);
        concert.setConcertName("가상 Checkout 예약 공연");
        concert.setPosterUrl("https://example.invalid/checkout-poster.jpg");
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

    private record AttemptResult(boolean success, String exceptionType, String message) {
        static AttemptResult succeeded() {
            return new AttemptResult(true, null, null);
        }

        static AttemptResult failure(String exceptionType, String message) {
            return new AttemptResult(false, exceptionType, message);
        }
    }

    private static final class CheckoutAliasLockBarrier {

        private final String aliasKey;
        private final CountDownLatch checkoutLockAcquired = new CountDownLatch(1);
        private final CountDownLatch aliasKeySaveAttempted = new CountDownLatch(1);

        private CheckoutAliasLockBarrier(String aliasKey) {
            this.aliasKey = aliasKey;
        }

        boolean targets(CheckoutRequestKey requestKey) {
            return aliasKey.equals(requestKey.getIdempotencyKey());
        }

        void checkoutLockAcquired() throws InterruptedException {
            checkoutLockAcquired.countDown();
            if (!aliasKeySaveAttempted.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("후속 Checkout 키 저장 시도를 기다리지 못했습니다.");
            }
        }

        boolean awaitCheckoutLock() throws InterruptedException {
            return checkoutLockAcquired.await(5, TimeUnit.SECONDS);
        }

        void aliasKeySaveAttempted() {
            aliasKeySaveAttempted.countDown();
        }
    }

    @TestConfiguration
    static class ClockConfiguration {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(BASE_TIME.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        }
    }

    @TestConfiguration
    static class CheckoutAliasLockBarrierConfiguration {

        @Bean
        static BeanPostProcessor checkoutAliasLockBarrierBeanPostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof CheckoutRepository checkoutRepository) {
                        return Proxy.newProxyInstance(
                                CheckoutRepository.class.getClassLoader(),
                                new Class<?>[]{CheckoutRepository.class},
                                (proxy, method, args) -> {
                                    Object result = invoke(checkoutRepository, method, args);
                                    CheckoutAliasLockBarrier barrier = CHECKOUT_ALIAS_LOCK_BARRIER.get();
                                    if (barrier != null && method.getName().equals("findByMerchantUidWithLock")) {
                                        barrier.checkoutLockAcquired();
                                    }
                                    return result;
                                }
                        );
                    }
                    if (bean instanceof CheckoutRequestKeyRepository checkoutRequestKeyRepository) {
                        return Proxy.newProxyInstance(
                                CheckoutRequestKeyRepository.class.getClassLoader(),
                                new Class<?>[]{CheckoutRequestKeyRepository.class},
                                (proxy, method, args) -> {
                                    CheckoutAliasLockBarrier barrier = CHECKOUT_ALIAS_LOCK_BARRIER.get();
                                    if (barrier != null
                                            && method.getName().equals("saveAndFlush")
                                            && args != null
                                            && args.length == 1
                                            && args[0] instanceof CheckoutRequestKey requestKey
                                            && barrier.targets(requestKey)) {
                                        barrier.aliasKeySaveAttempted();
                                    }
                                    return invoke(checkoutRequestKeyRepository, method, args);
                                }
                        );
                    }
                    return bean;
                }

                private Object invoke(Object repository, java.lang.reflect.Method method, Object[] args)
                        throws Throwable {
                    try {
                        return method.invoke(repository, args);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                }
            };
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
}
