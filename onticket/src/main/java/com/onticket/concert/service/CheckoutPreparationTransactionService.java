package com.onticket.concert.service;

import com.onticket.concert.domain.Checkout;
import com.onticket.concert.domain.CheckoutRequestKey;
import com.onticket.concert.domain.ConcertTime;
import com.onticket.concert.domain.Seat;
import com.onticket.concert.dto.CheckoutRequest;
import com.onticket.concert.repository.CheckoutRepository;
import com.onticket.concert.repository.CheckoutRequestKeyRepository;
import com.onticket.concert.repository.ConcertTimeRepository;
import com.onticket.concert.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

@RequiredArgsConstructor
@Service
public class CheckoutPreparationTransactionService {

    private final CheckoutRepository checkoutRepository;
    private final CheckoutRequestKeyRepository checkoutRequestKeyRepository;
    private final ConcertTimeRepository concertTimeRepository;
    private final SeatRepository seatRepository;
    private final Clock clock;

    @Transactional(rollbackFor = Exception.class)
    public Checkout create(
            String merchantUid,
            String username,
            String idempotencyKey,
            String concertId,
            CheckoutRequest request,
            List<String> seatNumbers,
            String requestFingerprint,
            long expectedAmount
    ) {
        ConcertTime concertTime = concertTimeRepository.findById(request.getConcertTimeId())
                .orElseThrow(() -> new InvalidCheckoutRequestException("해당 공연 회차가 없습니다."));
        if (concertTime.getConcert() == null
                || !Objects.equals(concertId, concertTime.getConcert().getConcertId())) {
            throw new InvalidCheckoutRequestException("공연과 회차가 일치하지 않습니다.");
        }

        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        LocalDateTime earliestExpiry = null;
        for (String seatNumber : seatNumbers) {
            Seat seat = seatRepository.findByConcertTimeIdAndSeatNumberWithLock(
                            request.getConcertTimeId(),
                            seatNumber
                    )
                    .orElseThrow(() -> new InvalidCheckoutRequestException("존재하지 않는 좌석입니다."));
            seat.clearExpiredHold(now);
            if (seat.isReserved()) {
                throw new CheckoutConflictException("이미 예약된 좌석입니다.");
            }
            if (!seat.isHeldBy(username, now)) {
                throw new CheckoutConflictException("본인이 임시 점유한 좌석만 결제를 준비할 수 있습니다.");
            }
            if (earliestExpiry == null || seat.getHeldUntil().isBefore(earliestExpiry)) {
                earliestExpiry = seat.getHeldUntil();
            }
        }

        Checkout checkout = checkoutRepository
                .findByUsernameAndRequestFingerprintAndExpiresAt(
                        username,
                        requestFingerprint,
                        earliestExpiry
                )
                .orElse(null);
        if (checkout != null) {
            return checkout;
        }

        try {
            checkout = Checkout.ready(
                    merchantUid,
                    username,
                    idempotencyKey,
                    concertId,
                    request.getConcertTimeId(),
                    requestFingerprint,
                    expectedAmount,
                    now,
                    earliestExpiry
            );
            checkout = checkoutRepository.saveAndFlush(checkout);
            checkoutRequestKeyRepository.saveAndFlush(CheckoutRequestKey.bind(
                    username,
                    idempotencyKey,
                    requestFingerprint,
                    checkout
            ));
            return checkout;
        } catch (DataIntegrityViolationException exception) {
            throw new CheckoutHoldIdentityConflictException(
                    username,
                    requestFingerprint,
                    earliestExpiry,
                    exception
            );
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void bindRequestKey(
            Checkout checkout,
            String username,
            String idempotencyKey,
            String requestFingerprint
    ) {
        Checkout managedCheckout = checkoutRepository.findById(checkout.getId())
                .orElseThrow(() -> new InvalidCheckoutRequestException("결제 요청을 찾을 수 없습니다."));
        checkoutRequestKeyRepository.saveAndFlush(CheckoutRequestKey.bind(
                username,
                idempotencyKey,
                requestFingerprint,
                managedCheckout
        ));
    }
}
