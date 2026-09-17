package com.onticket.concert.service;

import com.onticket.concert.domain.Checkout;
import com.onticket.concert.repository.CheckoutRepository;
import com.onticket.concert.repository.CheckoutSeatAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@RequiredArgsConstructor
@Service
public class CheckoutExpirationService {

    private final CheckoutRepository checkoutRepository;
    private final CheckoutSeatAssignmentRepository checkoutSeatAssignmentRepository;

    @Transactional
    public boolean expire(String merchantUid, LocalDateTime now) {
        Checkout checkout = checkoutRepository.findByMerchantUidWithLock(merchantUid)
                .orElseThrow(() -> new InvalidCheckoutRequestException("결제 요청을 찾을 수 없습니다."));
        boolean expired = checkout.expireIfNeeded(now);
        if (expired) {
            checkoutSeatAssignmentRepository.findByCheckoutIdWithLock(checkout.getId())
                    .forEach(assignment -> assignment.clearExpiredActiveSeat(now));
        }
        return expired;
    }
}
