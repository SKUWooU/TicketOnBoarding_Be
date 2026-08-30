package com.onticket.concert.service;

import com.onticket.concert.domain.Concert;
import com.onticket.concert.domain.ConcertDetail;
import com.onticket.concert.domain.ConcertTime;
import com.onticket.concert.domain.Booking;
import com.onticket.concert.domain.Reservation;
import com.onticket.concert.domain.ReservationStatus;
import com.onticket.concert.domain.Seat;
import com.onticket.concert.dto.ReservRequest;
import com.onticket.concert.repository.ConcertDetailRepository;
import com.onticket.concert.repository.ConcertRepository;
import com.onticket.concert.repository.ConcertTimeRepository;
import com.onticket.concert.repository.BookingRepository;
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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.show-sql=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        SeatReservationService.class,
        ReservationIdempotencyService.class,
        ReservationBookingTransactionService.class,
        SeatReservationConcurrencyIntegrationTest.SeatRepositoryBarrierConfiguration.class
})
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SeatReservationConcurrencyIntegrationTest {

    private static final int TOTAL_SEATS = 24;
    private static final String CONCERT_ID = "BASELINE-CONCERT";
    private static final String USERNAME = "baseline-user";
    private static final String SEAT_COMPOSITE_UNIQUE_INDEX = "uk_seat_concert_time_number";
    private static final String SEAT_MIGRATION_SUPPORT_INDEX = "idx_seat_concert_time_migration_test";
    private static final AtomicReference<CyclicBarrier> AGGREGATE_READ_BARRIER = new AtomicReference<>();
    private static final AtomicReference<CyclicBarrier> FIRST_SEAT_LOCK_BARRIER = new AtomicReference<>();
    private static final AtomicReference<CyclicBarrier> IDEMPOTENCY_LOOKUP_BARRIER = new AtomicReference<>();
    private static final AtomicBoolean BOTH_FIRST_SEAT_LOCKS_ACQUIRED = new AtomicBoolean();
    private static final AtomicInteger SEAT_LOCK_QUERY_COUNT = new AtomicInteger();
    private static final AtomicInteger IDEMPOTENCY_LOOKUP_COUNT = new AtomicInteger();
    private static final ConcurrentMap<Long, List<String>> SEAT_LOCK_QUERY_ORDER = new ConcurrentHashMap<>();
    private static final ThreadLocal<Integer> SEAT_LOCK_CALL_COUNT = ThreadLocal.withInitial(() -> 0);

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
    private BookingRepository bookingRepository;

    @Autowired
    private ReservationIdempotencyService reservationIdempotencyService;

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
        SEAT_LOCK_QUERY_COUNT.set(0);
        IDEMPOTENCY_LOOKUP_COUNT.set(0);
        IDEMPOTENCY_LOOKUP_BARRIER.set(null);
        SEAT_LOCK_QUERY_ORDER.clear();
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
    void currentReservationContractCreatesPaymentCompletedStateWithoutPaymentEvidence() throws Exception {
        ReservRequest reservRequest = request("A1");

        List<String> requestFields = Arrays.stream(ReservRequest.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)
                .toList();
        assertThat(requestFields).containsExactlyInAnyOrder(
                "concertDate",
                "concertTimeId",
                "concertTime",
                "seatNumberList"
        );

        seatReservationService.reserveSeat(USERNAME, CONCERT_ID, reservRequest);

        Reservation reservation = reservationRepository.findAll().getFirst();
        InventorySnapshot snapshot = inventorySnapshot();

        assertThat(reservation.getUsername()).isEqualTo(USERNAME);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PAYMENT_COMPLETED);
        assertThat(reservation.getSeatNumber()).isEqualTo("A1");
        assertThat(snapshot.successfullyReservedSeats()).isEqualTo(1);
        assertThat(snapshot.reservations()).isEqualTo(1);
        assertThat(snapshot.remainingSeats()).isEqualTo(TOTAL_SEATS - 1);
        assertThat(snapshot.inventoryEquationHolds()).isTrue();
    }

    @Test
    void replayAfterSuccessfulReservationCannotReturnOriginalSuccess() throws Exception {
        ReservRequest reservRequest = request("A1");
        seatReservationService.reserveSeat(USERNAME, CONCERT_ID, reservRequest);
        InventorySnapshot firstSuccess = inventorySnapshot();

        assertThatThrownBy(() -> seatReservationService.reserveSeat(USERNAME, CONCERT_ID, reservRequest))
                .isExactlyInstanceOf(SeatReservationConflictException.class)
                .hasMessage("이미 예약된 좌석입니다.");

        InventorySnapshot retrySnapshot = inventorySnapshot();
        List<Reservation> reservations = reservationRepository.findAll();

        assertThat(retrySnapshot).isEqualTo(firstSuccess);
        assertThat(retrySnapshot.successfullyReservedSeats()).isEqualTo(1);
        assertThat(retrySnapshot.reservations()).isEqualTo(1);
        assertThat(retrySnapshot.remainingSeats()).isEqualTo(TOTAL_SEATS - 1);
        assertThat(retrySnapshot.inventoryEquationHolds()).isTrue();
        assertThat(reservations).singleElement()
                .satisfies(reservation -> assertThat(reservation.getStatus())
                        .isEqualTo(ReservationStatus.PAYMENT_COMPLETED));
    }

    @Test
    void sameIdempotencyKeyAndCanonicalPayloadReturnsFirstSuccess() throws Exception {
        String idempotencyKey = "booking-retry-key";

        LocalDateTime firstResult = reservationIdempotencyService.reserve(
                USERNAME,
                CONCERT_ID,
                request("A2", "A1"),
                idempotencyKey
        );
        LocalDateTime replayResult = reservationIdempotencyService.reserve(
                USERNAME,
                CONCERT_ID,
                request("A1", "A2"),
                idempotencyKey
        );

        InventorySnapshot snapshot = inventorySnapshot();
        List<Reservation> reservations = reservationRepository.findAll();

        assertThat(replayResult).isEqualTo(firstResult);
        assertThat(bookingRepository.count()).isEqualTo(1);
        assertThat(reservations).hasSize(2)
                .allSatisfy(reservation -> assertThat(reservation.getBooking().getId()).isNotNull());
        assertThat(snapshot.successfullyReservedSeats()).isEqualTo(2);
        assertThat(snapshot.reservations()).isEqualTo(2);
        assertThat(snapshot.remainingSeats()).isEqualTo(TOTAL_SEATS - 2);
        assertThat(snapshot.inventoryEquationHolds()).isTrue();
    }

    @Test
    void requestWithoutIdempotencyKeyKeepsLegacyReservationPath() throws Exception {
        LocalDateTime result = reservationIdempotencyService.reserve(
                USERNAME,
                CONCERT_ID,
                request("A1"),
                null
        );

        assertThat(result).isNotNull();
        assertThat(bookingRepository.count()).isZero();
        assertThat(inventorySnapshot()).isEqualTo(new InventorySnapshot(TOTAL_SEATS - 1, 1, 1));
    }

    @Test
    void blankIdempotencyKeyIsRejectedBeforeStateChange() {
        assertThatThrownBy(() -> reservationIdempotencyService.reserve(
                USERNAME,
                CONCERT_ID,
                request("A1"),
                "   "
        ))
                .isExactlyInstanceOf(InvalidIdempotencyKeyException.class)
                .hasMessage("멱등 키는 비어 있을 수 없습니다.");

        assertThat(bookingRepository.count()).isZero();
        assertThat(inventorySnapshot()).isEqualTo(new InventorySnapshot(TOTAL_SEATS, 0, 0));
    }

    @Test
    void oversizedIdempotencyKeyIsRejectedBeforeStateChange() {
        assertThatThrownBy(() -> reservationIdempotencyService.reserve(
                USERNAME,
                CONCERT_ID,
                request("A1"),
                "k".repeat(101)
        ))
                .isExactlyInstanceOf(InvalidIdempotencyKeyException.class)
                .hasMessage("멱등 키는 100자를 초과할 수 없습니다.");

        assertThat(bookingRepository.count()).isZero();
        assertThat(inventorySnapshot()).isEqualTo(new InventorySnapshot(TOTAL_SEATS, 0, 0));
    }

    @RepeatedTest(3)
    void concurrentSameIdempotencyKeyReturnsOneBookingResult() throws Exception {
        String idempotencyKey = "concurrent-booking-key";
        IDEMPOTENCY_LOOKUP_BARRIER.set(new CyclicBarrier(2));

        try {
            List<LocalDateTime> results = runIdempotentRequestsConcurrently(idempotencyKey, request("A1"));
            InventorySnapshot snapshot = inventorySnapshot();

            assertThat(results).hasSize(2).allMatch(results.getFirst()::equals);
            assertThat(IDEMPOTENCY_LOOKUP_COUNT).hasValue(3);
            assertThat(bookingRepository.count()).isEqualTo(1);
            assertThat(snapshot.successfullyReservedSeats()).isEqualTo(1);
            assertThat(snapshot.reservations()).isEqualTo(1);
            assertThat(snapshot.remainingSeats()).isEqualTo(TOTAL_SEATS - 1);
            assertThat(snapshot.inventoryEquationHolds()).isTrue();
        } finally {
            IDEMPOTENCY_LOOKUP_BARRIER.set(null);
        }
    }

    @Test
    void sameIdempotencyKeyWithDifferentPayloadIsRejectedWithoutStateChange() throws Exception {
        String idempotencyKey = "payload-conflict-key";
        reservationIdempotencyService.reserve(USERNAME, CONCERT_ID, request("A1"), idempotencyKey);
        InventorySnapshot firstSuccess = inventorySnapshot();

        assertThatThrownBy(() -> reservationIdempotencyService.reserve(
                USERNAME,
                CONCERT_ID,
                request("A2"),
                idempotencyKey
        ))
                .isExactlyInstanceOf(IdempotencyKeyConflictException.class)
                .hasMessage("동일한 멱등 키가 다른 예약 요청에 사용되었습니다.");

        assertThat(inventorySnapshot()).isEqualTo(firstSuccess);
        assertThat(bookingRepository.count()).isEqualTo(1);
    }

    @Test
    void failedReservationRollsBackIdempotencyKeyForRetry() throws Exception {
        String idempotencyKey = "retry-after-rollback-key";

        assertThatThrownBy(() -> reservationIdempotencyService.reserve(
                USERNAME,
                CONCERT_ID,
                request("Z9"),
                idempotencyKey
        ))
                .isExactlyInstanceOf(Exception.class)
                .hasMessage("존재하지 않는 좌석입니다.");

        assertThat(bookingRepository.count()).isZero();
        assertThat(inventorySnapshot()).isEqualTo(new InventorySnapshot(TOTAL_SEATS, 0, 0));

        LocalDateTime retryResult = reservationIdempotencyService.reserve(
                USERNAME,
                CONCERT_ID,
                request("A1"),
                idempotencyKey
        );

        assertThat(retryResult).isNotNull();
        assertThat(bookingRepository.count()).isEqualTo(1);
        assertThat(inventorySnapshot()).isEqualTo(new InventorySnapshot(TOTAL_SEATS - 1, 1, 1));
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
                    assertThat(result.exceptionType()).isEqualTo("SeatReservationConflictException");
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

    @RepeatedTest(3)
    void differentSeatConcurrentReservationKeepsAggregateConsistent() throws Exception {
        List<String> differentSeats = List.of("A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8");
        AGGREGATE_READ_BARRIER.set(new CyclicBarrier(differentSeats.size()));

        List<AttemptResult> results;
        try {
            results = runConcurrently(differentSeats);
        } finally {
            AGGREGATE_READ_BARRIER.set(null);
        }

        InventorySnapshot snapshot = inventorySnapshot();

        assertThat(results).allMatch(AttemptResult::success);
        assertThat(snapshot.successfullyReservedSeats()).isEqualTo(differentSeats.size());
        assertThat(snapshot.reservations()).isEqualTo(differentSeats.size());

        System.out.printf(
                "DIFFERENT_SEAT_AFTER attempts=%d remaining=%d reserved=%d reservations=%d invariant=%s%n",
                differentSeats.size(),
                snapshot.remainingSeats(),
                snapshot.successfullyReservedSeats(),
                snapshot.reservations(),
                snapshot.inventoryEquationHolds()
        );

        assertThat(snapshot.remainingSeats())
                .isEqualTo(TOTAL_SEATS - differentSeats.size());
        assertThat(snapshot.inventoryEquationHolds()).isTrue();
    }

    @RepeatedTest(3)
    void oppositeSeatOrderConcurrentReservationSerializesAtFirstLockWithoutDeadlock() throws Exception {
        FIRST_SEAT_LOCK_BARRIER.set(new CyclicBarrier(2));
        BOTH_FIRST_SEAT_LOCKS_ACQUIRED.set(false);

        List<LockAttemptResult> results;
        try {
            results = runRequestsConcurrently(List.of(
                    List.of("A1", "A2"),
                    List.of("A2", "A1")
            ));
        } finally {
            FIRST_SEAT_LOCK_BARRIER.set(null);
        }

        InventorySnapshot snapshot = inventorySnapshot();

        System.out.printf(
                "OPPOSITE_ORDER_BASELINE firstLocksConcurrent=%s results=%s remaining=%d reserved=%d reservations=%d invariant=%s%n",
                BOTH_FIRST_SEAT_LOCKS_ACQUIRED.get(),
                results,
                snapshot.remainingSeats(),
                snapshot.successfullyReservedSeats(),
                snapshot.reservations(),
                snapshot.inventoryEquationHolds()
        );

        assertThat(results).hasSize(2);
        assertThat(BOTH_FIRST_SEAT_LOCKS_ACQUIRED.get()).isFalse();
        assertThat(results).filteredOn(LockAttemptResult::success).hasSize(1);
        assertThat(results).filteredOn(result -> !result.success())
                .hasSize(1)
                .allSatisfy(result -> {
                    assertThat(result.exceptionType()).isEqualTo("SeatReservationConflictException");
                    assertThat(result.message()).isEqualTo("이미 예약된 좌석입니다.");
                    assertThat(result.sqlState()).isNull();
                    assertThat(result.errorCode()).isNull();
                });
        assertThat(snapshot.successfullyReservedSeats()).isEqualTo(2);
        assertThat(snapshot.reservations()).isEqualTo(2);
        assertThat(snapshot.remainingSeats()).isEqualTo(TOTAL_SEATS - 2);
        assertThat(snapshot.inventoryEquationHolds()).isTrue();
        assertThat(snapshot.successfullyReservedSeats()).isEqualTo(snapshot.reservations());
    }

    @RepeatedTest(3)
    void indexedOppositeSeatOrderConcurrentReservationUsesCanonicalOrderWithoutDeadlock() throws Exception {
        SEAT_LOCK_QUERY_ORDER.clear();

        List<LockAttemptResult> results = runRequestsConcurrently(List.of(
                List.of("A1", "A2"),
                List.of("A2", "A1")
        ));

        InventorySnapshot snapshot = inventorySnapshot();
        List<List<String>> lockQueryOrders = SEAT_LOCK_QUERY_ORDER.values().stream()
                .map(List::copyOf)
                .toList();

        System.out.printf(
                "INDEXED_CANONICAL_ORDER lockQueryOrders=%s results=%s remaining=%d reserved=%d reservations=%d invariant=%s%n",
                lockQueryOrders,
                results,
                snapshot.remainingSeats(),
                snapshot.successfullyReservedSeats(),
                snapshot.reservations(),
                snapshot.inventoryEquationHolds()
        );

        assertThat(lockQueryOrders).hasSize(2);
        assertThat(lockQueryOrders)
                .allSatisfy(order -> assertThat(order).startsWith("A1"));
        assertThat(results).filteredOn(LockAttemptResult::success).hasSize(1);
        assertThat(results).filteredOn(result -> !result.success())
                .hasSize(1)
                .allSatisfy(result -> {
                    assertThat(result.exceptionType()).isEqualTo("SeatReservationConflictException");
                    assertThat(result.message()).isEqualTo("이미 예약된 좌석입니다.");
                    assertThat(result.sqlState()).isNull();
                    assertThat(result.errorCode()).isNull();
                });
        assertReservationsMatchAttemptResults(results);
        assertThat(snapshot.successfullyReservedSeats()).isEqualTo(2);
        assertThat(snapshot.reservations()).isEqualTo(2);
        assertThat(snapshot.remainingSeats()).isEqualTo(TOTAL_SEATS - 2);
        assertThat(snapshot.inventoryEquationHolds()).isTrue();
    }

    @Test
    void reservationSortsCopiedSeatNumbersWithoutMutatingRequest() throws Exception {
        List<String> requestedSeatNumbers = new ArrayList<>(List.of("A2", "A1"));
        ReservRequest reservRequest = request();
        reservRequest.setSeatNumberList(requestedSeatNumbers);

        seatReservationService.reserveSeat(USERNAME, CONCERT_ID, reservRequest);

        assertThat(requestedSeatNumbers).containsExactly("A2", "A1");
        assertThat(SEAT_LOCK_QUERY_ORDER.values()).singleElement()
                .satisfies(order -> assertThat(order).containsExactly("A1", "A2"));
    }

    @Test
    void invalidSeatSelectionsFailBeforeSeatLockQuery() {
        assertThatThrownBy(() -> seatReservationService.reserveSeat(USERNAME, CONCERT_ID, null))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("예약 요청이 필요합니다.");
        assertInvalidSeatSelection(null, "좌석을 한 개 이상 선택해야 합니다.");
        assertInvalidSeatSelection(List.of(), "좌석을 한 개 이상 선택해야 합니다.");
        assertInvalidSeatSelection(Arrays.asList("A1", null), "좌석 번호는 비어 있을 수 없습니다.");
        assertInvalidSeatSelection(List.of("A1", " "), "좌석 번호는 비어 있을 수 없습니다.");
        assertInvalidSeatSelection(List.of("A1", "A1"), "중복된 좌석을 선택할 수 없습니다.");

        assertThat(SEAT_LOCK_QUERY_COUNT).hasValue(0);
        InventorySnapshot snapshot = inventorySnapshot();
        assertThat(snapshot.successfullyReservedSeats()).isZero();
        assertThat(snapshot.reservations()).isZero();
        assertThat(snapshot.remainingSeats()).isEqualTo(TOTAL_SEATS);
        assertThat(snapshot.inventoryEquationHolds()).isTrue();
    }

    @Test
    void checkedFailureAfterFirstSeatRollsBackEntireReservation() {
        assertThatThrownBy(() -> seatReservationService.reserveSeat(
                USERNAME,
                CONCERT_ID,
                request("A1", "NOT-EXISTING")
        ))
                .isExactlyInstanceOf(Exception.class)
                .hasMessage("존재하지 않는 좌석입니다.");

        InventorySnapshot snapshot = inventorySnapshot();

        System.out.printf(
                "CHECKED_EXCEPTION_AFTER remaining=%d reserved=%d reservations=%d invariant=%s%n",
                snapshot.remainingSeats(),
                snapshot.successfullyReservedSeats(),
                snapshot.reservations(),
                snapshot.inventoryEquationHolds()
        );

        assertThat(snapshot.successfullyReservedSeats()).isZero();
        assertThat(snapshot.reservations()).isZero();
        assertThat(snapshot.remainingSeats()).isEqualTo(TOTAL_SEATS);
        assertThat(snapshot.inventoryEquationHolds()).isTrue();
    }

    @Test
    void insufficientAggregateRollsBackSeatsAndReservationsWithoutGoingNegative() {
        ConcertTime concertTime = concertTimeRepository.findById(concertTimeId).orElseThrow();
        concertTime.setSeatAmount(1);
        concertTimeRepository.saveAndFlush(concertTime);
        entityManager.clear();

        assertThatThrownBy(() -> seatReservationService.reserveSeat(
                USERNAME,
                CONCERT_ID,
                request("A1", "A2")
        ))
                .isExactlyInstanceOf(SeatReservationConflictException.class)
                .hasMessage("잔여 좌석이 부족합니다.");

        InventorySnapshot snapshot = inventorySnapshot();

        assertThat(snapshot.successfullyReservedSeats()).isZero();
        assertThat(snapshot.reservations()).isZero();
        assertThat(snapshot.remainingSeats()).isEqualTo(1);
    }

    @Test
    void entitySchemaCreatesCompositeUniqueIndexForSeatLockQuery() {
        List<Map<String, Object>> indexes = jdbcTemplate.queryForList("SHOW INDEX FROM seat");
        List<Map<String, Object>> compositeIndexRows = indexes.stream()
                .filter(row -> SEAT_COMPOSITE_UNIQUE_INDEX.equals(row.get("Key_name")))
                .sorted(Comparator.comparingInt(row -> ((Number) row.get("Seq_in_index")).intValue()))
                .toList();
        List<String> indexedColumns = compositeIndexRows.stream()
                .map(row -> String.valueOf(row.get("Column_name")))
                .toList();

        List<Map<String, Object>> explain = jdbcTemplate.queryForList(
                "EXPLAIN SELECT * FROM seat WHERE concert_time_id = ? AND seat_number = ? FOR UPDATE",
                concertTimeId,
                "A1"
        );

        System.out.printf("SEAT_ENTITY_COMPOSITE_INDEX columns=%s explain=%s%n", indexedColumns, explain);

        assertThat(indexedColumns).containsExactly("concert_time_id", "seat_number");
        assertThat(compositeIndexRows)
                .allSatisfy(row -> assertThat(((Number) row.get("Non_unique")).intValue()).isZero());
        assertThat(explain).hasSize(1);
        assertThat(explain.getFirst().get("type")).isEqualTo("const");
        assertThat(explain.getFirst().get("key")).isEqualTo(SEAT_COMPOSITE_UNIQUE_INDEX);
        assertThat(Integer.parseInt(String.valueOf(explain.getFirst().get("rows")))).isEqualTo(1);
    }

    @Test
    void entitySchemaRejectsDuplicateSeatIdentityWithinSameConcertTime() {
        DataAccessException duplicateInsertFailure = catchThrowableOfType(
                () -> insertSeat(concertTimeId, "A1"),
                DataAccessException.class
        );

        assertSqlFailure(duplicateInsertFailure, "23000", 1062);
        assertThat(countSeats(concertTimeId, "A1")).isEqualTo(1);
    }

    @Test
    void entitySchemaAllowsSameSeatNumberInDifferentConcertTimes() {
        Concert concert = concertRepository.findById(CONCERT_ID).orElseThrow();
        ConcertTime secondConcertTime = new ConcertTime();
        secondConcertTime.setConcert(concert);
        secondConcertTime.setDate(LocalDate.of(2030, 1, 11));
        secondConcertTime.setDayOfWeek("FRIDAY");
        secondConcertTime.setStartTime(LocalTime.of(19, 0));
        secondConcertTime.setSeatAmount(1);
        secondConcertTime = concertTimeRepository.saveAndFlush(secondConcertTime);

        insertSeat(secondConcertTime.getId(), "A1");

        assertThat(countSeats(concertTimeId, "A1")).isEqualTo(1);
        assertThat(countSeats(secondConcertTime.getId(), "A1")).isEqualTo(1);
    }

    @Test
    void duplicateSeatIdentityStillBlocksExistingSchemaMigrationWithoutChangingData() {
        dropSeatCompositeUniqueIndexForMigrationBaseline();
        boolean duplicateInserted = false;

        try {
            insertSeat(concertTimeId, "A1");
            duplicateInserted = true;

            List<Map<String, Object>> duplicates = jdbcTemplate.queryForList("""
                    SELECT concert_time_id, seat_number, COUNT(*) AS duplicate_count
                    FROM seat
                    GROUP BY concert_time_id, seat_number
                    HAVING COUNT(*) > 1
                    """);

            assertThat(duplicates).singleElement().satisfies(duplicate -> {
                assertThat(((Number) duplicate.get("concert_time_id")).longValue()).isEqualTo(concertTimeId);
                assertThat(duplicate.get("seat_number")).isEqualTo("A1");
                assertThat(((Number) duplicate.get("duplicate_count")).intValue()).isEqualTo(2);
            });

            DataAccessException migrationFailure = catchThrowableOfType(
                    this::createSeatCompositeUniqueIndex,
                    DataAccessException.class
            );

            assertSqlFailure(migrationFailure, "23000", 1062);
            assertThat(hasSeatCompositeUniqueIndex()).isFalse();
            assertThat(countSeats(concertTimeId, "A1")).isEqualTo(2);
        } finally {
            if (duplicateInserted) {
                jdbcTemplate.update(
                        "DELETE FROM seat WHERE concert_time_id = ? AND seat_number = ? ORDER BY id DESC LIMIT 1",
                        concertTimeId,
                        "A1"
                );
            }
            if (!hasSeatCompositeUniqueIndex()) {
                createSeatCompositeUniqueIndex();
            }
            jdbcTemplate.execute("DROP INDEX %s ON seat".formatted(SEAT_MIGRATION_SUPPORT_INDEX));
        }
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

    private List<LocalDateTime> runIdempotentRequestsConcurrently(
            String idempotencyKey,
            ReservRequest reservRequest
    ) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<LocalDateTime>> futures = new ArrayList<>();

        try {
            for (int index = 0; index < 2; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new TimeoutException("동시 시작 신호 대기 시간 초과");
                    }
                    return reservationIdempotencyService.reserve(
                            USERNAME,
                            CONCERT_ID,
                            reservRequest,
                            idempotencyKey
                    );
                }));
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<LocalDateTime> results = new ArrayList<>();
            for (Future<LocalDateTime> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private List<LockAttemptResult> runRequestsConcurrently(List<List<String>> seatNumberRequests) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(seatNumberRequests.size());
        CountDownLatch ready = new CountDownLatch(seatNumberRequests.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<LockAttemptResult>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < seatNumberRequests.size(); i++) {
                String username = USERNAME + "-opposite-" + i;
                List<String> seatNumbers = seatNumberRequests.get(i);
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        return LockAttemptResult.failure(
                                new TimeoutException("동시 시작 신호 대기 시간 초과")
                        );
                    }

                    try {
                        seatReservationService.reserveSeat(
                                username,
                                CONCERT_ID,
                                request(seatNumbers.toArray(String[]::new))
                        );
                        return LockAttemptResult.succeeded();
                    } catch (Exception exception) {
                        return LockAttemptResult.failure(exception);
                    } finally {
                        SEAT_LOCK_CALL_COUNT.remove();
                    }
                }));
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<LockAttemptResult> results = new ArrayList<>();
            for (Future<LockAttemptResult> future : futures) {
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

    private void assertInvalidSeatSelection(List<String> seatNumbers, String message) {
        ReservRequest reservRequest = request();
        reservRequest.setSeatNumberList(seatNumbers);

        assertThatThrownBy(() -> seatReservationService.reserveSeat(USERNAME, CONCERT_ID, reservRequest))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }

    private void insertSeat(Long targetConcertTimeId, String seatNumber) {
        jdbcTemplate.update(
                "INSERT INTO seat (concert_time_id, reserved, seat_number) VALUES (?, false, ?)",
                targetConcertTimeId,
                seatNumber
        );
    }

    private int countSeats(Long targetConcertTimeId, String seatNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM seat WHERE concert_time_id = ? AND seat_number = ?",
                Integer.class,
                targetConcertTimeId,
                seatNumber
        );
    }

    private void createSeatCompositeUniqueIndex() {
        jdbcTemplate.execute("""
                CREATE UNIQUE INDEX %s
                ON seat (concert_time_id, seat_number)
                """.formatted(SEAT_COMPOSITE_UNIQUE_INDEX));
    }

    private void dropSeatCompositeUniqueIndexForMigrationBaseline() {
        jdbcTemplate.execute("""
                CREATE INDEX %s
                ON seat (concert_time_id)
                """.formatted(SEAT_MIGRATION_SUPPORT_INDEX));
        jdbcTemplate.execute("DROP INDEX %s ON seat".formatted(SEAT_COMPOSITE_UNIQUE_INDEX));
    }

    private boolean hasSeatCompositeUniqueIndex() {
        return jdbcTemplate.queryForList("SHOW INDEX FROM seat").stream()
                .anyMatch(row -> SEAT_COMPOSITE_UNIQUE_INDEX.equals(row.get("Key_name")));
    }

    private void assertSqlFailure(DataAccessException failure, String sqlState, int errorCode) {
        assertThat(failure).isNotNull();
        SQLException sqlException = findSqlException(failure);
        assertNotNull(sqlException);
        assertThat(sqlException.getSQLState()).isEqualTo(sqlState);
        assertThat(sqlException.getErrorCode()).isEqualTo(errorCode);
        System.out.printf(
                "SEAT_UNIQUE_CONSTRAINT_FAILURE sqlState=%s errorCode=%d message=%s%n",
                sqlException.getSQLState(),
                sqlException.getErrorCode(),
                sqlException.getMessage()
        );
    }

    private void assertReservationsMatchAttemptResults(List<LockAttemptResult> results) {
        for (int i = 0; i < results.size(); i++) {
            String username = USERNAME + "-opposite-" + i;
            List<Reservation> reservations = reservationRepository.findByUsername(username)
                    .orElseGet(List::of);

            if (results.get(i).success()) {
                assertThat(reservations).hasSize(2);
            } else {
                assertThat(reservations).isEmpty();
            }
        }
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

    private record LockAttemptResult(
            boolean success,
            String exceptionType,
            String message,
            String sqlState,
            Integer errorCode
    ) {
        static LockAttemptResult succeeded() {
            return new LockAttemptResult(true, null, null, null, null);
        }

        static LockAttemptResult failure(Throwable throwable) {
            SQLException sqlException = findSqlException(throwable);
            return new LockAttemptResult(
                    false,
                    throwable.getClass().getSimpleName(),
                    throwable.getMessage(),
                    sqlException == null ? null : sqlException.getSQLState(),
                    sqlException == null ? null : sqlException.getErrorCode()
            );
        }

    }

    private static SQLException findSqlException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                return sqlException;
            }
            current = current.getCause();
        }
        return null;
    }

    private record InventorySnapshot(int remainingSeats, long successfullyReservedSeats, long reservations) {
        boolean inventoryEquationHolds() {
            return TOTAL_SEATS == remainingSeats + successfullyReservedSeats;
        }
    }

    @TestConfiguration
    static class SeatRepositoryBarrierConfiguration {

        @Bean
        static BeanPostProcessor seatRepositoryBarrierBeanPostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof BookingRepository bookingRepository) {
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
                                        int lookupCount = IDEMPOTENCY_LOOKUP_COUNT.incrementAndGet();
                                        CyclicBarrier barrier = IDEMPOTENCY_LOOKUP_BARRIER.get();
                                        if (barrier != null && lookupCount <= 2) {
                                            barrier.await(5, TimeUnit.SECONDS);
                                        }
                                    }
                                    return result;
                                }
                        );
                    }
                    if (!(bean instanceof SeatRepository seatRepository)) {
                        return bean;
                    }

                    return Proxy.newProxyInstance(
                            SeatRepository.class.getClassLoader(),
                            new Class<?>[]{SeatRepository.class},
                            (proxy, method, args) -> {
                                if (method.getName().equals("findByConcertTimeIdAndSeatNumberWithLock")) {
                                    SEAT_LOCK_QUERY_COUNT.incrementAndGet();
                                    SEAT_LOCK_QUERY_ORDER
                                            .computeIfAbsent(Thread.currentThread().threadId(), ignored -> new CopyOnWriteArrayList<>())
                                            .add((String) args[1]);
                                    CyclicBarrier barrier = AGGREGATE_READ_BARRIER.get();
                                    if (barrier != null) {
                                        barrier.await(5, TimeUnit.SECONDS);
                                    }
                                }

                                Object result;
                                try {
                                    result = method.invoke(seatRepository, args);
                                } catch (InvocationTargetException exception) {
                                    throw exception.getCause();
                                }

                                if (method.getName().equals("findByConcertTimeIdAndSeatNumberWithLock")) {
                                    CyclicBarrier barrier = FIRST_SEAT_LOCK_BARRIER.get();
                                    if (barrier != null) {
                                        int lockCallCount = SEAT_LOCK_CALL_COUNT.get() + 1;
                                        SEAT_LOCK_CALL_COUNT.set(lockCallCount);
                                        if (lockCallCount != 1) {
                                            return result;
                                        }

                                        try {
                                            barrier.await(2, TimeUnit.SECONDS);
                                            BOTH_FIRST_SEAT_LOCKS_ACQUIRED.set(true);
                                        } catch (TimeoutException ignored) {
                                            // 첫 lock 조회 자체가 넓게 직렬화되면 transaction을 실패시키지 않고 계속 진행한다.
                                        } catch (java.util.concurrent.BrokenBarrierException ignored) {
                                            // 상대 transaction의 timeout 이후 도달한 경우에도 현재 동작 관찰을 계속한다.
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
