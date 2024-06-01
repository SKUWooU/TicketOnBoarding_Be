package com.onticket.concert.dto;

import com.onticket.concert.domain.Seat;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CalDto {
    private Long id;

    //날짜
    private LocalDate date;

    //요일
    private String dayOfWeek;

    //공연시간
    private LocalTime startTime;

    //좌석수
    private int seatAmount;

    private List<Seat> seatList;
}
