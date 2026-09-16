package com.onticket.loadtest;

import com.onticket.concert.domain.Concert;
import com.onticket.concert.domain.ConcertDetail;
import com.onticket.concert.domain.ConcertTime;
import com.onticket.concert.domain.Place;
import com.onticket.concert.domain.Seat;
import com.onticket.concert.repository.BookingRepository;
import com.onticket.concert.repository.ConcertDetailRepository;
import com.onticket.concert.repository.ConcertRepository;
import com.onticket.concert.repository.ConcertTimeRepository;
import com.onticket.concert.repository.PaymentRepository;
import com.onticket.concert.repository.PlaceRepository;
import com.onticket.concert.repository.ReservationRepository;
import com.onticket.concert.repository.SeatRepository;
import com.onticket.concert.service.VirtualSeatLayoutFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
@Profile("loadtest")
public class LoadTestFixtureService {

    private static final Pattern RUN_ID_PATTERN = Pattern.compile("[A-Za-z0-9-]{1,32}");
    private static final String CONCERT_ID_PREFIX = "LOAD-TEST-";
    public static final String USERNAME_PREFIX = "load-user-";
    public static final String IDEMPOTENCY_KEY_PREFIX = "lt-";

    private final ConcertRepository concertRepository;
    private final ConcertDetailRepository concertDetailRepository;
    private final ConcertTimeRepository concertTimeRepository;
    private final PlaceRepository placeRepository;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final Clock clock;
    private final VirtualSeatLayoutFactory seatLayoutFactory;
    private final int rows;
    private final int seatsPerRow;

    public LoadTestFixtureService(
            ConcertRepository concertRepository,
            ConcertDetailRepository concertDetailRepository,
            ConcertTimeRepository concertTimeRepository,
            PlaceRepository placeRepository,
            SeatRepository seatRepository,
            ReservationRepository reservationRepository,
            BookingRepository bookingRepository,
            PaymentRepository paymentRepository,
            Clock clock,
            VirtualSeatLayoutFactory seatLayoutFactory,
            @Value("${onticket.loadtest.fixture.rows:50}") int rows,
            @Value("${onticket.loadtest.fixture.seats-per-row:40}") int seatsPerRow
    ) {
        if (rows <= 0 || seatsPerRow <= 0 || Math.multiplyExact(rows, seatsPerRow) > 10_000) {
            throw new IllegalArgumentException("loadtest fixture 좌석 수는 1~10,000 범위여야 합니다.");
        }
        this.concertRepository = concertRepository;
        this.concertDetailRepository = concertDetailRepository;
        this.concertTimeRepository = concertTimeRepository;
        this.placeRepository = placeRepository;
        this.seatRepository = seatRepository;
        this.reservationRepository = reservationRepository;
        this.bookingRepository = bookingRepository;
        this.paymentRepository = paymentRepository;
        this.clock = clock;
        this.seatLayoutFactory = seatLayoutFactory;
        this.rows = rows;
        this.seatsPerRow = seatsPerRow;
    }

    @Transactional
    public FixtureMetadata initialize(String runId) {
        String normalizedRunId = validateRunId(runId);
        String concertId = concertId(normalizedRunId);
        List<Long> existingTimeIds = concertTimeRepository.findConcertTimeIdsByConcertId(concertId);
        if (!existingTimeIds.isEmpty()) {
            repairDetailPlace(normalizedRunId, concertId);
            return metadata(normalizedRunId, existingTimeIds.get(0));
        }

        Place place = createOrGetPlace(normalizedRunId);

        Concert concert = new Concert();
        concert.setConcertId(concertId);
        concert.setConcertName("가상 고경합 부하 공연 " + normalizedRunId);
        concert.setPosterUrl("https://example.invalid/loadtest-poster.jpg");
        concert.setStartDate(LocalDate.of(2030, 1, 1));
        concert.setEndDate(LocalDate.of(2030, 1, 31));
        concert.setGenre("가상 부하 테스트");

        ConcertDetail detail = new ConcertDetail();
        detail.setConcertId(concertId);
        detail.setConcert(concert);
        detail.setPlace("가상 부하 공연장");
        detail.setPlaceId(place.getPlaceId());
        detail.setAge("전체 관람가");
        detail.setPrice("가상 좌석 30,000원");
        detail.setStartTime("19:00");
        detail.setPerformers("가상 fixture");
        detail.setCrew("가상 fixture");
        detail.setCompany("가상 fixture");
        concert.setConcertDetail(detail);
        concertRepository.saveAndFlush(concert);

        ConcertTime concertTime = new ConcertTime();
        concertTime.setConcert(concert);
        concertTime.setDate(LocalDate.of(2030, 1, 10));
        concertTime.setDayOfWeek("THURSDAY");
        concertTime.setStartTime(LocalTime.of(19, 0));
        concertTime.setSeatAmount(totalSeats());
        VirtualSeatLayoutFactory.LayoutDefinition layout =
                seatLayoutFactory.loadTestLayout(rows, seatsPerRow);
        concertTime.setSeatLayoutVersion(layout.version());
        concertTime = concertTimeRepository.saveAndFlush(concertTime);

        List<Seat> seats = new ArrayList<>(totalSeats());
        for (VirtualSeatLayoutFactory.SeatPosition position : layout.positions()) {
            Seat seat = new Seat();
            seat.setSeatNumber(position.seatNumber());
            seat.setReserved(false);
            seat.setConcertTime(concertTime);
            seat.assignLayout(
                    position.sectionCode(), position.sectionName(), position.sectionOrder(),
                    position.rowLabel(), position.rowOrder(), position.seatIndex()
            );
            seats.add(seat);
        }
        seatRepository.saveAllAndFlush(seats);
        return metadata(normalizedRunId, concertTime.getId());
    }

    private Place createOrGetPlace(String runId) {
        String placeId = placeId(runId);
        return placeRepository.findById(placeId).orElseGet(() -> {
            Place place = new Place();
            place.setPlaceId(placeId);
            place.setPlaceName("가상 부하 공연장");
            place.setSido("가상시");
            place.setGugun("부하구");
            place.setAddr("가상시 부하구 " + runId + " fixture");
            place.setLatitude(new BigDecimal("37.5665000"));
            place.setLongitude(new BigDecimal("126.9780000"));
            return placeRepository.save(place);
        });
    }

    private void repairDetailPlace(String runId, String concertId) {
        ConcertDetail detail = concertDetailRepository.findById(concertId)
                .orElseThrow(() -> new IllegalStateException("loadtest fixture 상세 정보가 없습니다."));
        String placeId = createOrGetPlace(runId).getPlaceId();
        if (!placeId.equals(detail.getPlaceId())) {
            detail.setPlaceId(placeId);
            concertDetailRepository.save(detail);
        }
    }

    @Transactional(readOnly = true)
    public FixtureMetadata metadata(String runId) {
        String normalizedRunId = validateRunId(runId);
        List<Long> timeIds = concertTimeRepository.findConcertTimeIdsByConcertId(concertId(normalizedRunId));
        if (timeIds.size() != 1) {
            throw new IllegalStateException("loadtest fixture가 초기화되지 않았습니다.");
        }
        return metadata(normalizedRunId, timeIds.get(0));
    }

    @Transactional(readOnly = true)
    public InventorySnapshot snapshot(String runId) {
        FixtureMetadata fixture = metadata(runId);
        long seatCount = seatRepository.countByConcertTimeId(fixture.concertTimeId());
        long reservedSeats = seatRepository.countByConcertTimeIdAndReservedTrue(fixture.concertTimeId());
        int remainingSeats = concertTimeRepository.findById(fixture.concertTimeId())
                .orElseThrow()
                .getSeatAmount();
        long reservations = reservationRepository.countByConcertTimeId(fixture.concertTimeId());
        long bookings = bookingRepository.countByIdempotencyKeyStartingWith(idempotencyKeyPrefix(fixture.runId()));
        long payments = paymentRepository.countByProviderPaymentIdStartingWith(paymentIdPrefix(fixture.runId()));
        return new InventorySnapshot(
                fixture.totalSeats(),
                seatCount,
                remainingSeats,
                reservedSeats,
                reservations,
                bookings,
                payments,
                fixture.totalSeats() == remainingSeats + reservedSeats
                        && reservedSeats == reservations
                        && bookings == payments
        );
    }

    @Transactional
    public SeatHoldSnapshot resetSeatHolds(String runId) {
        FixtureMetadata fixture = metadata(runId);
        seatRepository.clearHoldsByConcertTimeId(fixture.concertTimeId());
        return seatHoldSnapshot(fixture);
    }

    @Transactional(readOnly = true)
    public SeatHoldSnapshot seatHoldSnapshot(String runId) {
        return seatHoldSnapshot(metadata(runId));
    }

    private SeatHoldSnapshot seatHoldSnapshot(FixtureMetadata fixture) {
        long seatCount = seatRepository.countByConcertTimeId(fixture.concertTimeId());
        long reservedSeats = seatRepository.countByConcertTimeIdAndReservedTrue(fixture.concertTimeId());
        long holdRows = seatRepository.countHoldRows(fixture.concertTimeId());
        long activeHeldSeats = seatRepository.countActiveHolds(
                fixture.concertTimeId(),
                LocalDateTime.now(clock)
        );
        long partialHoldStates = seatRepository.countPartialHoldStates(fixture.concertTimeId());
        int remainingSeats = concertTimeRepository.findById(fixture.concertTimeId())
                .orElseThrow()
                .getSeatAmount();
        long reservations = reservationRepository.countByConcertTimeId(fixture.concertTimeId());
        long bookings = bookingRepository.countByIdempotencyKeyStartingWith(idempotencyKeyPrefix(fixture.runId()));
        long payments = paymentRepository.countByProviderPaymentIdStartingWith(paymentIdPrefix(fixture.runId()));
        boolean invariantSatisfied = seatCount == fixture.totalSeats()
                && remainingSeats == fixture.totalSeats()
                && reservedSeats == 0
                && reservations == 0
                && bookings == 0
                && payments == 0
                && partialHoldStates == 0
                && activeHeldSeats == holdRows;
        return new SeatHoldSnapshot(
                fixture.totalSeats(),
                seatCount,
                remainingSeats,
                reservedSeats,
                activeHeldSeats,
                holdRows,
                partialHoldStates,
                reservations,
                bookings,
                payments,
                invariantSatisfied
        );
    }

    static String seatNumber(int row, int number) {
        return VirtualSeatLayoutFactory.loadTestSeatNumber(row, number);
    }

    static String usernamePrefix(String runId) {
        return USERNAME_PREFIX + validateRunId(runId) + ".";
    }

    static String idempotencyKeyPrefix(String runId) {
        return IDEMPOTENCY_KEY_PREFIX + validateRunId(runId) + ".";
    }

    static String paymentIdPrefix(String runId) {
        return "LT:" + usernamePrefix(runId);
    }

    private FixtureMetadata metadata(String runId, Long concertTimeId) {
        return new FixtureMetadata(runId, concertId(runId), concertTimeId, rows, seatsPerRow, totalSeats());
    }

    private int totalSeats() {
        return Math.multiplyExact(rows, seatsPerRow);
    }

    private static String concertId(String runId) {
        return CONCERT_ID_PREFIX + validateRunId(runId);
    }

    private static String placeId(String runId) {
        return CONCERT_ID_PREFIX + "PLACE-" + validateRunId(runId);
    }

    private static String validateRunId(String runId) {
        if (runId == null || !RUN_ID_PATTERN.matcher(runId).matches()) {
            throw new InvalidLoadTestRunIdException();
        }
        return runId;
    }

    public record FixtureMetadata(
            String runId,
            String concertId,
            Long concertTimeId,
            int rows,
            int seatsPerRow,
            int totalSeats
    ) {
    }

    public record InventorySnapshot(
            int expectedTotalSeats,
            long actualSeatCount,
            int remainingSeats,
            long reservedSeats,
            long reservations,
            long bookings,
            long payments,
            boolean invariantSatisfied
    ) {
    }

    public record SeatHoldSnapshot(
            int expectedTotalSeats,
            long actualSeatCount,
            int remainingSeats,
            long reservedSeats,
            long activeHeldSeats,
            long holdRows,
            long partialHoldStates,
            long reservations,
            long bookings,
            long payments,
            boolean invariantSatisfied
    ) {
    }
}
