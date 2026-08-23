package com.onticket.concert.service;

import com.onticket.concert.domain.Concert;
import com.onticket.concert.domain.ConcertDetail;
import com.onticket.concert.domain.ConcertTime;
import com.onticket.concert.domain.Reservation;
import com.onticket.concert.domain.Seat;
import com.onticket.concert.repository.ConcertDetailRepository;
import com.onticket.concert.repository.ConcertRepository;
import com.onticket.concert.repository.ConcertTimeRepository;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.show-sql=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        SeatReservationService.class,
        CancellationConsistencyBaselineIntegrationTest.ReservationLockBarrierConfiguration.class
})
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CancellationConsistencyBaselineIntegrationTest {

    private static final int TOTAL_SEATS = 24;
    private static final String CONCERT_ID = "CANCELLATION-BASELINE-CONCERT";
    private static final String USERNAME = "cancellation-baseline-user";
    private static final AtomicReference<ReservationLockBarrier> RESERVATION_LOCK_BARRIER =
            new AtomicReference<>();

    @Container
    static final MariaDBContainer<?> MARIA_DB = new MariaDBContainer<>("mariadb:10.11.8")
            .withDatabaseName("onticket_cancellation_baseline")
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
    private SeatReservationService seatReservationService;

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
    private EntityManager entityManager;

    @MockBean
    private JwtUtil jwtUtil;

    private Long concertTimeId;
    private Long reservationId;

    @BeforeEach
    void setUp() {
        RESERVATION_LOCK_BARRIER.set(null);
        deleteFixture();
        CancellationFixture fixture = createFixture("취소신청");
        concertTimeId = fixture.concertTimeId();
        reservationId = fixture.reservationId();

        CancellationSnapshot initialSnapshot = cancellationSnapshot();
        assertThat(initialSnapshot.reservationStatus()).isEqualTo("취소신청");
        assertThat(initialSnapshot.seatReserved()).isTrue();
        assertThat(initialSnapshot.remainingSeats()).isEqualTo(TOTAL_SEATS - 1);
        assertThat(initialSnapshot.reservations()).isEqualTo(1);
        assertThat(initialSnapshot.inventoryEquationHolds()).isTrue();
    }

    @AfterEach
    void tearDown() {
        ReservationLockBarrier barrier = RESERVATION_LOCK_BARRIER.getAndSet(null);
        if (barrier != null) {
            barrier.releaseFirstLock();
        }
        deleteFixture();
    }

    @Test
    void approvedCancellationRestoresExactlyOneSeat() throws Exception {
        seatReservationService.cancelReservation(reservationId);

        CancellationSnapshot snapshot = cancellationSnapshot();

        assertThat(snapshot.reservationStatus()).isEqualTo("취소완료");
        assertThat(snapshot.seatReserved()).isFalse();
        assertThat(snapshot.remainingSeats()).isEqualTo(TOTAL_SEATS);
        assertThat(snapshot.inventoryEquationHolds()).isTrue();
    }

    @RepeatedTest(3)
    void duplicateCancellationRestoresInventoryOnlyOnce() throws Exception {
        seatReservationService.cancelReservation(reservationId);
        seatReservationService.cancelReservation(reservationId);

        CancellationSnapshot snapshot = cancellationSnapshot();

        assertThat(snapshot.reservationStatus()).isEqualTo("취소완료");
        assertThat(snapshot.seatReserved()).isFalse();
        assertThat(snapshot.remainingSeats()).isEqualTo(TOTAL_SEATS);
        assertThat(snapshot.inventoryEquationHolds()).isTrue();
    }

    @RepeatedTest(3)
    void concurrentDuplicateCancellationRestoresInventoryOnlyOnce() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ReservationLockBarrier barrier = new ReservationLockBarrier();
        RESERVATION_LOCK_BARRIER.set(barrier);

        try {
            Future<?> first = executor.submit(this::cancelReservation);
            assertThat(barrier.awaitFirstLock()).isTrue();

            Future<?> second = executor.submit(this::cancelReservation);
            assertThat(barrier.awaitSecondLockAttempt()).isTrue();
            assertThat(barrier.awaitSecondLock(200, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(first.isDone()).isFalse();
            assertThat(second.isDone()).isFalse();

            barrier.releaseFirstLock();
            assertThat(barrier.awaitSecondLock(5, TimeUnit.SECONDS)).isTrue();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            barrier.releaseFirstLock();
            RESERVATION_LOCK_BARRIER.set(null);
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        CancellationSnapshot snapshot = cancellationSnapshot();

        assertThat(snapshot.reservationStatus()).isEqualTo("취소완료");
        assertThat(snapshot.seatReserved()).isFalse();
        assertThat(snapshot.remainingSeats()).isEqualTo(TOTAL_SEATS);
        assertThat(snapshot.inventoryEquationHolds()).isTrue();
    }

    @Test
    void inventoryRecoveryFailureRollsBackReservationAndSeat() {
        Reservation reservation = reservationRepository.findById(reservationId).orElseThrow();
        reservation.setConcertTimeId(Long.MAX_VALUE);
        reservationRepository.saveAndFlush(reservation);
        entityManager.clear();

        assertThatThrownBy(() -> seatReservationService.cancelReservation(reservationId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("공연 회차의 잔여 좌석을 복구할 수 없습니다.");

        CancellationSnapshot snapshot = cancellationSnapshot();

        assertThat(snapshot.reservationStatus()).isEqualTo("취소신청");
        assertThat(snapshot.seatReserved()).isTrue();
        assertThat(snapshot.remainingSeats()).isEqualTo(TOTAL_SEATS - 1);
        assertThat(snapshot.inventoryEquationHolds()).isTrue();
    }

    @Test
    void missingReservationIsRejectedWithoutInventoryChange() {
        assertThatThrownBy(() -> seatReservationService.cancelReservation(Long.MAX_VALUE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("해당하는 예약이 없습니다.");

        CancellationSnapshot snapshot = cancellationSnapshot();

        assertThat(snapshot.reservationStatus()).isEqualTo("취소신청");
        assertThat(snapshot.seatReserved()).isTrue();
        assertThat(snapshot.remainingSeats()).isEqualTo(TOTAL_SEATS - 1);
        assertThat(snapshot.inventoryEquationHolds()).isTrue();
    }

    @Test
    void releasedSeatIsRejectedWithoutAdditionalInventoryRecovery() {
        Seat seat = seatRepository.findByConcertTimeAndSeatNumber(concertTimeId, "A1");
        seat.setReserved(false);
        seatRepository.saveAndFlush(seat);
        entityManager.clear();

        assertThatThrownBy(() -> seatReservationService.cancelReservation(reservationId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("예약 좌석 상태가 올바르지 않습니다.");

        CancellationSnapshot snapshot = cancellationSnapshot();

        assertThat(snapshot.reservationStatus()).isEqualTo("취소신청");
        assertThat(snapshot.seatReserved()).isFalse();
        assertThat(snapshot.remainingSeats()).isEqualTo(TOTAL_SEATS - 1);
    }

    @Test
    void paidReservationCannotSkipCancellationRequestState() {
        Reservation reservation = reservationRepository.findById(reservationId).orElseThrow();
        reservation.setStatus("결제완료");
        reservationRepository.saveAndFlush(reservation);
        entityManager.clear();

        assertThatThrownBy(() -> seatReservationService.cancelReservation(reservationId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("취소 신청 상태의 예약만 취소할 수 있습니다.");

        CancellationSnapshot snapshot = cancellationSnapshot();

        assertThat(snapshot.reservationStatus()).isEqualTo("결제완료");
        assertThat(snapshot.seatReserved()).isTrue();
        assertThat(snapshot.remainingSeats()).isEqualTo(TOTAL_SEATS - 1);
        assertThat(snapshot.inventoryEquationHolds()).isTrue();
    }

    private void cancelReservation() {
        try {
            seatReservationService.cancelReservation(reservationId);
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private CancellationFixture createFixture(String reservationStatus) {
        Concert concert = new Concert();
        concert.setConcertId(CONCERT_ID);
        concert.setConcertName("취소 정합성 기준선 공연");
        concert.setPosterUrl("https://example.invalid/poster.jpg");
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
        concertTime.setSeatAmount(TOTAL_SEATS - 1);
        concertTime = concertTimeRepository.saveAndFlush(concertTime);

        List<Seat> seats = new ArrayList<>();
        for (char row = 'A'; row <= 'C'; row++) {
            for (int number = 1; number <= 8; number++) {
                Seat seat = new Seat();
                seat.setSeatNumber(row + String.valueOf(number));
                seat.setReserved(row == 'A' && number == 1);
                seat.setConcertTime(concertTime);
                seats.add(seat);
            }
        }
        seatRepository.saveAllAndFlush(seats);
        Seat reservedSeat = seats.get(0);

        Reservation reservation = new Reservation();
        reservation.setConcertId(CONCERT_ID);
        reservation.setConcertName(concert.getConcertName());
        reservation.setPosterUrl(concert.getPosterUrl());
        reservation.setUsername(USERNAME);
        reservation.setCreatedAt(LocalDateTime.of(2026, 8, 24, 0, 0));
        reservation.setConcertDate(concertTime.getDate());
        reservation.setConcertTime(concertTime.getStartTime());
        reservation.setConcertTimeId(concertTime.getId());
        reservation.setSeat(reservedSeat);
        reservation.setSeatNumber(reservedSeat.getSeatNumber());
        reservation.setStatus(reservationStatus);
        reservation = reservationRepository.saveAndFlush(reservation);

        entityManager.clear();
        return new CancellationFixture(concertTime.getId(), reservation.getId());
    }

    private CancellationSnapshot cancellationSnapshot() {
        entityManager.clear();
        Reservation reservation = reservationRepository.findById(reservationId).orElseThrow();
        Seat seat = seatRepository.findByConcertTimeAndSeatNumber(concertTimeId, "A1");
        int remainingSeats = concertTimeRepository.findById(concertTimeId).orElseThrow().getSeatAmount();
        long reservedSeats = seatRepository.findByConcertTimeId(concertTimeId).stream()
                .filter(Seat::isReserved)
                .count();
        long reservations = reservationRepository.count();
        return new CancellationSnapshot(
                reservation.getStatus(),
                seat.isReserved(),
                remainingSeats,
                reservedSeats,
                reservations
        );
    }

    private void deleteFixture() {
        reservationRepository.deleteAllInBatch();
        seatRepository.deleteAllInBatch();
        concertTimeRepository.deleteAllInBatch();
        concertDetailRepository.deleteAllInBatch();
        concertRepository.deleteAllInBatch();
        entityManager.clear();
    }

    private record CancellationFixture(Long concertTimeId, Long reservationId) {
    }

    private record CancellationSnapshot(
            String reservationStatus,
            boolean seatReserved,
            int remainingSeats,
            long reservedSeats,
            long reservations
    ) {
        boolean inventoryEquationHolds() {
            return TOTAL_SEATS == remainingSeats + reservedSeats;
        }
    }

    private static final class ReservationLockBarrier {
        private final AtomicInteger lockAttempts = new AtomicInteger();
        private final AtomicBoolean firstLockHolder = new AtomicBoolean();
        private final CountDownLatch firstLockAcquired = new CountDownLatch(1);
        private final CountDownLatch secondLockAttempted = new CountDownLatch(1);
        private final CountDownLatch secondLockAcquired = new CountDownLatch(1);
        private final CountDownLatch releaseFirstLock = new CountDownLatch(1);

        int registerLockAttempt() {
            int attempt = lockAttempts.incrementAndGet();
            if (attempt == 2) {
                secondLockAttempted.countDown();
            }
            return attempt;
        }

        boolean holdFirstLock(int attempt) {
            return attempt == 1 && firstLockHolder.compareAndSet(false, true);
        }

        void firstLockAcquired() {
            firstLockAcquired.countDown();
        }

        boolean awaitFirstLock() throws InterruptedException {
            return firstLockAcquired.await(5, TimeUnit.SECONDS);
        }

        boolean awaitSecondLockAttempt() throws InterruptedException {
            return secondLockAttempted.await(5, TimeUnit.SECONDS);
        }

        void secondLockAcquired(int attempt) {
            if (attempt == 2) {
                secondLockAcquired.countDown();
            }
        }

        boolean awaitSecondLock(long timeout, TimeUnit unit) throws InterruptedException {
            return secondLockAcquired.await(timeout, unit);
        }

        void awaitRelease() throws InterruptedException {
            if (!releaseFirstLock.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("첫 취소 transaction의 lock 해제 신호를 기다리지 못했습니다.");
            }
        }

        void releaseFirstLock() {
            releaseFirstLock.countDown();
        }
    }

    @TestConfiguration
    static class ReservationLockBarrierConfiguration {

        @Bean
        static BeanPostProcessor reservationRepositoryBarrierBeanPostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof ReservationRepository reservationRepository)) {
                        return bean;
                    }

                    return Proxy.newProxyInstance(
                            ReservationRepository.class.getClassLoader(),
                            new Class<?>[]{ReservationRepository.class},
                            (proxy, method, args) -> {
                                ReservationLockBarrier barrier = RESERVATION_LOCK_BARRIER.get();
                                boolean lockQuery = method.getName().equals("findByIdWithLock") && barrier != null;
                                int lockAttempt = lockQuery ? barrier.registerLockAttempt() : 0;

                                Object result;
                                try {
                                    result = method.invoke(reservationRepository, args);
                                } catch (InvocationTargetException exception) {
                                    throw exception.getCause();
                                }

                                if (lockQuery && barrier.holdFirstLock(lockAttempt)) {
                                    barrier.firstLockAcquired();
                                    barrier.awaitRelease();
                                }
                                if (lockQuery) {
                                    barrier.secondLockAcquired(lockAttempt);
                                }
                                return result;
                            }
                    );
                }
            };
        }
    }
}
