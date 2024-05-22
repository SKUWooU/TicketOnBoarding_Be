package com.onticket.concert.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import lombok.*;
import java.time.LocalDate;



//****공연테이블****
@Getter
@Setter
@Entity
public class Concert {


    //공연 아이디
    @Id
    private String concertId;

    //공연이름
    private String concertName;

    //시작일
    @Temporal(TemporalType.DATE)
    private LocalDate startDate;

    //종료일
    @Temporal(TemporalType.DATE)
    private LocalDate endDate;

    //시설명
    private String place;

    //메인포스터 url
    private String posterurl;
//    //지역명
//    private String region;
    //장르
    private String genre;

    //공연상태
    private String status;

    //좌석수
    private int seatAmount;



}
