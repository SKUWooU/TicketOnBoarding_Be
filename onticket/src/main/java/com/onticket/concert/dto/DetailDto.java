package com.onticket.concert.dto;

import com.onticket.concert.domain.Review;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

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
    private String crew;
    private String company;
    private String performers;
    private String genre;
    private String posterUrl;
    private String addr;
    private String startDate;
    private String endDate;
    private BigDecimal la;
    private BigDecimal lo;
    private List<Review> reviewList;

}
