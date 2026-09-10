package com.onticket.concert.service;

import java.time.LocalDateTime;
import java.util.Objects;

public record PaymentReconciliationResult(
        PaymentReconciliationOutcome outcome,
        LocalDateTime reservationCreatedAt
) {

    public PaymentReconciliationResult {
        Objects.requireNonNull(outcome, "결제 대조 결과가 필요합니다.");
        if (outcome == PaymentReconciliationOutcome.RESERVATION_CONFIRMED
                && reservationCreatedAt == null) {
            throw new IllegalArgumentException("예약 확정 결과에는 생성 시각이 필요합니다.");
        }
        if (outcome != PaymentReconciliationOutcome.RESERVATION_CONFIRMED
                && reservationCreatedAt != null) {
            throw new IllegalArgumentException("예약 확정 외 결과에는 생성 시각을 포함할 수 없습니다.");
        }
    }

    public static PaymentReconciliationResult reservationConfirmed(LocalDateTime createdAt) {
        return new PaymentReconciliationResult(
                PaymentReconciliationOutcome.RESERVATION_CONFIRMED,
                Objects.requireNonNull(createdAt, "예약 생성 시각이 필요합니다.")
        );
    }

    public static PaymentReconciliationResult paymentRejected() {
        return new PaymentReconciliationResult(PaymentReconciliationOutcome.PAYMENT_REJECTED, null);
    }

    public static PaymentReconciliationResult stillUnresolved() {
        return new PaymentReconciliationResult(PaymentReconciliationOutcome.STILL_UNRESOLVED, null);
    }

    public static PaymentReconciliationResult compensationRequired() {
        return new PaymentReconciliationResult(PaymentReconciliationOutcome.COMPENSATION_REQUIRED, null);
    }

    public static PaymentReconciliationResult manualReviewRequired() {
        return new PaymentReconciliationResult(PaymentReconciliationOutcome.MANUAL_REVIEW_REQUIRED, null);
    }

    public static PaymentReconciliationResult noActionRequired() {
        return new PaymentReconciliationResult(PaymentReconciliationOutcome.NO_ACTION_REQUIRED, null);
    }
}
