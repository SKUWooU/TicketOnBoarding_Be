package com.onticket.concert.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidSeatHoldRequestException extends RuntimeException {

    public InvalidSeatHoldRequestException(String message) {
        super(message);
    }
}
