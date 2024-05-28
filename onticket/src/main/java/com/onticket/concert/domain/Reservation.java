package com.onticket.concert.domain;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 예약자 ID
    private String userId;

    // 예약 시간
    private LocalDateTime reservationTime;

    // 공연 시간
    @ManyToOne
    @JoinColumn(name = "concertTimeId")
    private ConcertTime concertTime;

    // 좌석
    @ManyToOne
    @JoinColumn(name = "seatId")
    private Seat seat;

    // 예약 상태
    private String status;
}