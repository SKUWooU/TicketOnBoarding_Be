package com.onticket.concert.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SeatDto {
    private Long id;

    // 좌석 번호
    private String seatNumber;

    // 예약 상태
    private boolean reserved;
}
