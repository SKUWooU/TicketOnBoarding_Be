package com.onticket.concert.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DetailDto {
    private String concertId;
    private String concertName;
    private String placeName;
    private String startTime;
    private String age;
    private String price;
    private String cast;
    private String crew;
    private String company;
    private String performers;
    private String genre;
    private BigDecimal la;
    private BigDecimal lo;
}
