package com.onticket.concert.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class PaymentAlreadyUsedException extends RuntimeException {

    public PaymentAlreadyUsedException() {
        super("이미 예약에 사용된 결제입니다.");
    }
}
