package com.onticket.concert.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class SeatHoldConfiguration {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock seatHoldClock() {
        return Clock.systemDefaultZone();
    }
}
