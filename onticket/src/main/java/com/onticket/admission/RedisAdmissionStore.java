package com.onticket.admission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Explicitly constructed core; no controller, automatic bootstrap or scheduler. */
public final class RedisAdmissionStore {
    private static final DefaultRedisScript<String> SCRIPT = new DefaultRedisScript<>();
    static {
        SCRIPT.setLocation(new ClassPathResource("admission/admission.lua"));
        SCRIPT.setResultType(String.class);
    }
    private final StringRedisTemplate redis;
    private final AdmissionPolicy policy;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> keys;
    private final String fingerprint;
    private final LongSupplier testTime;

    public RedisAdmissionStore(StringRedisTemplate redis, AdmissionPolicy policy) {
        this(redis, policy, null);
    }

    // Controlled-time seam for package-local tests only; production scripts use Redis TIME.
    RedisAdmissionStore(StringRedisTemplate redis, AdmissionPolicy policy, LongSupplier testTime) {
        this.redis = java.util.Objects.requireNonNull(redis);
        this.policy = java.util.Objects.requireNonNull(policy);
        this.testTime = testTime;
        String prefix = "admission:{" + policy.concertTimeId() + ":" + policy.generation() + "}:";
        keys = List.of(prefix + "meta", prefix + "states", prefix + "waiting", prefix + "waiting-expiry",
                prefix + "active", prefix + "terminal");
        fingerprint = digest(policy.toString());
    }

    /** Operator-only primitive: callers must use a new generation after state loss. */
    public void bootstrap() { execute("INIT", "", "", "", ""); }

    /** Operator CLI: atomically refuses any already initialized or partially present namespace. */
    public void bootstrapFresh() { execute("INIT_FRESH", "", "", "", ""); }

    public AdmissionSnapshot join(String username) {
        return joinObserved(username).admission();
    }

    public Optional<AdmissionSnapshot> get(String username) {
        return getObserved(username).map(AdmissionObservation::admission);
    }

    public AdmissionObservation joinObserved(String username) {
        return observation(execute("JOIN", member(username), UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), UUID.randomUUID().toString())).orElseThrow(AdmissionUnavailableException::new);
    }

    public Optional<AdmissionObservation> getObserved(String username) {
        return observation(execute("READ", member(username), "", "", ""));
    }

    public void leave(String username, String expectedQueueEntryId) {
        if (expectedQueueEntryId == null || !expectedQueueEntryId.matches("[a-f0-9-]{36}")) {
            throw new IllegalArgumentException("Expected queue entry ID required");
        }
        execute("LEAVE", member(username), expectedQueueEntryId, "", "");
    }

    public int tick() { return Integer.parseInt(execute("TICK", "", "", "", "")); }

    public boolean isAdmitted(String username, String admissionId, String token) {
        if (admissionId == null || token == null) return false;
        return "true".equals(execute("VERIFY", member(username), "", admissionId, token));
    }

    private String execute(String operation, String member, String entryId, String admissionId, String token) {
        try {
            String value = redis.execute(SCRIPT, keys, operation, fingerprint,
                    Long.toString(policy.opensAt().toEpochMilli()), Integer.toString(policy.maxActive()),
                    Integer.toString(policy.maxWaiting()), Long.toString(policy.waitingTtl().toMillis()),
                    Long.toString(policy.admissionTtl().toMillis()), Integer.toString(policy.batchSize()),
                    member, entryId, admissionId, token, testTime == null ? "" : Long.toString(testTime.getAsLong()));
            if (value == null) throw new AdmissionUnavailableException();
            return value;
        } catch (DataAccessException failure) {
            String reason = failure.getMostSpecificCause().getMessage();
            if (operation.equals("JOIN") && reason != null && reason.contains("ADMISSION_QUEUE_FULL")) {
                throw new AdmissionQueueFullException();
            }
            throw new AdmissionUnavailableException();
        }
    }

    private Optional<AdmissionSnapshot> snapshot(String json) {
        if (json.equals("null")) return Optional.empty();
        try {
            JsonNode node = mapper.readTree(json);
            AdmissionSnapshot.State state = AdmissionSnapshot.State.valueOf(node.get("state").asText());
            return Optional.of(new AdmissionSnapshot(node.get("id").asText(), state,
                    Instant.ofEpochMilli(node.get("expiry").asLong()), node.get("seq").asLong(),
                    node.get("position").asLong(), state == AdmissionSnapshot.State.ADMITTED ? node.get("admissionId").asText() : null,
                    state == AdmissionSnapshot.State.ADMITTED ? node.get("token").asText() : null));
        } catch (Exception invalid) { throw new AdmissionUnavailableException(); }
    }

    private Optional<AdmissionObservation> observation(String json) {
        var snapshot = snapshot(json);
        if (snapshot.isEmpty()) return Optional.empty();
        try {
            JsonNode now = mapper.readTree(json).get("serverNow");
            if (now == null || !now.isIntegralNumber() || now.asLong() < 0) throw new AdmissionUnavailableException();
            return Optional.of(new AdmissionObservation(snapshot.get(), Instant.ofEpochMilli(now.asLong())));
        } catch (Exception invalid) { throw new AdmissionUnavailableException(); }
    }

    private static String member(String username) {
        if (username == null || username.isBlank() || username.length() > 128) throw new IllegalArgumentException("Invalid admission user");
        return digest(username);
    }

    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
