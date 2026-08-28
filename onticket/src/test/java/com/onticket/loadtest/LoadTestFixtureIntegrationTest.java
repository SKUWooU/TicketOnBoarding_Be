package com.onticket.loadtest;

import com.onticket.concert.domain.Booking;
import com.onticket.concert.domain.Payment;
import com.onticket.concert.repository.BookingRepository;
import com.onticket.concert.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.show-sql=false",
        "onticket.loadtest.fixture.rows=50",
        "onticket.loadtest.fixture.seats-per-row=40"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("loadtest")
@Import(LoadTestFixtureService.class)
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
    private PaymentRepository paymentRepository;

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
    void usesFixedWidthCanonicalSeatNumbers() {
        assertThat(LoadTestFixtureService.seatNumber(1, 1)).isEqualTo("R001-S001");
        assertThat(LoadTestFixtureService.seatNumber(50, 40)).isEqualTo("R050-S040");
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
}
