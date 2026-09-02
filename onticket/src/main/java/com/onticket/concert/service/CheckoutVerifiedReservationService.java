package com.onticket.concert.service;

import com.onticket.concert.domain.Booking;
import com.onticket.concert.dto.VerifiedReservRequest;
import com.onticket.concert.repository.BookingRepository;
import com.onticket.concert.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@RequiredArgsConstructor
@Service
public class CheckoutVerifiedReservationService {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 100;
    private static final int MAX_PROVIDER_IDENTIFIER_LENGTH = 100;

    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentVerificationPort paymentVerificationPort;
    private final CheckoutPaymentVerificationTransactionService verificationTransactionService;
    private final VerifiedReservationTransactionService transactionService;

    public LocalDateTime reserve(
            String username,
            String concertId,
            VerifiedReservRequest request,
            String idempotencyKey
    ) throws Exception {
        validateRequiredValues(username, request, idempotencyKey);

        String checkoutFingerprint;
        String bookingFingerprint;
        List<String> canonicalSeatNumbers;
        try {
            checkoutFingerprint = ReservationRequestCanonicalizer.fingerprint(concertId, request);
            bookingFingerprint = ReservationRequestCanonicalizer.checkoutVerifiedFingerprint(
                    concertId,
                    request,
                    request.getPaymentId(),
                    request.getMerchantUid()
            );
            canonicalSeatNumbers = ReservationRequestCanonicalizer.canonicalSeatNumbers(request);
        } catch (IllegalArgumentException exception) {
            throw new InvalidCheckoutRequestException(exception.getMessage());
        }
        validateProviderIdentifiers(request);

        Optional<Booking> existingBooking = bookingRepository
                .findByUsernameAndIdempotencyKey(username, idempotencyKey);
        if (existingBooking.isPresent()) {
            return resultForMatchingBooking(existingBooking.get(), bookingFingerprint);
        }

        CheckoutPaymentVerificationClaim claim = verificationTransactionService.claim(
                username,
                concertId,
                request,
                canonicalSeatNumbers,
                idempotencyKey,
                checkoutFingerprint,
                bookingFingerprint
        );
        if (claim.isCompleted()) {
            return claim.existingReservationCreatedAt();
        }

        PaymentApproval approval;
        try {
            approval = paymentVerificationPort.verify(request.getPaymentId());
        } catch (PaymentVerificationUnavailableException exception) {
            releaseKnownFailure(username, request, canonicalSeatNumbers, idempotencyKey, bookingFingerprint);
            throw exception;
        } catch (RuntimeException exception) {
            markUnknown(username, request, idempotencyKey, bookingFingerprint);
            throw exception;
        }

        if (approval == null || !approval.approved()) {
            releaseKnownFailure(username, request, canonicalSeatNumbers, idempotencyKey, bookingFingerprint);
            throw new InvalidPaymentException("승인된 결제가 아닙니다.");
        }
        try {
            validateApprovedPayment(request, claim.expectedAmount(), approval);
        } catch (InvalidPaymentException exception) {
            markUnknown(username, request, idempotencyKey, bookingFingerprint);
            throw exception;
        }

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
            markUnknown(username, request, idempotencyKey, bookingFingerprint);
            if (paymentRepository.existsByProviderPaymentId(request.getPaymentId())) {
                throw new PaymentAlreadyUsedException();
            }
            throw exception;
        } catch (Exception exception) {
            markUnknown(username, request, idempotencyKey, bookingFingerprint);
            throw exception;
        }
    }

    private void releaseKnownFailure(
            String username,
            VerifiedReservRequest request,
            List<String> canonicalSeatNumbers,
            String idempotencyKey,
            String bookingFingerprint
    ) {
        verificationTransactionService.releaseKnownFailure(
                request.getMerchantUid(), username, request.getConcertTimeId(),
                canonicalSeatNumbers, request.getPaymentId(), idempotencyKey, bookingFingerprint
        );
    }

    private void markUnknown(
            String username,
            VerifiedReservRequest request,
            String idempotencyKey,
            String bookingFingerprint
    ) {
        verificationTransactionService.markUnknown(
                request.getMerchantUid(), username, request.getPaymentId(),
                idempotencyKey, bookingFingerprint
        );
    }

    private void validateApprovedPayment(
            VerifiedReservRequest request,
            long expectedAmount,
            PaymentApproval approval
    ) {
        if (!request.getPaymentId().equals(approval.paymentId())) {
            throw new InvalidPaymentException("결제 식별자가 일치하지 않습니다.");
        }
        if (!request.getMerchantUid().equals(approval.merchantUid())) {
            throw new InvalidPaymentException("고객사 주문 식별자가 일치하지 않습니다.");
        }
        if (approval.approvedAmount() != expectedAmount) {
            throw new InvalidPaymentException("서버 주문 금액과 승인 금액이 일치하지 않습니다.");
        }
        if (approval.approvedAt() == null) {
            throw new InvalidPaymentException("결제 승인 시각이 필요합니다.");
        }
    }

    private LocalDateTime resultForMatchingBooking(Booking booking, String bookingFingerprint) {
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
