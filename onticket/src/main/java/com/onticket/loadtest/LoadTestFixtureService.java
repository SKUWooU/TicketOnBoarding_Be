package com.onticket.loadtest;

import com.onticket.concert.domain.Concert;
import com.onticket.concert.domain.ConcertDetail;
import com.onticket.concert.domain.ConcertTime;
import com.onticket.concert.domain.Seat;
import com.onticket.concert.repository.BookingRepository;
import com.onticket.concert.repository.ConcertRepository;
import com.onticket.concert.repository.ConcertTimeRepository;
import com.onticket.concert.repository.PaymentRepository;
import com.onticket.concert.repository.ReservationRepository;
import com.onticket.concert.repository.SeatRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Service
@Profile("loadtest")
public class LoadTestFixtureService {

    public static final String CONCERT_ID = "LOAD-TEST-CONCERT";
    public static final String USERNAME_PREFIX = "load-user-";

    private final ConcertRepository concertRepository;
    private final ConcertTimeRepository concertTimeRepository;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final int rows;
    private final int seatsPerRow;

    public LoadTestFixtureService(
            ConcertRepository concertRepository,
            ConcertTimeRepository concertTimeRepository,
            SeatRepository seatRepository,
            ReservationRepository reservationRepository,
            BookingRepository bookingRepository,
            PaymentRepository paymentRepository,
            @Value("${onticket.loadtest.fixture.rows:50}") int rows,
            @Value("${onticket.loadtest.fixture.seats-per-row:40}") int seatsPerRow
    ) {
        if (rows <= 0 || seatsPerRow <= 0 || Math.multiplyExact(rows, seatsPerRow) > 10_000) {
            throw new IllegalArgumentException("loadtest fixture 좌석 수는 1~10,000 범위여야 합니다.");
        }
        this.concertRepository = concertRepository;
        this.concertTimeRepository = concertTimeRepository;
        this.seatRepository = seatRepository;
        this.reservationRepository = reservationRepository;
        this.bookingRepository = bookingRepository;
        this.paymentRepository = paymentRepository;
        this.rows = rows;
        this.seatsPerRow = seatsPerRow;
    }

    @Transactional
    public FixtureMetadata initialize() {
        List<Long> existingTimeIds = concertTimeRepository.findConcertTimeIdsByConcertId(CONCERT_ID);
        if (!existingTimeIds.isEmpty()) {
            return metadata(existingTimeIds.get(0));
        }

        Concert concert = new Concert();
        concert.setConcertId(CONCERT_ID);
        concert.setConcertName("가상 고경합 부하 공연");
        concert.setPosterUrl("https://example.invalid/loadtest-poster.jpg");
        concert.setStartDate(LocalDate.of(2030, 1, 1));
        concert.setEndDate(LocalDate.of(2030, 1, 31));

        ConcertDetail detail = new ConcertDetail();
        detail.setConcertId(CONCERT_ID);
        detail.setConcert(concert);
        detail.setPlace("가상 부하 공연장");
        detail.setPrice("가상 좌석 30,000원");
        concert.setConcertDetail(detail);
        concertRepository.saveAndFlush(concert);

        ConcertTime concertTime = new ConcertTime();
        concertTime.setConcert(concert);
        concertTime.setDate(LocalDate.of(2030, 1, 10));
        concertTime.setDayOfWeek("THURSDAY");
        concertTime.setStartTime(LocalTime.of(19, 0));
        concertTime.setSeatAmount(totalSeats());
        concertTime = concertTimeRepository.saveAndFlush(concertTime);

        List<Seat> seats = new ArrayList<>(totalSeats());
        for (int row = 1; row <= rows; row++) {
            for (int number = 1; number <= seatsPerRow; number++) {
                Seat seat = new Seat();
                seat.setSeatNumber(seatNumber(row, number));
                seat.setReserved(false);
                seat.setConcertTime(concertTime);
                seats.add(seat);
            }
        }
        seatRepository.saveAllAndFlush(seats);
        return metadata(concertTime.getId());
    }

    @Transactional(readOnly = true)
    public FixtureMetadata metadata() {
        List<Long> timeIds = concertTimeRepository.findConcertTimeIdsByConcertId(CONCERT_ID);
        if (timeIds.size() != 1) {
            throw new IllegalStateException("loadtest fixture가 초기화되지 않았습니다.");
        }
        return metadata(timeIds.get(0));
    }

    @Transactional(readOnly = true)
    public InventorySnapshot snapshot() {
        FixtureMetadata fixture = metadata();
        long seatCount = seatRepository.countByConcertTimeId(fixture.concertTimeId());
        long reservedSeats = seatRepository.countByConcertTimeIdAndReservedTrue(fixture.concertTimeId());
        int remainingSeats = concertTimeRepository.findById(fixture.concertTimeId())
                .orElseThrow()
                .getSeatAmount();
        long reservations = reservationRepository.countByConcertTimeId(fixture.concertTimeId());
        long bookings = bookingRepository.countByUsernameStartingWith(USERNAME_PREFIX);
        long payments = paymentRepository.countByUsernameStartingWith(USERNAME_PREFIX);
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

    static String seatNumber(int row, int number) {
        return "R%03d-S%03d".formatted(row, number);
    }

    private FixtureMetadata metadata(Long concertTimeId) {
        return new FixtureMetadata(CONCERT_ID, concertTimeId, rows, seatsPerRow, totalSeats());
    }

    private int totalSeats() {
        return Math.multiplyExact(rows, seatsPerRow);
    }

    public record FixtureMetadata(
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
}
