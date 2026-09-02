package com.onticket.concert.domain;

public enum CheckoutStatus {
    READY,
    PAYMENT_VERIFYING,
    PAYMENT_VERIFICATION_UNKNOWN,
    RESERVATION_CONFIRMED,
    EXPIRED
}
