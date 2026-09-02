package com.onticket.concert.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class PaymentVerificationUnknownException extends RuntimeException {

    public PaymentVerificationUnknownException() {
        super("결제 검증 결과를 확인해야 하는 Checkout입니다.");
    }
}
