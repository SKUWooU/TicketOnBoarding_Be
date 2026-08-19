package com.onticket.concert.service;

import com.onticket.concert.domain.Concert;
import com.onticket.concert.domain.ConcertDetail;
import com.onticket.concert.domain.ConcertTime;
import com.onticket.concert.domain.Seat;
import com.onticket.concert.dto.ReservRequest;
import com.onticket.concert.repository.ConcertDetailRepository;
import com.onticket.concert.repository.ConcertRepository;
import com.onticket.concert.repository.ConcertTimeRepository;
import com.onticket.concert.repository.ReservationRepository;
import com.onticket.concert.repository.SeatRepository;
import com.onticket.user.jwt.JwtUtil;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.show-sql=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(SeatReservationService.class)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SeatReservationConcurrencyBaselineTest {

    private static final int TOTAL_SEATS = 24;
    private static final String CONCERT_ID = "BASELINE-CONCERT";
    private static final String USERNAME = "baseline-user";

    @Container
    static final MariaDBContainer<?> MARIA_DB = new MariaDBContainer<>("mariadb:10.11.8")
            .withDatabaseName("onticket_baseline")
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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private JwtUtil jwtUtil;

    private Long concertTimeId;

    @BeforeEach
    void setUp() {
        deleteFixture();
        concertTimeId = createFixture();
    }

    @AfterEach
    void tearDown() {
        deleteFixture();
    }

    @Test
    void singleSeatReservationKeepsInventoryRepresentationsConsistent() throws Exception {
        seatReservationService.reserveSeat(USERNAME, CONCERT_ID, request("A1"));

        InventorySnapshot snapshot = inventorySnapshot();

        assertThat(snapshot.successfullyReservedSeats()).isEqualTo(1);
        assertThat(snapshot.reservations()).isEqualTo(1);
        assertThat(snapshot.remainingSeats()).isEqualTo(TOTAL_SEATS - 1);
        assertThat(snapshot.inventoryEquationHolds()).isTrue();
    }

    @Test
    void sameSeatConcurrentReservationAllowsOnlyOneSuccess() throws Exception {
        int attempts = 8;
        List<AttemptResult> results = runConcurrently(
                java.util.Collections.nCopies(attempts, "A1")
        );

        InventorySnapshot snapshot = inventorySnapshot();

        assertThat(results).filteredOn(AttemptResult::success).hasSize(1);
        assertThat(results).filteredOn(result -> !result.success())
                .hasSize(attempts - 1)
                .allSatisfy(result -> {
                    assertThat(result.exceptionType()).isEqualTo("Exception");
                    assertThat(result.message()).isEqualTo("이미 예약된 좌석입니다.");
                });
        assertThat(snapshot.successfullyReservedSeats()).isEqualTo(1);
        assertThat(snapshot.reservations()).isEqualTo(1);
        assertThat(snapshot.remainingSeats()).isEqualTo(TOTAL_SEATS - 1);
        assertThat(snapshot.inventoryEquationHolds()).isTrue();

        System.out.printf(
                "SAME_SEAT_BASELINE attempts=%d successes=1 failures=%d remaining=%d reserved=%d reservations=%d invariant=%s%n",
                attempts,
                attempts - 1,
                snapshot.remainingSeats(),
                snapshot.successfullyReservedSeats(),
                snapshot.reservations(),
                snapshot.inventoryEquationHolds()
        );
    }

    @Test
    void differentSeatConcurrentReservationCapturesAggregateBaseline() throws Exception {
        List<String> differentSeats = List.of("A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8");
        List<AttemptResult> results = runConcurrently(differentSeats);

        InventorySnapshot snapshot = inventorySnapshot();

        assertThat(results).allMatch(AttemptResult::success);
        assertThat(snapshot.successfullyReservedSeats()).isEqualTo(differentSeats.size());
        assertThat(snapshot.reservations()).isEqualTo(differentSeats.size());

        System.out.printf(
                "DIFFERENT_SEAT_BASELINE attempts=%d remaining=%d reserved=%d reservations=%d invariant=%s%n",
                differentSeats.size(),
                snapshot.remainingSeats(),
                snapshot.successfullyReservedSeats(),
                snapshot.reservations(),
                snapshot.inventoryEquationHolds()
        );

        assertThat(snapshot.remainingSeats())
                .as("8회 감소 중 하나 이상이 유실되는 현재 기준선")
                .isBetween(TOTAL_SEATS - differentSeats.size() + 1, TOTAL_SEATS - 1);
        assertThat(snapshot.inventoryEquationHolds()).isFalse();
    }

    @Test
    void checkedFailureAfterFirstSeatCapturesPartialCommitBaseline() {
        assertThatThrownBy(() -> seatReservationService.reserveSeat(
                USERNAME,
                CONCERT_ID,
                request("A1", "NOT-EXISTING")
        ))
                .isExactlyInstanceOf(Exception.class)
                .hasMessage("존재하지 않는 좌석입니다.");

        InventorySnapshot snapshot = inventorySnapshot();

        System.out.printf(
                "CHECKED_EXCEPTION_BASELINE remaining=%d reserved=%d reservations=%d invariant=%s%n",
                snapshot.remainingSeats(),
                snapshot.successfullyReservedSeats(),
                snapshot.reservations(),
                snapshot.inventoryEquationHolds()
        );

        assertThat(snapshot.successfullyReservedSeats()).isEqualTo(1);
        assertThat(snapshot.reservations()).isEqualTo(1);
        assertThat(snapshot.remainingSeats()).isEqualTo(TOTAL_SEATS);
        assertThat(snapshot.inventoryEquationHolds()).isFalse();
    }

    @Test
    void seatLockQueryIndexBaseline() {
        List<Map<String, Object>> indexes = jdbcTemplate.queryForList("SHOW INDEX FROM seat");
        List<String> indexedColumns = indexes.stream()
                .sorted(Comparator.comparingInt(row -> ((Number) row.get("Seq_in_index")).intValue()))
                .map(row -> String.valueOf(row.get("Column_name")))
                .toList();

        List<Map<String, Object>> explain = jdbcTemplate.queryForList(
                "EXPLAIN SELECT * FROM seat WHERE concert_time_id = ? AND seat_number = ? FOR UPDATE",
                concertTimeId,
                "A1"
        );

        System.out.printf("SEAT_INDEX_BASELINE columns=%s explain=%s%n", indexedColumns, explain);

        assertThat(indexedColumns).contains("id", "concert_time_id");
        assertThat(indexedColumns).doesNotContain("seat_number");
        assertThat(explain).hasSize(1);
        assertThat(explain.getFirst().get("type")).isEqualTo("ALL");
        assertThat(explain.getFirst().get("key")).isNull();
    }

    private List<AttemptResult> runConcurrently(List<String> seatNumbers) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(seatNumbers.size());
        CountDownLatch ready = new CountDownLatch(seatNumbers.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AttemptResult>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < seatNumbers.size(); i++) {
                String username = USERNAME + "-" + i;
                String seatNumber = seatNumbers.get(i);
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        return AttemptResult.failure("StartTimeout", "동시 시작 신호 대기 시간 초과");
                    }

                    try {
                        seatReservationService.reserveSeat(username, CONCERT_ID, request(seatNumber));
                        return AttemptResult.succeeded();
                    } catch (Exception exception) {
                        return AttemptResult.failure(exception.getClass().getSimpleName(), exception.getMessage());
                    }
                }));
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<AttemptResult> results = new ArrayList<>();
            for (Future<AttemptResult> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private ReservRequest request(String... seatNumbers) {
        ReservRequest request = new ReservRequest();
        request.setConcertTimeId(concertTimeId);
        request.setSeatNumberList(List.of(seatNumbers));
        return request;
    }

    private Long createFixture() {
        Concert concert = new Concert();
        concert.setConcertId(CONCERT_ID);
        concert.setConcertName("예매 정합성 기준선 공연");
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

    private InventorySnapshot inventorySnapshot() {
        entityManager.clear();
        long reservedSeats = seatRepository.findByConcertTimeId(concertTimeId).stream()
                .filter(Seat::isReserved)
                .count();
        long reservations = reservationRepository.count();
        int remainingSeats = concertTimeRepository.findById(concertTimeId).orElseThrow().getSeatAmount();
        return new InventorySnapshot(remainingSeats, reservedSeats, reservations);
    }

    private void deleteFixture() {
        reservationRepository.deleteAllInBatch();
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

    private record InventorySnapshot(int remainingSeats, long successfullyReservedSeats, long reservations) {
        boolean inventoryEquationHolds() {
            return TOTAL_SEATS == remainingSeats + successfullyReservedSeats;
        }
    }
}
