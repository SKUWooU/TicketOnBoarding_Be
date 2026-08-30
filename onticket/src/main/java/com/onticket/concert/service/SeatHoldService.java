package com.onticket.concert.service;

import com.onticket.concert.domain.ConcertTime;
import com.onticket.concert.domain.Seat;
import com.onticket.concert.dto.SeatHoldRequest;
import com.onticket.concert.dto.SeatHoldResponse;
import com.onticket.concert.repository.ConcertTimeRepository;
import com.onticket.concert.repository.SeatRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
public class SeatHoldService {

    private final SeatRepository seatRepository;
    private final ConcertTimeRepository concertTimeRepository;
    private final Clock clock;
    private final Duration holdDuration;

    public SeatHoldService(
            SeatRepository seatRepository,
            ConcertTimeRepository concertTimeRepository,
            Clock clock,
            @Value("${onticket.ticket.seat-hold-duration:PT5M}") Duration holdDuration
    ) {
        this.seatRepository = seatRepository;
        this.concertTimeRepository = concertTimeRepository;
        this.clock = clock;
        if (holdDuration == null || holdDuration.isZero() || holdDuration.isNegative()) {
            throw new IllegalArgumentException("좌석 점유 시간은 0보다 커야 합니다.");
        }
        this.holdDuration = holdDuration;
    }

    @Transactional(rollbackFor = Exception.class)
    public SeatHoldResponse hold(String username, String concertId, SeatHoldRequest request) {
        validateUsername(username);
        validateConcertTime(concertId, request);
        List<String> seatNumbers = canonicalSeatNumbers(request);
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime requestedExpiry = now.plus(holdDuration);
        List<SeatHoldResponse.HeldSeat> heldSeats = new ArrayList<>();

        for (String seatNumber : seatNumbers) {
            Seat seat = lockedSeat(request.getConcertTimeId(), seatNumber);
            seat.clearExpiredHold(now);
            if (seat.isReserved()) {
                throw new SeatHoldConflictException("이미 예약된 좌석입니다.");
            }
            if (seat.isHeldAt(now)) {
                if (!seat.isHeldBy(username, now)) {
                    throw new SeatHoldConflictException("다른 사용자가 임시 점유한 좌석입니다.");
                }
                heldSeats.add(new SeatHoldResponse.HeldSeat(seatNumber, seat.getHeldUntil()));
                continue;
            }
            seat.holdFor(username, now, requestedExpiry);
            heldSeats.add(new SeatHoldResponse.HeldSeat(seatNumber, requestedExpiry));
        }

        return new SeatHoldResponse(List.copyOf(heldSeats));
    }

    @Transactional(rollbackFor = Exception.class)
    public void release(String username, String concertId, SeatHoldRequest request) {
        validateUsername(username);
        validateConcertTime(concertId, request);
        List<String> seatNumbers = canonicalSeatNumbers(request);
        LocalDateTime now = LocalDateTime.now(clock);

        for (String seatNumber : seatNumbers) {
            Seat seat = lockedSeat(request.getConcertTimeId(), seatNumber);
            seat.clearExpiredHold(now);
            if (!seat.isHeldAt(now)) {
                continue;
            }
            if (!seat.isHeldBy(username, now)) {
                throw new SeatHoldConflictException("다른 사용자의 임시 점유는 해제할 수 없습니다.");
            }
            seat.clearHold();
        }
    }

    private void validateConcertTime(String concertId, SeatHoldRequest request) {
        if (concertId == null || concertId.isBlank()) {
            throw new InvalidSeatHoldRequestException("공연 ID가 필요합니다.");
        }
        if (request == null || request.getConcertTimeId() == null) {
            throw new InvalidSeatHoldRequestException("공연 회차가 필요합니다.");
        }
        ConcertTime concertTime = concertTimeRepository.findById(request.getConcertTimeId())
                .orElseThrow(() -> new InvalidSeatHoldRequestException("해당 공연 회차가 없습니다."));
        if (concertTime.getConcert() == null
                || !Objects.equals(concertId, concertTime.getConcert().getConcertId())) {
            throw new InvalidSeatHoldRequestException("공연과 회차가 일치하지 않습니다.");
        }
    }

    private Seat lockedSeat(Long concertTimeId, String seatNumber) {
        return seatRepository.findByConcertTimeIdAndSeatNumberWithLock(concertTimeId, seatNumber)
                .orElseThrow(() -> new InvalidSeatHoldRequestException("존재하지 않는 좌석입니다."));
    }

    private void validateUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new InvalidSeatHoldRequestException("좌석 점유자가 필요합니다.");
        }
    }

    private List<String> canonicalSeatNumbers(SeatHoldRequest request) {
        try {
            return ReservationRequestCanonicalizer.canonicalSeatNumbers(request.getSeatNumberList());
        } catch (IllegalArgumentException exception) {
            throw new InvalidSeatHoldRequestException(exception.getMessage());
        }
    }
}
