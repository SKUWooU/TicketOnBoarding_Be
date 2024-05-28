package com.onticket.concert.domain;


import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@Entity
public class ConcertDetail {

    @Id
    private String concertId;

    private String place;

    private String age;

    private String performers;

    private String price;

    private String startTime;

    private String crew;

    private String runtime;

    private String company;

    private String placeId;

    private float averageRating;
    @OneToOne
    @MapsId // concertId를 Concert의 concertId와 매핑
    @JoinColumn(name = "concertId")
    @JsonBackReference
    private Concert concert;

    @OneToMany(mappedBy = "concertDetail", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonManagedReference
    private List<Review> reviews;

}
//    @OneToOne(cascade = CascadeType.ALL)
//    @JoinColumn(name = "styUrlsId", referencedColumnName = "id")
//    private StyUrls styUrls;
