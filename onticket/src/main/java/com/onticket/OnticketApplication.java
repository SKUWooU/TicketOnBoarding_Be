package com.onticket;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class OnticketApplication {

    public static void main(String[] args) {
        SpringApplication.run(OnticketApplication.class, args);
    }

}
