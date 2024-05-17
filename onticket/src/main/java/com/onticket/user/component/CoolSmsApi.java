package com.onticket.user.component;


import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;


//coolsms api키
@Getter
@Component
public class CoolSmsApi {
    @Setter
    @Value("${spring.coolsms.apiKey}")
    private String coolsms_ApiKey;

    public String getCoolsmsApiKey() {
        return coolsms_ApiKey;
    }

    @Value("${spring.coolsms.apiSecret}")
    private String coolsms_ApiSecret;

    public String getCoolsmsApiSecret() {
        return coolsms_ApiSecret;
    }

}
