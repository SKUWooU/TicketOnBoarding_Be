package com.onticket.concert.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.GONE)
public class CheckoutExpiredException extends RuntimeException {

    public CheckoutExpiredException() {
        super("결제 요청과 좌석 임시 점유가 만료되었습니다.");
    }
}
