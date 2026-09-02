package com.onticket.concert.service;

import com.onticket.concert.domain.Booking;
import com.onticket.concert.domain.Checkout;
import com.onticket.concert.domain.CheckoutSeatAssignment;
import com.onticket.concert.domain.CheckoutStatus;
import com.onticket.concert.domain.Seat;
import com.onticket.concert.dto.VerifiedReservRequest;
import com.onticket.concert.repository.CheckoutRepository;
import com.onticket.concert.repository.CheckoutSeatAssignmentRepository;
import com.onticket.concert.repository.SeatRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CheckoutPaymentVerificationTransactionService {

    private final CheckoutRepository checkoutRepository;
    private final CheckoutSeatAssignmentRepository assignmentRepository;
    private final SeatRepository seatRepository;
    private final Clock clock;
    private final Duration verificationGraceDuration;

    public CheckoutPaymentVerificationTransactionService(
            CheckoutRepository checkoutRepository,
            CheckoutSeatAssignmentRepository assignmentRepository,
            SeatRepository seatRepository,
            Clock clock,
            @Value("${onticket.ticket.checkout-payment-verification-grace-duration:PT30S}")
            Duration verificationGraceDuration
    ) {
        if (verificationGraceDuration == null
                || verificationGraceDuration.isZero()
                || verificationGraceDuration.isNegative()) {
            throw new IllegalArgumentException("결제 검증 유예 시간은 0보다 커야 합니다.");
        }
        this.checkoutRepository = checkoutRepository;
        this.assignmentRepository = assignmentRepository;
        this.seatRepository = seatRepository;
        this.clock = clock;
        this.verificationGraceDuration = verificationGraceDuration;
    }

    @Transactional(
            rollbackFor = Exception.class,
            noRollbackFor = {CheckoutExpiredException.class, PaymentVerificationUnknownException.class}
    )
    public CheckoutPaymentVerificationClaim claim(
            String username,
            String concertId,
            VerifiedReservRequest request,
            List<String> canonicalSeatNumbers,
            String reservationIdempotencyKey,
            String checkoutFingerprint,
            String bookingFingerprint
    ) {
        Checkout checkout = checkoutRepository.findByMerchantUidWithLock(request.getMerchantUid())
                .orElseThrow(() -> new InvalidCheckoutRequestException("결제 요청을 찾을 수 없습니다."));
        validateCheckout(checkout, username, concertId, request, checkoutFingerprint);

        if (checkout.getStatus() == CheckoutStatus.RESERVATION_CONFIRMED) {
            return CheckoutPaymentVerificationClaim.completed(existingCheckoutResult(
                    checkout,
                    username,
                    reservationIdempotencyKey,
                    bookingFingerprint
            ));
        }
        if (checkout.getStatus() == CheckoutStatus.PAYMENT_VERIFICATION_UNKNOWN) {
            throw new PaymentVerificationUnknownException();
        }

        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        if (checkout.getStatus() == CheckoutStatus.PAYMENT_VERIFYING) {
            if (checkout.isPaymentVerificationTimedOut(now)) {
                checkout.markPaymentVerificationUnknown();
                throw new PaymentVerificationUnknownException();
            }
            throw new CheckoutConflictException("결제 검증이 진행 중인 Checkout입니다.");
        }
        if (checkout.expireIfNeeded(now)) {
            throw new CheckoutExpiredException();
        }
        if (checkout.getStatus() != CheckoutStatus.READY) {
            throw new CheckoutConflictException("결제 검증을 시작할 수 없는 Checkout 상태입니다.");
        }

        List<CheckoutSeatAssignment> snapshot = assignmentRepository
                .findByCheckoutIdOrderBySeatId(checkout.getId());
        validateAssignments(checkout, snapshot, canonicalSeatNumbers, checkoutFingerprint);

        List<Seat> lockedSeats = lockSeats(request.getConcertTimeId(), canonicalSeatNumbers);
        for (Seat seat : lockedSeats) {
            if (seat.isReserved() || !seat.isHeldBy(username, now)) {
                throw new CheckoutConflictException("Checkout 좌석의 임시 점유가 유효하지 않습니다.");
            }
        }

        List<CheckoutSeatAssignment> lockedAssignments = assignmentRepository
                .findByCheckoutIdWithLock(checkout.getId());
        validateAssignments(checkout, lockedAssignments, canonicalSeatNumbers, checkoutFingerprint);

        LocalDateTime deadline = checkout.getExpiresAt().plus(verificationGraceDuration);
        checkout.beginPaymentVerification(
                request.getPaymentId(),
                reservationIdempotencyKey,
                bookingFingerprint,
                now,
                deadline
        );
        lockedAssignments.forEach(assignment ->
                assignment.beginVerificationLease(checkout.getExpiresAt(), deadline));
        lockedSeats.stream()
                .filter(seat -> seat.getHeldUntil().isBefore(deadline))
                .forEach(seat -> seat.extendOwnedHoldUntil(username, now, deadline));

        return CheckoutPaymentVerificationClaim.claimed(checkout.getExpectedAmount());
    }

    @Transactional(rollbackFor = Exception.class)
    public void releaseKnownFailure(
            String merchantUid,
            String username,
            Long concertTimeId,
            List<String> canonicalSeatNumbers,
            String paymentId,
            String reservationIdempotencyKey,
            String bookingFingerprint
    ) {
        Checkout checkout = checkoutRepository.findByMerchantUidWithLock(merchantUid)
                .orElse(null);
        if (!isMatchingActiveClaim(
                checkout,
                username,
                paymentId,
                reservationIdempotencyKey,
                bookingFingerprint
        )) {
            return;
        }

        List<Seat> lockedSeats = lockSeats(concertTimeId, canonicalSeatNumbers);
        List<CheckoutSeatAssignment> assignments = assignmentRepository
                .findByCheckoutIdWithLock(checkout.getId());
        LocalDateTime deadline = checkout.getVerificationDeadline();

        assignments.forEach(assignment -> {
            assignment.getSeat().restoreOwnedHoldUntil(
                    username,
                    deadline,
                    assignment.getOriginalHoldExpiresAt()
            );
            assignment.clearVerificationLease(deadline);
        });
        checkout.releasePaymentVerification(
                LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS)
        );
    }

    @Transactional(rollbackFor = Exception.class)
    public void markUnknown(
            String merchantUid,
            String username,
            String paymentId,
            String reservationIdempotencyKey,
            String bookingFingerprint
    ) {
        Checkout checkout = checkoutRepository.findByMerchantUidWithLock(merchantUid)
                .orElse(null);
        if (isMatchingActiveClaim(
                checkout,
                username,
                paymentId,
                reservationIdempotencyKey,
                bookingFingerprint
        )) {
            checkout.markPaymentVerificationUnknown();
        }
    }

    private List<Seat> lockSeats(Long concertTimeId, List<String> canonicalSeatNumbers) {
        return canonicalSeatNumbers.stream()
                .map(seatNumber -> seatRepository.findByConcertTimeIdAndSeatNumberWithLock(
                                concertTimeId,
                                seatNumber
                        )
                        .orElseThrow(() -> new InvalidCheckoutRequestException("존재하지 않는 좌석입니다.")))
                .toList();
    }

    private void validateAssignments(
            Checkout checkout,
            List<CheckoutSeatAssignment> assignments,
            List<String> canonicalSeatNumbers,
            String checkoutFingerprint
    ) {
        Set<String> assignedSeatNumbers = assignments.stream()
                .map(assignment -> assignment.getSeat().getSeatNumber())
                .collect(Collectors.toSet());
        if (assignments.size() != canonicalSeatNumbers.size()
                || !assignedSeatNumbers.equals(Set.copyOf(canonicalSeatNumbers))
                || assignments.stream().anyMatch(assignment ->
                !Objects.equals(assignment.getRequestFingerprint(), checkoutFingerprint)
                        || !Objects.equals(assignment.getActiveUntil(), checkout.getExpiresAt())
                        || assignment.getVerificationLeaseUntil() != null)) {
            throw new CheckoutConflictException("Checkout 좌석 귀속 정보가 결제 요청과 일치하지 않습니다.");
        }
    }

    private boolean isMatchingActiveClaim(
            Checkout checkout,
            String username,
            String paymentId,
            String reservationIdempotencyKey,
            String bookingFingerprint
    ) {
        return checkout != null
                && checkout.getStatus() == CheckoutStatus.PAYMENT_VERIFYING
                && Objects.equals(checkout.getUsername(), username)
                && checkout.matchesPaymentVerification(
                paymentId,
                reservationIdempotencyKey,
                bookingFingerprint
        );
    }

    private void validateCheckout(
            Checkout checkout,
            String username,
            String concertId,
            VerifiedReservRequest request,
            String checkoutFingerprint
    ) {
        if (!Objects.equals(checkout.getUsername(), username)) {
            throw new CheckoutConflictException("다른 사용자의 결제 요청입니다.");
        }
        if (!Objects.equals(checkout.getConcertId(), concertId)
                || !Objects.equals(checkout.getConcertTimeId(), request.getConcertTimeId())
                || !Objects.equals(checkout.getRequestFingerprint(), checkoutFingerprint)) {
            throw new CheckoutConflictException("결제 요청과 예약 payload가 일치하지 않습니다.");
        }
    }

    private LocalDateTime existingCheckoutResult(
            Checkout checkout,
            String username,
            String idempotencyKey,
            String bookingFingerprint
    ) {
        Booking booking = checkout.getBooking();
        if (booking != null
                && Objects.equals(booking.getUsername(), username)
                && Objects.equals(booking.getIdempotencyKey(), idempotencyKey)
                && Objects.equals(booking.getRequestFingerprint(), bookingFingerprint)) {
            return booking.getCreatedAt();
        }
        throw new CheckoutConflictException("이미 다른 예약 요청에 사용된 결제 요청입니다.");
    }
}
