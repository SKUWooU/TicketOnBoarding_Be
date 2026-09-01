package com.onticket.concert.service;

import com.onticket.concert.domain.Checkout;
import com.onticket.concert.domain.CheckoutStatus;
import com.onticket.concert.domain.Concert;
import com.onticket.concert.domain.ConcertTime;
import com.onticket.concert.domain.Seat;
import com.onticket.concert.dto.CheckoutRequest;
import com.onticket.concert.dto.CheckoutResponse;
import com.onticket.concert.dto.SeatHoldRequest;
import com.onticket.concert.repository.CheckoutRepository;
import com.onticket.concert.repository.ConcertRepository;
import com.onticket.concert.repository.ConcertTimeRepository;
import com.onticket.concert.repository.SeatRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
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
}
