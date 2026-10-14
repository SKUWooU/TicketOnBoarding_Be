package com.onticket.admission;

import com.onticket.concert.repository.ConcertTimeRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;

@Service
public class AdmissionAccess {
    public static final String TOKEN_HEADER = "X-Admission-Token";
    private final AdmissionSettings settings;
    private final ObjectProvider<RedisAdmissionStore> stores;
    private final ConcertTimeRepository times;

    public AdmissionAccess(AdmissionSettings settings, ObjectProvider<RedisAdmissionStore> stores,
                           ConcertTimeRepository times) {
        this.settings = settings; this.stores = stores; this.times = times;
    }

    public boolean protectedTime(Long timeId) {
        return settings.enabled() && timeId != null && timeId == settings.concertTimeId();
    }

    public void requireAdmission(String username, String concertId, Long timeId, String token) {
        if (!protectedTime(timeId)) return;
        requireScope(concertId, timeId);
        if (username == null || username.isBlank() || username.length() > 128
                || token == null || !token.matches("[a-f0-9-]{36}")) {
            throw new AdmissionRequestException(HttpStatus.FORBIDDEN, "ADMISSION_REQUIRED");
        }
        var store = store();
        var admission = store.get(username).orElseThrow(() ->
                new AdmissionRequestException(HttpStatus.FORBIDDEN, "ADMISSION_REQUIRED"));
        if (!store.isAdmitted(username, admission.admissionId(), token)) {
            throw new AdmissionRequestException(HttpStatus.FORBIDDEN, "ADMISSION_REQUIRED");
        }
    }

    public void rejectDirectReservation(Long timeId) {
        if (protectedTime(timeId)) {
            throw new AdmissionRequestException(HttpStatus.FORBIDDEN, "CHECKOUT_REQUIRED");
        }
    }

    public void rejectFixtureMutation(String runId) {
        if (settings.enabled() && settings.concertId().equals("LOAD-TEST-" + runId)) {
            throw new AdmissionRequestException(HttpStatus.FORBIDDEN, "ADMISSION_FIXTURE_FROZEN");
        }
    }

    public boolean enabledPolicy(String concertId, long timeId) {
        requireScope(concertId, timeId);
        return protectedTime(timeId);
    }

    public AdmissionSettings requireEnabledPolicy(String concertId, long timeId) {
        if (!enabledPolicy(concertId, timeId)) {
            throw new AdmissionRequestException(HttpStatus.CONFLICT, "ADMISSION_NOT_ENABLED");
        }
        return settings;
    }

    private void requireScope(String concertId, Long timeId) {
        if (timeId == null || timeId <= 0 || (protectedTime(timeId) && !settings.concertId().equals(concertId))
                || times.findByIdAndConcert_ConcertId(timeId, concertId).isEmpty()) {
            throw new AdmissionRequestException(HttpStatus.NOT_FOUND, "CONCERT_TIME_NOT_FOUND");
        }
    }

    public RedisAdmissionStore store() {
        var store = stores.getIfAvailable();
        if (store == null) throw new AdmissionUnavailableException();
        return store;
    }
}
