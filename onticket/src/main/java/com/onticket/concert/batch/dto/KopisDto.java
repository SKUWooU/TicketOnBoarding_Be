package com.onticket.concert.batch.dto;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Getter
@NoArgsConstructor
@ToString
public class KopisDto {

    //공연 아이디
    @JsonProperty("mt20id")
    private String concertId;

    //공연이름
    @JsonProperty("prfnm")
    private String concertName;

    //시작일
    @JsonProperty("prfpdfrom")
    private String startDate;

    //종료일
    @JsonProperty("prfpdto")
    private String endDate;

    //시설명
    @JsonProperty("fcltynm")
    private String place;

    //메인포스터 url
    @JsonProperty("poster")
    private String posterUrl;

    //장르
    @JsonProperty("genrenm")
    private String genre;

    //오픈런
    @JsonProperty("openrun")
    private String openRun;

    //상태
    @JsonProperty("prfstate")
    private String status;

}
