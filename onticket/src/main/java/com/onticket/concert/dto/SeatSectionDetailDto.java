package com.onticket.concert.dto;

import java.util.List;

public record SeatSectionDetailDto(
        Long concertTimeId,
        String layoutVersion,
        String sectionCode,
        String sectionName,
        int sectionOrder,
        List<SeatPositionDto> seats
) {
}
