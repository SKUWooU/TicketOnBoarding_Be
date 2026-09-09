package com.onticket.concert.service;

record PaymentUnknownReconciliationPreparation(
        PaymentUnknownReconciliationClaim claim,
        PaymentReconciliationResult existingResult
) {

    static PaymentUnknownReconciliationPreparation claimed(
            PaymentUnknownReconciliationClaim claim
    ) {
        return new PaymentUnknownReconciliationPreparation(claim, null);
    }

    static PaymentUnknownReconciliationPreparation completed(
            PaymentReconciliationResult result
    ) {
        return new PaymentUnknownReconciliationPreparation(null, result);
    }

    boolean isCompleted() {
        return existingResult != null;
    }
}
