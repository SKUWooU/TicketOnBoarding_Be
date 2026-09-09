package com.onticket.concert.service;

public interface PaymentReconciliationPort {

    PaymentReconciliationSnapshot lookup(String paymentId);
}
