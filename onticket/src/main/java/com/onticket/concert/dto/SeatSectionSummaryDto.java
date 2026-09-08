package com.onticket.concert.dto;

public record SeatSectionSummaryDto(
        String sectionCode,
        String sectionName,
        int sectionOrder,
        long totalSeats,
        long availableSeats,
        long heldSeats,
        long reservedSeats
) {
}
