package com.onticket.concert.dto;

import java.util.List;

public record SeatLayoutSummaryDto(
        Long concertTimeId,
        String layoutVersion,
        List<SeatSectionSummaryDto> sections
) {
}
