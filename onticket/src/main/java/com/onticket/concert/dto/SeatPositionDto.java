package com.onticket.concert.dto;

import com.onticket.concert.domain.SeatAvailability;

import java.time.LocalDateTime;

public record SeatPositionDto(
        Long seatId,
        String seatNumber,
        String rowLabel,
        int rowOrder,
        int seatIndex,
        SeatAvailability availability,
        LocalDateTime holdExpiresAt
) {
}
