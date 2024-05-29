package com.onticket.concert.dto;


import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MainDto {
    private String concertID;
    private String concertName;
    private String startDate;
    private String endDate;
    private String sido;
    private String gugun;
    private String price;
    private String posterUrl;
    private String placename;
    private float averageRating;
}
