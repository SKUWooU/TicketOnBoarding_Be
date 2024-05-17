package com.onticket.concert.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;


import java.sql.Date;

@Getter
@NoArgsConstructor
@ToString
public class Concert {


    //공연 아이디
    @Id
    private String concertId;
    //시설명
    private String place;
    //지역명
    private String region;
    //공연이름
    private String concertName;
    //장르
    private String genre;
    //메인포스터 url
    private String posterurl;
    //시작일
    private Date startDate;
    //종료일
    private Date endDate;
    //좌석수
    private int seatAmount;



}
