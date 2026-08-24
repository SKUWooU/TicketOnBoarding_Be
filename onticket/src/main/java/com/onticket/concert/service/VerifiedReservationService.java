package com.onticket.concert.service;

import com.onticket.concert.domain.Booking;
import com.onticket.concert.dto.VerifiedReservRequest;
import com.onticket.concert.repository.BookingRepository;
import com.onticket.concert.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

@RequiredArgsConstructor
@Service
public class VerifiedReservationService {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 100;
    private static final int MAX_PAYMENT_ID_LENGTH = 100;

    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentVerificationPort paymentVerificationPort;
    private final VirtualTicketPricePolicy pricePolicy;
    private final VerifiedReservationTransactionService transactionService;

    public LocalDateTime reserve(
            String username,
            String concertId,
            VerifiedReservRequest request,
            String idempotencyKey
    ) throws Exception {
        if (username == null || username.isBlank()) {
            throw new InvalidPaymentException("예약자 정보가 필요합니다.");
        }
        validateIdempotencyKey(idempotencyKey);
        validatePaymentId(request == null ? null : request.getPaymentId());
        String requestFingerprint = ReservationRequestCanonicalizer.verifiedFingerprint(
                concertId,
                request,
                request == null ? null : request.getPaymentId()
        );

        Optional<Booking> existingBooking = bookingRepository
                .findByUsernameAndIdempotencyKey(username, idempotencyKey);
        if (existingBooking.isPresent()) {
            return resultForMatchingRequest(existingBooking.get(), requestFingerprint);
        }

        long expectedAmount = pricePolicy.expectedAmount(request);
        PaymentApproval approval = paymentVerificationPort.verify(request.getPaymentId());
        validateApproval(username, request.getPaymentId(), expectedAmount, approval);

        try {
            return transactionService.reserve(
                    username,
                    concertId,
                    request,
                    idempotencyKey,
                    requestFingerprint,
                    approval
            );
        } catch (DataIntegrityViolationException exception) {
            Optional<Booking> concurrentBooking = bookingRepository
                    .findByUsernameAndIdempotencyKey(username, idempotencyKey);
            if (concurrentBooking.isPresent()) {
                return resultForMatchingRequest(concurrentBooking.get(), requestFingerprint);
            }
            if (paymentRepository.existsByProviderPaymentId(request.getPaymentId())) {
                throw new PaymentAlreadyUsedException();
            }
            throw exception;
        }
    }

    private void validateApproval(
            String username,
            String requestedPaymentId,
            long expectedAmount,
            PaymentApproval approval
    ) {
        if (approval == null || !approval.approved()) {
            throw new InvalidPaymentException("승인된 결제가 아닙니다.");
        }
        if (!requestedPaymentId.equals(approval.paymentId())) {
            throw new InvalidPaymentException("결제 식별자가 일치하지 않습니다.");
        }
        if (!Objects.equals(username, approval.username())) {
            throw new InvalidPaymentException("결제자와 예약자가 일치하지 않습니다.");
        }
        if (approval.approvedAmount() != expectedAmount) {
            throw new InvalidPaymentException("서버 주문 금액과 승인 금액이 일치하지 않습니다.");
        }
        if (approval.approvedAt() == null) {
            throw new InvalidPaymentException("결제 승인 시각이 필요합니다.");
        }
    }

    private LocalDateTime resultForMatchingRequest(Booking booking, String requestFingerprint) {
        if (!booking.getRequestFingerprint().equals(requestFingerprint)) {
            throw new IdempotencyKeyConflictException();
        }
        return booking.getCreatedAt();
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new InvalidIdempotencyKeyException("검증 예약에는 멱등 키가 필요합니다.");
        }
        if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new InvalidIdempotencyKeyException("멱등 키는 100자를 초과할 수 없습니다.");
        }
    }

    private void validatePaymentId(String paymentId) {
        if (paymentId == null || paymentId.isBlank()) {
            throw new InvalidPaymentException("결제 식별자가 필요합니다.");
        }
        if (paymentId.length() > MAX_PAYMENT_ID_LENGTH) {
            throw new InvalidPaymentException("결제 식별자는 100자를 초과할 수 없습니다.");
        }
    }
}
