package com.onticket.concert.service;

import com.onticket.concert.domain.Booking;
import com.onticket.concert.domain.Checkout;
import com.onticket.concert.domain.CheckoutStatus;
import com.onticket.concert.domain.Payment;
import com.onticket.concert.dto.VerifiedReservRequest;
import com.onticket.concert.repository.BookingRepository;
import com.onticket.concert.repository.CheckoutRepository;
import com.onticket.concert.repository.CheckoutSeatAssignmentRepository;
import com.onticket.concert.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Clock;
import java.time.temporal.ChronoUnit;

@RequiredArgsConstructor
@Service
public class VerifiedReservationTransactionService {

    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final CheckoutRepository checkoutRepository;
    private final CheckoutSeatAssignmentRepository checkoutSeatAssignmentRepository;
    private final SeatReservationService seatReservationService;
    private final Clock clock;

    @Transactional(rollbackFor = Exception.class)
    public LocalDateTime reserve(
            String username,
            String concertId,
            VerifiedReservRequest request,
            String idempotencyKey,
            String requestFingerprint,
            PaymentApproval approval
    ) throws Exception {
        Booking booking = new Booking();
        booking.setUsername(username);
        booking.setIdempotencyKey(idempotencyKey);
        booking.setRequestFingerprint(requestFingerprint);
        booking.setCreatedAt(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
        bookingRepository.saveAndFlush(booking);

        Payment payment = Payment.approved(
                approval.paymentId(),
                approval.username(),
                approval.approvedAmount(),
                approval.approvedAt(),
                booking
        );
        paymentRepository.saveAndFlush(payment);

        seatReservationService.reserveSeat(username, concertId, request, booking);
        payment.confirmReservation();
        return booking.getCreatedAt();
    }

    @Transactional(
            rollbackFor = Exception.class,
            noRollbackFor = PaymentVerificationUnknownException.class
    )
    public LocalDateTime reserveWithCheckout(
            String username,
            String concertId,
            VerifiedReservRequest request,
            String idempotencyKey,
            String checkoutFingerprint,
            String bookingFingerprint,
            PaymentApproval approval
    ) throws Exception {
        Checkout checkout = checkoutRepository.findByMerchantUidWithLock(request.getMerchantUid())
                .orElseThrow(() -> new InvalidCheckoutRequestException("결제 요청을 찾을 수 없습니다."));
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        if (checkout.isPaymentVerificationTimedOut(now)) {
            checkout.markPaymentVerificationUnknown();
            throw new PaymentVerificationUnknownException();
        }
        validateCheckout(checkout, username, concertId, request, checkoutFingerprint);

        if (checkout.getStatus() == CheckoutStatus.RESERVATION_CONFIRMED) {
            return existingCheckoutResult(checkout, username, idempotencyKey, bookingFingerprint);
        }
        if (checkout.getStatus() == CheckoutStatus.PAYMENT_VERIFICATION_UNKNOWN) {
            throw new PaymentVerificationUnknownException();
        }
        if (checkout.getStatus() != CheckoutStatus.PAYMENT_VERIFYING
                || !checkout.matchesPaymentVerification(
                request.getPaymentId(),
                idempotencyKey,
                bookingFingerprint
        )) {
            throw new CheckoutConflictException("결제 검증 claim과 예약 확정 요청이 일치하지 않습니다.");
        }

        Booking booking = new Booking();
        booking.setUsername(username);
        booking.setIdempotencyKey(idempotencyKey);
        booking.setRequestFingerprint(bookingFingerprint);
        booking.setCreatedAt(now);
        bookingRepository.saveAndFlush(booking);

        Payment payment = Payment.approved(
                approval.paymentId(),
                username,
                approval.approvedAmount(),
                approval.approvedAt(),
                booking
        );
        paymentRepository.saveAndFlush(payment);

        seatReservationService.reserveSeat(username, concertId, request, booking);
        payment.confirmReservation();
        checkoutSeatAssignmentRepository.findByCheckoutIdWithLock(checkout.getId())
                .forEach(assignment ->
                        assignment.clearVerificationLease(checkout.getVerificationDeadline()));
        checkout.confirmReservation(booking);
        return booking.getCreatedAt();
    }

    private void validateCheckout(
            Checkout checkout,
            String username,
            String concertId,
            VerifiedReservRequest request,
            String checkoutFingerprint
    ) {
        if (!checkout.getUsername().equals(username)) {
            throw new CheckoutConflictException("다른 사용자의 결제 요청입니다.");
        }
        if (!checkout.getConcertId().equals(concertId)
                || !checkout.getConcertTimeId().equals(request.getConcertTimeId())
                || !checkout.getRequestFingerprint().equals(checkoutFingerprint)) {
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
                && booking.getUsername().equals(username)
                && booking.getIdempotencyKey().equals(idempotencyKey)
                && booking.getRequestFingerprint().equals(bookingFingerprint)) {
            return booking.getCreatedAt();
        }
        throw new CheckoutConflictException("이미 다른 예약 요청에 사용된 결제 요청입니다.");
    }
}
