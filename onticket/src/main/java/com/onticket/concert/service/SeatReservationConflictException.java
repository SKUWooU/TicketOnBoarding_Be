package com.onticket.concert.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class SeatReservationConflictException extends RuntimeException {

    public SeatReservationConflictException(String message) {
        super(message);
    }
}
