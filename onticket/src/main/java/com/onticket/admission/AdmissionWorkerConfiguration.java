package com.onticket.admission;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "onticket.admission.enabled", havingValue = "true")
@EnableConfigurationProperties(AdmissionWorkerSettings.class)
public class AdmissionWorkerConfiguration {
    @Bean
    @ConditionalOnProperty(name = "onticket.admission.worker.enabled", havingValue = "true", matchIfMissing = true)
    ThreadPoolTaskScheduler admissionTaskScheduler(AdmissionWorkerSettings settings) {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("admission-tick-");
        scheduler.setDaemon(true); scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }
    @Bean
    @ConditionalOnProperty(name = "onticket.admission.worker.enabled", havingValue = "true", matchIfMissing = true)
    AdmissionWorker admissionWorker(RedisAdmissionStore store, MeterRegistry registry, AdmissionWorkerSettings settings) {
        return new AdmissionWorker(store, registry);
    }
}
