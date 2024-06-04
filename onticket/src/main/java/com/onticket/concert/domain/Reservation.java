package com.onticket.concert.domain;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.*;

@Getter
@Setter
@Entity
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String concertId;

    // 예약자 ID
    private String username;

    // 예약 시간
    private LocalDateTime createdAt;

    //공연일자
    private LocalDate concertDate;

    // 공연 시간
    private LocalTime concertTime;

//    @ManyToOne
//    @JoinColumn(name = "concertTimeId")
//    private ConcertTime concertTime;

    // 좌석
    @ManyToOne
    @JoinColumn(name = "seatId")
    private Seat seat;

    // 예약 상태
    private String status;
}