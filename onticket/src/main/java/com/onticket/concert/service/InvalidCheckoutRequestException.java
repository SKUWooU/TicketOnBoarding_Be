package com.onticket.concert.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidCheckoutRequestException extends RuntimeException {

    public InvalidCheckoutRequestException(String message) {
        super(message);
    }
}
