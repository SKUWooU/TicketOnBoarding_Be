package com.onticket.concert.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class SeatLayoutNotFoundException extends RuntimeException {
    public SeatLayoutNotFoundException(String message) {
        super(message);
    }
}
