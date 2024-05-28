package com.onticket.concert.domain;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 좌석 번호
    private String seatNumber;

    // 예약 상태
    private boolean reserved;

    @ManyToOne
    @JoinColumn(name = "concertTimeId")
    private ConcertTime concertTime;
}