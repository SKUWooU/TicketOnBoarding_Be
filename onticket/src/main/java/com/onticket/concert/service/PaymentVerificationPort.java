package com.onticket.concert.service;

public interface PaymentVerificationPort {

    PaymentApproval verify(String paymentId);
}
