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
                                @DefaultValue("16379") int redisPort) {
    public AdmissionPolicy policy() {
        if (!enabled || concertId == null || !concertId.matches("LOAD-TEST-[A-Za-z0-9-]{1,32}")
                || redisPort < 1 || redisPort > 65535) {
            throw new IllegalArgumentException("Explicit local admission fixture configuration required");
        }
        return new AdmissionPolicy(concertTimeId, generation, opensAt, maxActive, maxWaiting,
                waitingTtl, admissionTtl, batchSize);
    }
}
