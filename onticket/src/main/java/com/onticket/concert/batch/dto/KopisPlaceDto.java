package com.onticket.concert.batch.dto;


import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Getter
@NoArgsConstructor
@ToString
public class KopisPlaceDto {
    @JsonProperty("fcltynm")
    private String placeName;
    @JsonProperty("mt10id")
    private String placeId;
    @JsonProperty("mt13cn")
    private String count;
    @JsonProperty("fcltychartr")
    private String placeHall;
    @JsonProperty("sidonm")
    private String sido;
    @JsonProperty("gugunnm")
    private String gugun;
    @JsonProperty("opende")
    private String builtYear;
}
