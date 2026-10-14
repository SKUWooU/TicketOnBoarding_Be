package com.onticket.admission;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.concurrent.atomic.AtomicBoolean;

/** Scheduling is separate from HTTP polling. No bootstrap, local fallback, or per-user metrics. */
public final class AdmissionWorker {
    private static final Logger LOG = LoggerFactory.getLogger(AdmissionWorker.class);
    private final RedisAdmissionStore store;
    private final Counter successfulTicks;
    private final Counter unavailableTicks;
    private final Counter promoted;
    private final AtomicBoolean unavailable = new AtomicBoolean();

    public AdmissionWorker(RedisAdmissionStore store, MeterRegistry registry) {
        this.store = store;
        successfulTicks = registry.counter("onticket.admission.worker.ticks", "result", "success");
        unavailableTicks = registry.counter("onticket.admission.worker.ticks", "result", "unavailable");
        promoted = registry.counter("onticket.admission.worker.promoted");
    }

    @Scheduled(fixedDelayString = "${onticket.admission.worker.delay-ms:1000}",
            initialDelayString = "${onticket.admission.worker.delay-ms:1000}", scheduler = "admissionTaskScheduler")
    public void tick() {
        try {
            int count = store.tick();
            successfulTicks.increment(); promoted.increment(count);
            if (unavailable.compareAndSet(true, false)) LOG.info("Admission worker available again");
        } catch (AdmissionUnavailableException failure) {
            unavailableTicks.increment();
            if (unavailable.compareAndSet(false, true)) LOG.warn("Admission worker unavailable; no initialization or fallback");
        }
    }
}
