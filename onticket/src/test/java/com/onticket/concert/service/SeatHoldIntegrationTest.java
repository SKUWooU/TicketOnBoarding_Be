package com.onticket.concert.service;

import com.onticket.concert.domain.Concert;
import com.onticket.concert.domain.ConcertTime;
import com.onticket.concert.domain.Seat;
import com.onticket.concert.domain.SeatAvailability;
import com.onticket.concert.dto.SeatHoldRequest;
import com.onticket.concert.dto.SeatHoldResponse;
import com.onticket.concert.repository.ConcertRepository;
import com.onticket.concert.repository.ConcertTimeRepository;
import com.onticket.concert.repository.SeatRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.show-sql=false",
        "onticket.ticket.seat-hold-duration=PT5M"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        SeatHoldService.class,
        SeatHoldIntegrationTest.ClockConfiguration.class,
        SeatHoldIntegrationTest.SeatLockBarrierConfiguration.class
})
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SeatHoldIntegrationTest {

    private static final String CONCERT_ID = "HOLD-CONCERT";
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2030, 1, 1, 12, 0);
    private static final AtomicReference<CyclicBarrier> SEAT_LOCK_BARRIER = new AtomicReference<>();
    private static final AtomicInteger SEAT_LOCK_COUNT = new AtomicInteger();

    @Container
    static final MariaDBContainer<?> MARIA_DB = new MariaDBContainer<>("mariadb:10.11.8")
            .withDatabaseName("onticket_hold")
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
    private SeatHoldService seatHoldService;

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
        SEAT_LOCK_BARRIER.set(null);
        SEAT_LOCK_COUNT.set(0);
        seatRepository.deleteAllInBatch();
        concertTimeRepository.deleteAllInBatch();
        concertRepository.deleteAllInBatch();
        entityManager.clear();
        clock.set(BASE_TIME);
        concertTimeId = createFixture();
    }

    @Test
    void sameOwnerRetryReusesOriginalExpiryWithoutExtension() {
        SeatHoldResponse first = seatHoldService.hold("user-a", CONCERT_ID, request("A1"));
        clock.advanceSeconds(60);
        SeatHoldResponse retry = seatHoldService.hold("user-a", CONCERT_ID, request("A1"));

        assertThat(first.getSeats()).singleElement().satisfies(held -> {
            assertThat(held.getSeatNumber()).isEqualTo("A1");
            assertThat(held.getExpiresAt()).isEqualTo(BASE_TIME.plusMinutes(5));
        });
        assertThat(retry.getSeats()).singleElement()
                .extracting(SeatHoldResponse.HeldSeat::getExpiresAt)
                .isEqualTo(BASE_TIME.plusMinutes(5));
        assertSeat("A1", "user-a", BASE_TIME.plusMinutes(5), SeatAvailability.HELD);
    }

    @Test
    void otherOwnerIsRejectedBeforeExpiryAndCanReclaimAtExpiryBoundary() {
        seatHoldService.hold("user-a", CONCERT_ID, request("A1"));

        clock.set(BASE_TIME.plusMinutes(5).minusNanos(1));
        assertThatThrownBy(() -> seatHoldService.hold("user-b", CONCERT_ID, request("A1")))
                .isExactlyInstanceOf(SeatHoldConflictException.class)
                .hasMessage("다른 사용자가 임시 점유한 좌석입니다.");

        clock.set(BASE_TIME.plusMinutes(5));
        SeatHoldResponse reclaimed = seatHoldService.hold("user-b", CONCERT_ID, request("A1"));

        assertThat(reclaimed.getSeats()).singleElement()
                .extracting(SeatHoldResponse.HeldSeat::getExpiresAt)
                .isEqualTo(BASE_TIME.plusMinutes(10));
        assertSeat("A1", "user-b", BASE_TIME.plusMinutes(10), SeatAvailability.HELD);
    }

    @Test
    void multiSeatReleaseByDifferentOwnerRollsBackEverySeat() {
        seatHoldService.hold("user-a", CONCERT_ID, request("A1", "A2"));

        assertThatThrownBy(() -> seatHoldService.release("user-b", CONCERT_ID, request("A1", "A2")))
                .isExactlyInstanceOf(SeatHoldConflictException.class)
                .hasMessage("다른 사용자의 임시 점유는 해제할 수 없습니다.");

        assertSeat("A1", "user-a", BASE_TIME.plusMinutes(5), SeatAvailability.HELD);
        assertSeat("A2", "user-a", BASE_TIME.plusMinutes(5), SeatAvailability.HELD);

        seatHoldService.release("user-a", CONCERT_ID, request("A2", "A1"));
        assertSeat("A1", null, null, SeatAvailability.AVAILABLE);
        assertSeat("A2", null, null, SeatAvailability.AVAILABLE);
    }

    @Test
    void multiSeatHoldConflictRollsBackEarlierSeatAcquisition() {
        seatHoldService.hold("user-b", CONCERT_ID, request("A2"));

        assertThatThrownBy(() -> seatHoldService.hold("user-a", CONCERT_ID, request("A1", "A2")))
                .isExactlyInstanceOf(SeatHoldConflictException.class);

        assertSeat("A1", null, null, SeatAvailability.AVAILABLE);
        assertSeat("A2", "user-b", BASE_TIME.plusMinutes(5), SeatAvailability.HELD);
    }

    @Test
    void concurrentDifferentOwnersForSameSeatAllowsOneHold() throws Exception {
        SEAT_LOCK_BARRIER.set(new CyclicBarrier(2));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<AttemptResult>> futures = List.of(
                    executor.submit(() -> attemptHold("user-a", ready, start)),
                    executor.submit(() -> attemptHold("user-b", ready, start))
            );
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<AttemptResult> results = new ArrayList<>();
            for (Future<AttemptResult> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }

            assertThat(results).filteredOn(AttemptResult::success).hasSize(1);
            AttemptResult winner = results.stream().filter(AttemptResult::success).findFirst().orElseThrow();
            assertThat(results).filteredOn(result -> !result.success()).singleElement().satisfies(loser -> {
                assertThat(loser.exceptionType()).isEqualTo(SeatHoldConflictException.class.getSimpleName());
                assertThat(loser.message()).isEqualTo("다른 사용자가 임시 점유한 좌석입니다.");
            });
            assertSeat("A1", winner.username(), BASE_TIME.plusMinutes(5), SeatAvailability.HELD);
        } finally {
            SEAT_LOCK_BARRIER.set(null);
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private AttemptResult attemptHold(String username, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                return AttemptResult.failure(username, "Timeout", "start latch timeout");
            }
            seatHoldService.hold(username, CONCERT_ID, request("A1"));
            return AttemptResult.succeeded(username);
        } catch (Exception exception) {
            return AttemptResult.failure(username, exception.getClass().getSimpleName(), exception.getMessage());
        }
    }

    private SeatHoldRequest request(String... seatNumbers) {
        SeatHoldRequest request = new SeatHoldRequest();
        request.setConcertTimeId(concertTimeId);
        request.setSeatNumberList(List.of(seatNumbers));
        return request;
    }

    private void assertSeat(
            String seatNumber,
            String heldBy,
            LocalDateTime heldUntil,
            SeatAvailability availability
    ) {
        entityManager.clear();
        Seat seat = seatRepository.findByConcertTimeAndSeatNumber(concertTimeId, seatNumber);
        assertThat(seat.getHeldBy()).isEqualTo(heldBy);
        assertThat(seat.getHeldUntil()).isEqualTo(heldUntil);
        assertThat(seat.availabilityAt(LocalDateTime.now(clock))).isEqualTo(availability);
    }

    private Long createFixture() {
        Concert concert = new Concert();
        concert.setConcertId(CONCERT_ID);
        concert.setConcertName("가상 임시 점유 공연");
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

    private record AttemptResult(boolean success, String username, String exceptionType, String message) {
        static AttemptResult succeeded(String username) {
            return new AttemptResult(true, username, null, null);
        }

        static AttemptResult failure(String username, String exceptionType, String message) {
            return new AttemptResult(false, username, exceptionType, message);
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

    @TestConfiguration
    static class SeatLockBarrierConfiguration {

        @Bean
        static BeanPostProcessor seatLockBarrierBeanPostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof SeatRepository seatRepository)) {
                        return bean;
                    }
                    return Proxy.newProxyInstance(
                            SeatRepository.class.getClassLoader(),
                            new Class<?>[]{SeatRepository.class},
                            (proxy, method, args) -> {
                                if (method.getName().equals("findByConcertTimeIdAndSeatNumberWithLock")) {
                                    CyclicBarrier barrier = SEAT_LOCK_BARRIER.get();
                                    int invocationCount = SEAT_LOCK_COUNT.incrementAndGet();
                                    if (barrier != null && invocationCount <= 2) {
                                        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                                            throw new IllegalStateException("좌석 잠금 barrier는 활성 transaction 안에서 실행해야 합니다.");
                                        }
                                        try {
                                            barrier.await(10, TimeUnit.SECONDS);
                                        } catch (TimeoutException exception) {
                                            throw new IllegalStateException("좌석 잠금 barrier 대기 시간을 초과했습니다.", exception);
                                        }
                                    }
                                }
                                try {
                                    return method.invoke(seatRepository, args);
                                } catch (InvocationTargetException exception) {
                                    throw exception.getCause();
                                }
                            }
                    );
                }
            };
        }
    }
}
