package com.onticket.concert.batch.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.onticket.concert.batch.deserializer.StyUrlsDeserializer;
import java.util.List;

//소개 포스터에 대한 객체
@JsonDeserialize(using = StyUrlsDeserializer.class)
public class StyUrls {
    private List<String> styUrl;

    public List<String> getStyUrl() {
        return styUrl;
    }

    public void setStyUrl(List<String> styUrl) {
        this.styUrl = styUrl;
    }
}
