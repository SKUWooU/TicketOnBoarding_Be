package com.onticket.concert.service;

import com.onticket.concert.domain.Checkout;
import com.onticket.concert.dto.CheckoutRequest;
import com.onticket.concert.dto.CheckoutResponse;
import com.onticket.concert.repository.CheckoutRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RequiredArgsConstructor
@Service
public class CheckoutService {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 100;
    private static final String MERCHANT_UID_PREFIX = "ticket_";

    private final CheckoutRepository checkoutRepository;
    private final CheckoutPreparationTransactionService transactionService;
    private final CheckoutExpirationService expirationService;
    private final VirtualTicketPricePolicy pricePolicy;
    private final Clock clock;

    public CheckoutResponse prepare(
            String username,
            String concertId,
            CheckoutRequest request,
            String idempotencyKey
    ) {
        validateUsername(username);
        validateIdempotencyKey(idempotencyKey);

        List<String> seatNumbers;
        String requestFingerprint;
        long expectedAmount;
        try {
            seatNumbers = ReservationRequestCanonicalizer.canonicalSeatNumbers(request);
            requestFingerprint = ReservationRequestCanonicalizer.fingerprint(concertId, request);
            expectedAmount = pricePolicy.expectedAmount(request);
        } catch (IllegalArgumentException exception) {
            throw new InvalidCheckoutRequestException(exception.getMessage());
        }

        Optional<Checkout> existing = checkoutRepository
                .findByUsernameAndIdempotencyKey(username, idempotencyKey);
        if (existing.isPresent()) {
            return resultForMatchingRequest(existing.get(), requestFingerprint);
        }

        try {
            Checkout created = transactionService.create(
                    newMerchantUid(),
                    username,
                    idempotencyKey,
                    concertId,
                    request,
                    seatNumbers,
                    requestFingerprint,
                    expectedAmount
            );
            return response(created);
        } catch (DataIntegrityViolationException exception) {
            Checkout concurrent = checkoutRepository
                    .findByUsernameAndIdempotencyKey(username, idempotencyKey)
                    .orElseThrow(() -> exception);
            return resultForMatchingRequest(concurrent, requestFingerprint);
        }
    }

    private CheckoutResponse resultForMatchingRequest(
            Checkout checkout,
            String requestFingerprint
    ) {
        if (!checkout.getRequestFingerprint().equals(requestFingerprint)) {
            throw new IdempotencyKeyConflictException();
        }

        LocalDateTime now = LocalDateTime.now(clock);
        if (checkout.expireIfNeeded(now)) {
            expirationService.expire(checkout.getMerchantUid(), now);
            throw new CheckoutExpiredException();
        }
        return response(checkout);
    }

    private CheckoutResponse response(Checkout checkout) {
        return new CheckoutResponse(
                checkout.getMerchantUid(),
                checkout.getExpectedAmount(),
                checkout.getExpiresAt(),
                checkout.getStatus()
        );
    }

    private String newMerchantUid() {
        return MERCHANT_UID_PREFIX + UUID.randomUUID();
    }

    private void validateUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new InvalidCheckoutRequestException("결제 요청 사용자가 필요합니다.");
        }
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new InvalidIdempotencyKeyException("결제 요청에는 멱등 키가 필요합니다.");
        }
        if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new InvalidIdempotencyKeyException("멱등 키는 100자를 초과할 수 없습니다.");
        }
    }
}
