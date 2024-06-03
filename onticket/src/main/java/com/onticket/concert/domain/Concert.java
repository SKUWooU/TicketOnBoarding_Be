package com.onticket.concert.domain;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.util.List;


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

    //메인포스터 url
    private String posterUrl;
//    //지역명
//    private String region;
    //장르
    private String genre;

    //공연상태
    private String status;

    //MDsPick
    private int onTicketPick;


    @OneToOne(mappedBy = "concert", cascade = CascadeType.ALL)
    @JsonManagedReference
    private ConcertDetail concertDetail;

}
