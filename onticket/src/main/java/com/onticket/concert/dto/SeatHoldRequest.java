package com.onticket.concert.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class SeatHoldRequest {

    private Long concertTimeId;

    private List<String> seatNumberList;
}
