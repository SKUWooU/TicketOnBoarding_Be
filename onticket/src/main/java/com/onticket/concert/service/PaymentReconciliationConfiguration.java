package com.onticket.concert.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PaymentReconciliationConfiguration {

    @Bean
    @ConditionalOnMissingBean(PaymentReconciliationPort.class)
    PaymentReconciliationPort paymentReconciliationPort() {
        return new UnconfiguredPaymentReconciliationAdapter();
    }
}
