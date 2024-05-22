package com.onticket.concert.batch.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Getter
@Component
public class KopisApi {

    @Value("${spring.kopis.url}")
    private String kopisapiurl;


    public String getKopisapiurl() {
        return kopisapiurl;
    }


    //KOPIS API KEY
    @Value("${spring.kopis.apiKey}")
    private String kopisapikey;

    public String getKopisapikey() {
        return kopisapikey;
    }

}
