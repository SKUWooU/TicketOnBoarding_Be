package com.onticket.concert.service;

import com.onticket.concert.domain.Checkout;
import com.onticket.concert.domain.CheckoutSeatAssignment;
import com.onticket.concert.domain.CheckoutStatus;
import com.onticket.concert.domain.Seat;
import com.onticket.concert.dto.CheckoutResponse;
import com.onticket.concert.repository.CheckoutRepository;
import com.onticket.concert.repository.CheckoutSeatAssignmentRepository;
import com.onticket.concert.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
public class CheckoutCancellationService {

    private final CheckoutRepository checkoutRepository;
    private final CheckoutSeatAssignmentRepository assignmentRepository;
    private final SeatRepository seatRepository;
    private final Clock clock;

    @Transactional(
            rollbackFor = Exception.class,
            noRollbackFor = CheckoutExpiredException.class
    )
    public CheckoutResponse cancel(
            String username,
            String concertId,
            String merchantUid
    ) {
        validateIdentifiers(username, concertId, merchantUid);
        Checkout checkout = checkoutRepository.findByMerchantUidWithLock(merchantUid)
                .orElseThrow(() -> new InvalidCheckoutRequestException("결제 요청을 찾을 수 없습니다."));
        validateOwnerAndConcert(checkout, username, concertId);

        if (checkout.getStatus() == CheckoutStatus.CANCELED) {
            return response(checkout);
        }

        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        if (checkout.expireIfNeeded(now)) {
            throw new CheckoutExpiredException();
        }
        if (checkout.getStatus() != CheckoutStatus.READY) {
            throw new CheckoutConflictException("현재 Checkout 상태에서는 결제 요청을 취소할 수 없습니다.");
        }

        List<CheckoutSeatAssignment> snapshot = assignmentRepository
                .findByCheckoutIdOrderBySeatId(checkout.getId());
        if (snapshot.isEmpty()) {
            throw new CheckoutConflictException("Checkout 좌석 귀속 정보를 찾을 수 없습니다.");
        }
        List<String> canonicalSeatNumbers = ReservationRequestCanonicalizer.canonicalSeatNumbers(
                snapshot.stream().map(assignment -> assignment.getSeat().getSeatNumber()).toList()
        );
        List<Seat> lockedSeats = canonicalSeatNumbers.stream()
                .map(seatNumber -> seatRepository.findByConcertTimeIdAndSeatNumberWithLock(
                                checkout.getConcertTimeId(),
                                seatNumber
                        )
                        .orElseThrow(() -> new CheckoutConflictException(
                                "Checkout 좌석 귀속 정보를 찾을 수 없습니다."
                        )))
                .toList();
        List<CheckoutSeatAssignment> lockedAssignments = assignmentRepository
                .findByCheckoutIdWithLock(checkout.getId());
        validateLockedState(checkout, username, now, snapshot, lockedSeats, lockedAssignments);

        checkout.cancel(now);
        lockedAssignments.forEach(assignment -> assignment.release(now));
        lockedSeats.forEach(Seat::clearHold);
        return response(checkout);
    }

    private void validateLockedState(
            Checkout checkout,
            String username,
            LocalDateTime now,
            List<CheckoutSeatAssignment> snapshot,
            List<Seat> lockedSeats,
            List<CheckoutSeatAssignment> lockedAssignments
    ) {
        Set<Long> expectedSeatIds = snapshot.stream()
                .map(assignment -> assignment.getSeat().getId())
                .collect(Collectors.toSet());
        Set<Long> lockedSeatIds = lockedSeats.stream()
                .map(Seat::getId)
                .collect(Collectors.toSet());
        Set<Long> assignedSeatIds = lockedAssignments.stream()
                .map(assignment -> assignment.getSeat().getId())
                .collect(Collectors.toSet());
        if (!lockedSeatIds.equals(expectedSeatIds)
                || !assignedSeatIds.equals(expectedSeatIds)
                || lockedAssignments.stream().anyMatch(assignment ->
                assignment.getReleasedAt() != null
                        || !Objects.equals(assignment.getCheckout().getId(), checkout.getId())
                        || !assignment.effectiveActiveUntil().isAfter(now))) {
            throw new CheckoutConflictException("Checkout 좌석 귀속 정보가 유효하지 않습니다.");
        }

        Set<Long> checkoutTimeIds = lockedSeats.stream()
                .map(seat -> seat.getConcertTime().getId())
                .collect(Collectors.toSet());
        if (!checkoutTimeIds.equals(Set.of(checkout.getConcertTimeId()))
                || lockedSeats.stream().anyMatch(seat ->
                seat.isReserved() || !seat.isHeldBy(username, now))) {
            throw new CheckoutConflictException("Checkout 좌석의 임시 점유가 유효하지 않습니다.");
        }
    }

    private void validateIdentifiers(String username, String concertId, String merchantUid) {
        if (username == null || username.isBlank()) {
            throw new InvalidCheckoutRequestException("결제 요청 사용자가 필요합니다.");
        }
        if (concertId == null || concertId.isBlank()) {
            throw new InvalidCheckoutRequestException("공연 ID가 필요합니다.");
        }
        if (merchantUid == null || merchantUid.isBlank()) {
            throw new InvalidCheckoutRequestException("고객사 주문 식별자가 필요합니다.");
        }
    }

    private void validateOwnerAndConcert(
            Checkout checkout,
            String username,
            String concertId
    ) {
        if (!checkout.getUsername().equals(username)) {
            throw new CheckoutConflictException("다른 사용자의 결제 요청입니다.");
        }
        if (!checkout.getConcertId().equals(concertId)) {
            throw new CheckoutConflictException("공연과 결제 요청이 일치하지 않습니다.");
        }
    }

    private CheckoutResponse response(Checkout checkout) {
        return new CheckoutResponse(
                checkout.getMerchantUid(),
                checkout.getExpectedAmount(),
                checkout.getExpiresAt(),
                checkout.getStatus()
        );
    }
}
