package com.onticket.concert.service;

import java.util.Objects;

public record PaymentReconciliationSnapshot(
        PaymentReconciliationStatus status,
        PaymentApproval approval
) {

    public PaymentReconciliationSnapshot {
        Objects.requireNonNull(status, "결제 대조 상태가 필요합니다.");
        if (status == PaymentReconciliationStatus.APPROVED && approval == null) {
            throw new IllegalArgumentException("승인 상태에는 승인 정보가 필요합니다.");
        }
        if (status != PaymentReconciliationStatus.APPROVED && approval != null) {
            throw new IllegalArgumentException("미승인 상태에는 승인 정보를 포함할 수 없습니다.");
        }
    }

    public static PaymentReconciliationSnapshot approved(PaymentApproval approval) {
        return new PaymentReconciliationSnapshot(
                PaymentReconciliationStatus.APPROVED,
                Objects.requireNonNull(approval, "승인 정보가 필요합니다.")
        );
    }

    public static PaymentReconciliationSnapshot rejected() {
        return new PaymentReconciliationSnapshot(PaymentReconciliationStatus.REJECTED, null);
    }

    public static PaymentReconciliationSnapshot unresolved() {
        return new PaymentReconciliationSnapshot(PaymentReconciliationStatus.UNRESOLVED, null);
    }
}
