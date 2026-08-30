package com.onticket.concert.dto;

import com.onticket.concert.domain.SeatAvailability;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SeatDto {
    private Long seatId;

    // 좌석 번호
    private String seatNumber;

    // 예약 상태
    private boolean reserved;

    private SeatAvailability availability;

    private java.time.LocalDateTime holdExpiresAt;
}
