package com.onticket.concert.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@AllArgsConstructor
public class SeatHoldResponse {

    private final List<HeldSeat> seats;

    @Getter
    @AllArgsConstructor
    public static class HeldSeat {

        private final String seatNumber;
        private final LocalDateTime expiresAt;
    }
}
