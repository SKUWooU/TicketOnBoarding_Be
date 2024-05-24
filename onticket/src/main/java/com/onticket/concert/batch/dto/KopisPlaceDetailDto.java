package com.onticket.concert.batch.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Getter
@NoArgsConstructor
@ToString
public class KopisPlaceDetailDto {
    @JsonProperty("fcltynm")
    private String placeName;
    @JsonProperty("mt10id")
    private String placeId;
    @JsonProperty("mt13cnt")
    private String count;
    @JsonProperty("fcltychartr")
    private String fcltychartr;
    @JsonProperty("opende")
    private String opende;
    @JsonProperty("seatscale")
    private String seatscale;
    @JsonProperty("telno")
    private String telno;
    @JsonProperty("relateurl")
    private String relateurl;
    @JsonProperty("adres")
    private String addr;
    @JsonProperty("la")
    private String latitude;
    @JsonProperty("lo")
    private String longitude;
}
