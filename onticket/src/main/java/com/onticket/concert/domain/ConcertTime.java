package com.onticket.concert.domain;
import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

//****공연타임테이블****
@Getter
@Setter
@Entity
public class ConcertTime {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    //날짜
    private LocalDate date;

    //요일
    private String dayOfWeek;

    //공연시간
    private LocalTime startTime;

    //좌석수
    private int seatAmount;

    @ManyToOne
    @JoinColumn(name = "concertId")
    private Concert concert;

    @OneToMany(mappedBy = "concertTime", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Seat> seats;
}


