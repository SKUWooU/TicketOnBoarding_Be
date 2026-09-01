package com.onticket.concert.service;

import com.onticket.concert.domain.Booking;
import com.onticket.concert.domain.Checkout;
import com.onticket.concert.domain.CheckoutStatus;
import com.onticket.concert.dto.VerifiedReservRequest;
import com.onticket.concert.repository.BookingRepository;
import com.onticket.concert.repository.CheckoutRepository;
import com.onticket.concert.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

@RequiredArgsConstructor
@Service
public class CheckoutVerifiedReservationService {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 100;
    private static final int MAX_PROVIDER_IDENTIFIER_LENGTH = 100;

    private final BookingRepository bookingRepository;
    private final CheckoutRepository checkoutRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentVerificationPort paymentVerificationPort;
    private final VerifiedReservationTransactionService transactionService;
    private final CheckoutExpirationService expirationService;
    private final Clock clock;

    public LocalDateTime reserve(
            String username,
            String concertId,
            VerifiedReservRequest request,
            String idempotencyKey
    ) throws Exception {
        validateRequiredValues(username, request, idempotencyKey);

        String checkoutFingerprint;
        String bookingFingerprint;
        try {
            checkoutFingerprint = ReservationRequestCanonicalizer.fingerprint(concertId, request);
            bookingFingerprint = ReservationRequestCanonicalizer.checkoutVerifiedFingerprint(
                    concertId,
                    request,
                    request.getPaymentId(),
                    request.getMerchantUid()
            );
        } catch (IllegalArgumentException exception) {
            throw new InvalidCheckoutRequestException(exception.getMessage());
        }
        validateProviderIdentifiers(request);

        Optional<Booking> existingBooking = bookingRepository
                .findByUsernameAndIdempotencyKey(username, idempotencyKey);
        if (existingBooking.isPresent()) {
            return resultForMatchingBooking(existingBooking.get(), bookingFingerprint);
        }

        Checkout checkout = checkoutRepository.findByMerchantUid(request.getMerchantUid())
                .orElseThrow(() -> new InvalidCheckoutRequestException("결제 요청을 찾을 수 없습니다."));
        validateCheckoutBeforeVerification(
                checkout,
                username,
                concertId,
                request,
                checkoutFingerprint
        );

        PaymentApproval approval = paymentVerificationPort.verify(request.getPaymentId());
        validateApproval(request, checkout, approval);

        try {
            return transactionService.reserveWithCheckout(
                    username,
                    concertId,
                    request,
                    idempotencyKey,
                    checkoutFingerprint,
                    bookingFingerprint,
                    approval
            );
        } catch (DataIntegrityViolationException exception) {
            Optional<Booking> concurrentBooking = bookingRepository
                    .findByUsernameAndIdempotencyKey(username, idempotencyKey);
            if (concurrentBooking.isPresent()) {
                return resultForMatchingBooking(concurrentBooking.get(), bookingFingerprint);
            }
            if (paymentRepository.existsByProviderPaymentId(request.getPaymentId())) {
                throw new PaymentAlreadyUsedException();
            }
            throw exception;
        }
    }

    private void validateCheckoutBeforeVerification(
            Checkout checkout,
            String username,
            String concertId,
            VerifiedReservRequest request,
            String checkoutFingerprint
    ) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (checkout.expireIfNeeded(now)) {
            expirationService.expire(checkout.getMerchantUid(), now);
            throw new CheckoutExpiredException();
        }
        if (checkout.getStatus() != CheckoutStatus.READY) {
            throw new CheckoutConflictException("이미 사용되었거나 사용할 수 없는 결제 요청입니다.");
        }
        if (!checkout.getUsername().equals(username)) {
            throw new CheckoutConflictException("다른 사용자의 결제 요청입니다.");
        }
        if (!checkout.getConcertId().equals(concertId)
                || !checkout.getConcertTimeId().equals(request.getConcertTimeId())
                || !checkout.getRequestFingerprint().equals(checkoutFingerprint)) {
            throw new CheckoutConflictException("결제 요청과 예약 payload가 일치하지 않습니다.");
        }
    }

    private void validateApproval(
            VerifiedReservRequest request,
            Checkout checkout,
            PaymentApproval approval
    ) {
        if (approval == null || !approval.approved()) {
            throw new InvalidPaymentException("승인된 결제가 아닙니다.");
        }
        if (!request.getPaymentId().equals(approval.paymentId())) {
            throw new InvalidPaymentException("결제 식별자가 일치하지 않습니다.");
        }
        if (!request.getMerchantUid().equals(approval.merchantUid())) {
            throw new InvalidPaymentException("고객사 주문 식별자가 일치하지 않습니다.");
        }
        if (approval.approvedAmount() != checkout.getExpectedAmount()) {
            throw new InvalidPaymentException("서버 주문 금액과 승인 금액이 일치하지 않습니다.");
        }
        if (approval.approvedAt() == null) {
            throw new InvalidPaymentException("결제 승인 시각이 필요합니다.");
        }
    }

    private LocalDateTime resultForMatchingBooking(
            Booking booking,
            String bookingFingerprint
    ) {
        if (!Objects.equals(booking.getRequestFingerprint(), bookingFingerprint)) {
            throw new IdempotencyKeyConflictException();
        }
        return booking.getCreatedAt();
    }

    private void validateRequiredValues(
            String username,
            VerifiedReservRequest request,
            String idempotencyKey
    ) {
        if (username == null || username.isBlank()) {
            throw new InvalidPaymentException("예약자 정보가 필요합니다.");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new InvalidIdempotencyKeyException("Checkout 예약에는 멱등 키가 필요합니다.");
        }
        if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new InvalidIdempotencyKeyException("멱등 키는 100자를 초과할 수 없습니다.");
        }
        if (request == null) {
            throw new InvalidCheckoutRequestException("예약 요청이 필요합니다.");
        }
    }

    private void validateProviderIdentifiers(VerifiedReservRequest request) {
        validateProviderIdentifier(request.getPaymentId(), "결제 식별자가 필요합니다.");
        validateProviderIdentifier(request.getMerchantUid(), "고객사 주문 식별자가 필요합니다.");
    }

    private void validateProviderIdentifier(String value, String missingMessage) {
        if (value == null || value.isBlank()) {
            throw new InvalidPaymentException(missingMessage);
        }
        if (value.length() > MAX_PROVIDER_IDENTIFIER_LENGTH) {
            throw new InvalidPaymentException("결제 식별자는 100자를 초과할 수 없습니다.");
        }
    }
}
