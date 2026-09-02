package com.onticket.concert.service;

import java.time.LocalDateTime;

record CheckoutPaymentVerificationClaim(
        long expectedAmount,
        LocalDateTime existingReservationCreatedAt
) {

    static CheckoutPaymentVerificationClaim claimed(long expectedAmount) {
        return new CheckoutPaymentVerificationClaim(expectedAmount, null);
    }

    static CheckoutPaymentVerificationClaim completed(LocalDateTime createdAt) {
        return new CheckoutPaymentVerificationClaim(0, createdAt);
    }

    boolean isCompleted() {
        return existingReservationCreatedAt != null;
    }
}
