package com.onticket.concert.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class CheckoutConflictException extends RuntimeException {

    public CheckoutConflictException(String message) {
        super(message);
    }
}
