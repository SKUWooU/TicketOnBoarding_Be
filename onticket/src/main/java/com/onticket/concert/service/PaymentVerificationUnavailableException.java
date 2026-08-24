package com.onticket.concert.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class PaymentVerificationUnavailableException extends RuntimeException {

    public PaymentVerificationUnavailableException() {
        super("결제 검증 어댑터가 구성되지 않았습니다.");
    }
}
