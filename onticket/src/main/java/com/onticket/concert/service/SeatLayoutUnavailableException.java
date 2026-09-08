package com.onticket.concert.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class SeatLayoutUnavailableException extends RuntimeException {
    public SeatLayoutUnavailableException(String message) {
        super(message);
    }
}
