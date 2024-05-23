package com.onticket.concert.batch.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.onticket.concert.batch.deserializer.StyUrlsDeserializer;
import java.util.List;

//소개 포스터에 대한 객체
@JsonDeserialize(using = StyUrlsDeserializer.class)
public class StyUrlsDto {
    private List<String> styUrl;

    public List<String> getStyUrlDto() {
        return styUrl;
    }

    public void setStyUrlDto(List<String> styUrl) {
        this.styUrl = styUrl;
    }
}
