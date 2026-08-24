package com.onticket.concert.service;

public class UnconfiguredPaymentVerificationAdapter implements PaymentVerificationPort {

    @Override
    public PaymentApproval verify(String paymentId) {
        throw new PaymentVerificationUnavailableException();
    }
}
