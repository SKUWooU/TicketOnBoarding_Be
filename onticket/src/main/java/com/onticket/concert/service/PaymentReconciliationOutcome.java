package com.onticket.concert.service;

public enum PaymentReconciliationOutcome {
    RESERVATION_CONFIRMED,
    PAYMENT_REJECTED,
    STILL_UNRESOLVED,
    COMPENSATION_REQUIRED,
    MANUAL_REVIEW_REQUIRED,
    NO_ACTION_REQUIRED
}
