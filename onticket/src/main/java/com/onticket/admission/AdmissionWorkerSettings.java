package com.onticket.admission;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("onticket.admission.worker")
public record AdmissionWorkerSettings(@DefaultValue("true") boolean enabled, @DefaultValue("1000") long delayMs) {
    public AdmissionWorkerSettings {
        if (delayMs < 250 || delayMs > 60000) throw new IllegalArgumentException("Admission worker delay must be 250..60000ms");
    }
}
