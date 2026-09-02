package com.onticket.concert.service;

import com.onticket.concert.domain.Checkout;
import com.onticket.concert.domain.CheckoutRequestKey;
import com.onticket.concert.dto.CheckoutRequest;
import com.onticket.concert.dto.CheckoutResponse;
import com.onticket.concert.repository.CheckoutRepository;
import com.onticket.concert.repository.CheckoutRequestKeyRepository;
import com.onticket.concert.repository.CheckoutSeatAssignmentRepository;
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
    private final CheckoutRequestKeyRepository checkoutRequestKeyRepository;
    private final CheckoutSeatAssignmentRepository checkoutSeatAssignmentRepository;
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

        Optional<CheckoutRequestKey> existingRequestKey = checkoutRequestKeyRepository
                .findByUsernameAndIdempotencyKey(username, idempotencyKey);
        if (existingRequestKey.isPresent()) {
            return resultForMatchingRequest(
                    existingRequestKey.get().getCheckout(),
                    requestFingerprint
            );
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
            return bindPreparedRequestKey(
                    created,
                    username,
                    idempotencyKey,
                    requestFingerprint
            );
        } catch (CheckoutSeatAssignmentConflictException exception) {
            if (exception.getReusableCheckoutId() != null) {
                Checkout concurrent = checkoutRepository.findById(exception.getReusableCheckoutId())
                        .orElseThrow(() -> new CheckoutConflictException(
                                "활성 결제 요청에 이미 귀속된 좌석입니다."
                        ));
                return bindPreparedRequestKey(
                        concurrent,
                        username,
                        idempotencyKey,
                        requestFingerprint
                );
            }
            throw new CheckoutConflictException("활성 결제 요청에 이미 귀속된 좌석입니다.");
        } catch (CheckoutHoldIdentityConflictException exception) {
            Optional<CheckoutRequestKey> concurrentRequestKey = checkoutRequestKeyRepository
                    .findByUsernameAndIdempotencyKey(username, idempotencyKey);
            if (concurrentRequestKey.isPresent()) {
                return resultForMatchingRequest(
                        concurrentRequestKey.get().getCheckout(),
                        requestFingerprint
                );
            }
            Optional<Checkout> concurrentHoldCheckout = checkoutRepository
                    .findByUsernameAndRequestFingerprintAndExpiresAt(
                            exception.getUsername(),
                            exception.getRequestFingerprint(),
                            exception.getExpiresAt()
                    );
            if (concurrentHoldCheckout.isPresent()) {
                return bindPreparedRequestKey(
                        concurrentHoldCheckout.get(),
                        username,
                        idempotencyKey,
                        requestFingerprint
                );
            }
            Optional<Checkout> concurrentIdempotentCheckout = checkoutRepository
                    .findByUsernameAndIdempotencyKey(username, idempotencyKey);
            if (concurrentIdempotentCheckout.isPresent()) {
                return resultForMatchingRequest(
                        concurrentIdempotentCheckout.get(),
                        requestFingerprint
                );
            }
            if (checkoutSeatAssignmentRepository.existsActiveBySeatIds(
                    exception.getSeatIds(),
                    exception.getCheckedAt()
            )) {
                throw new CheckoutConflictException("활성 결제 요청에 이미 귀속된 좌석입니다.");
            }
            throw exception.getDataIntegrityViolation();
        } catch (DataIntegrityViolationException exception) {
            Checkout concurrent = checkoutRepository
                    .findByUsernameAndIdempotencyKey(username, idempotencyKey)
                    .orElseThrow(() -> exception);
            return resultForMatchingRequest(concurrent, requestFingerprint);
        }
    }

    private CheckoutResponse bindPreparedRequestKey(
            Checkout checkout,
            String username,
            String idempotencyKey,
            String requestFingerprint
    ) {
        Optional<CheckoutRequestKey> existingRequestKey = checkoutRequestKeyRepository
                .findByUsernameAndIdempotencyKey(username, idempotencyKey);
        if (existingRequestKey.isPresent()) {
            return resultForMatchingRequest(
                    existingRequestKey.get().getCheckout(),
                    requestFingerprint
            );
        }
        try {
            transactionService.bindRequestKey(
                    checkout,
                    username,
                    idempotencyKey,
                    requestFingerprint
            );
            CheckoutRequestKey boundRequestKey = checkoutRequestKeyRepository
                    .findByUsernameAndIdempotencyKey(username, idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("결제 요청 키 귀속 결과를 찾을 수 없습니다."));
            return resultForMatchingRequest(boundRequestKey.getCheckout(), requestFingerprint);
        } catch (DataIntegrityViolationException exception) {
            CheckoutRequestKey concurrentRequestKey = checkoutRequestKeyRepository
                    .findByUsernameAndIdempotencyKey(username, idempotencyKey)
                    .orElseThrow(() -> exception);
            return resultForMatchingRequest(
                    concurrentRequestKey.getCheckout(),
                    requestFingerprint
            );
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
