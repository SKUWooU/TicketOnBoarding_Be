package com.onticket.admission;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.Arrays;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdmissionSettings.class)
public class AdmissionConfiguration {
    @Bean
    @ConditionalOnProperty(name = "onticket.admission.enabled", havingValue = "true")
    AdmissionPolicy admissionPolicy(AdmissionSettings settings, Environment environment) {
        var profiles = Arrays.asList(environment.getActiveProfiles());
        if (!profiles.contains("local") || !profiles.contains("loadtest")) {
            throw new IllegalArgumentException("Admission requires local AND loadtest profiles");
        }
        return settings.policy();
    }

    @Bean(destroyMethod = "destroy")
    @ConditionalOnProperty(name = "onticket.admission.enabled", havingValue = "true")
    LettuceConnectionFactory admissionRedisConnectionFactory(AdmissionSettings settings, AdmissionPolicy policy) {
        // Loopback only. No automatic namespace initialization or recreation on failure.
        return AdmissionRedisConnections.loopback(settings.redisPort());
    }

    @Bean
    @ConditionalOnProperty(name = "onticket.admission.enabled", havingValue = "true")
    RedisAdmissionStore admissionStore(LettuceConnectionFactory admissionRedisConnectionFactory,
                                      AdmissionPolicy policy) {
        return new RedisAdmissionStore(new StringRedisTemplate(admissionRedisConnectionFactory), policy);
    }
}
