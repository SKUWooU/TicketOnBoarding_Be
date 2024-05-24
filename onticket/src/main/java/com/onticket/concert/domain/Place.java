package com.onticket.concert.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Getter
@Setter
public class Place {

    //시설id
    @Id
    private String placeId;

    //시설명
    private String placeName;

    //시.도
    private String sido;

    //구.군
    private String gugun;

    //주소
    private String addr;

    // 위도
    @Column(precision = 10, scale = 7)
    private BigDecimal latitude;

    // 경도
    @Column(precision = 10, scale = 7)
    private BigDecimal longitude;
}
