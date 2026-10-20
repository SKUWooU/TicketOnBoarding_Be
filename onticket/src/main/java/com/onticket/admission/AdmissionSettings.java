package com.onticket.admission;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;
import java.time.Instant;

/** One explicitly selected local fixture; never enabled by a client request. */
@ConfigurationProperties("onticket.admission")
public record AdmissionSettings(@DefaultValue("false") boolean enabled, String concertId,
                                long concertTimeId, String generation, Instant opensAt,
                                @DefaultValue("2") int maxActive, @DefaultValue("20") int maxWaiting,
                                @DefaultValue("5m") Duration waitingTtl,
                                @DefaultValue("1m") Duration admissionTtl,
                                @DefaultValue("2") int batchSize,
                                @DefaultValue("16379") int redisPort,
                                @DefaultValue("5000") int pollIntervalMillis) {
    public AdmissionSettings {
        // Client guidance only: does not participate in Redis admission identity or renew any lease.
        if (pollIntervalMillis < 1000 || pollIntervalMillis > 30000) {
            throw new IllegalArgumentException("Admission polling interval must be 1000..30000ms");
        }
    }
    public AdmissionPolicy policy() {
        if (!enabled || concertId == null || !concertId.matches("LOAD-TEST-[A-Za-z0-9-]{1,32}")
                || redisPort < 1 || redisPort > 65535) {
            throw new IllegalArgumentException("Explicit local admission fixture configuration required");
        }
        return new AdmissionPolicy(concertTimeId, generation, opensAt, maxActive, maxWaiting,
                waitingTtl, admissionTtl, batchSize);
    }
}
