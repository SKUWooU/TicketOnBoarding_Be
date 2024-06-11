package com.onticket.concert.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.List;

@Setter
@Getter
@NoArgsConstructor
@ToString
public class ReservRequest {

    @JsonProperty("concertDate")
    private String concertDate;

    @JsonProperty("concertTimeId")
    private Long concertTimeId;

    @JsonProperty("concertTime")
    private String concertTime;

    @JsonProperty("seatNumberList")
    private List<String> seatNumberList;
}
