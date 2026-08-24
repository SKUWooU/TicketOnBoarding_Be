package com.onticket.concert.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PaymentVerificationConfiguration {

    @Bean
    @ConditionalOnMissingBean(PaymentVerificationPort.class)
    PaymentVerificationPort paymentVerificationPort() {
        return new UnconfiguredPaymentVerificationAdapter();
    }
}
