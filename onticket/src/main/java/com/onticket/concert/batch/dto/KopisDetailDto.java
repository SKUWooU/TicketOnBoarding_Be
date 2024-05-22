package com.onticket.concert.batch.dto;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import java.util.List;

@Getter
@NoArgsConstructor
@ToString
public class KopisDetailDto {
    //공연ID
    @JsonProperty("mt20id")
    private String concertId;

    //공연이름
    @JsonProperty("prfnm")
    private String concertName;

    //공연시작일
    @JsonProperty("prfpdfrom")
    private String startDate;

    //공연종료일
    @JsonProperty("prfpdto")
    private String endDate;

    //시설명
    @JsonProperty("fcltynm")
    private String place;

    //출연진
    @JsonProperty("prfcast")
    private String cast;

    //제작진
    @JsonProperty("prfcrew")
    private String crew;

    //런타임
    @JsonProperty("prfruntime")
    private String runtime;

    //관람연령
    @JsonProperty("prfage")
    private String age;

    //제작사
    @JsonProperty("entrpsnm")
    private String company;

    //가격
    @JsonProperty("pcseguidance")
    private String priceGuidance;

    //포스터url
    @JsonProperty("poster")
    private String posterUrl;

    //줄거리
    @JsonProperty("sty")
    private String story;

    //장르명
    @JsonProperty("genrenm")
    private String genre;

    //openrun 정보
    @JsonProperty("openrun")
    private String openRun;

    //공연상태
    @JsonProperty("prfstate")
    private String status;

    //시설id
    @JsonProperty("mt10id")
    private String facilityId;

    //공연시간정보
    @JsonProperty("dtguidance")
    private String dateGuidance;

    //api 응답이 배열또는 문자열
    @JsonProperty("styurls")
    private StyUrls styUrls;


}

