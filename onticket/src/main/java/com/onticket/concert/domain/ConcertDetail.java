package com.onticket.concert.domain;


import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
public class ConcertDetail {

    @Id
    private String concertId;

    private String concertName;

    private String place;

    private String age;

    private String performers;

    private String price;

    private String startTime;

    private String crew;

    private String runtime;

    private String company;

    private String genre;

    private String status;

    private String placeId;

    @OneToOne
    @MapsId // concertId를 Concert의 concertId와 매핑
    @JoinColumn(name = "concertId")
    private Concert concert;


    @OneToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "styUrlsId", referencedColumnName = "id")
    private StyUrls styUrls;

}
