package com.onticket.concert.domain;
import com.fasterxml.jackson.annotation.JsonBackReference;
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

    private String concertName;

    private String posterUrl;
    // 예약자 ID
    private String username;

    // 예약 시간
    private LocalDateTime createdAt;

    //공연일자
    private LocalDate concertDate;

    // 공연 시간
    private LocalTime concertTime;

    private Long concertTimeId;

//    @ManyToOne
//    @JoinColumn(name = "concertTimeId")
//    private ConcertTime concertTime;

    // 좌석
    @ManyToOne
    @JoinColumn(name = "seatId")
    @JsonBackReference
    private Seat seat;

    private String seatNumber;
    // 예약 상태
    private String status; //결제완료, 취소신정, 취소완료
}