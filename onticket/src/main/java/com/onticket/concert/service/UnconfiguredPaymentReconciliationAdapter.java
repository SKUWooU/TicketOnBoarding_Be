package com.onticket.concert.service;

public class UnconfiguredPaymentReconciliationAdapter implements PaymentReconciliationPort {

    @Override
    public PaymentReconciliationSnapshot lookup(String paymentId) {
        throw new PaymentVerificationUnavailableException();
    }
}
