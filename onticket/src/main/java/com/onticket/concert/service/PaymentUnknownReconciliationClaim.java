package com.onticket.concert.service;

import java.time.LocalDateTime;
import java.util.List;

record PaymentUnknownReconciliationClaim(
        String merchantUid,
        String username,
        String concertId,
        Long concertTimeId,
        String checkoutFingerprint,
        long expectedAmount,
        String paymentId,
        String reservationIdempotencyKey,
        String bookingFingerprint,
        LocalDateTime verificationDeadline,
        List<String> seatNumbers
) {
}
