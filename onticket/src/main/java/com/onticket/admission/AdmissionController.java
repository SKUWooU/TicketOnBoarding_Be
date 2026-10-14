package com.onticket.admission;

import com.onticket.user.jwt.JwtUtil;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/main/detail/{concertId}/times/{concertTimeId}")
public class AdmissionController {
    private final AdmissionAccess access;
    private final JwtUtil jwt;
    public AdmissionController(AdmissionAccess access, JwtUtil jwt) { this.access = access; this.jwt = jwt; }

    @GetMapping("/admission-policy")
    public ResponseEntity<?> policy(@PathVariable String concertId, @PathVariable long concertTimeId) {
        boolean enabled = access.enabledPolicy(concertId, concertTimeId);
        if (!enabled) return uncached(Map.of("enabled", false));
        var settings = access.requireEnabledPolicy(concertId, concertTimeId);
        return uncached(Map.of("enabled", true, "generation", settings.generation(), "opensAt", settings.opensAt(),
                "pollIntervalMillis", 5000));
    }

    @PostMapping("/admission")
    public ResponseEntity<?> join(@CookieValue(value = "accessToken", required = false) String token,
                                 @PathVariable String concertId, @PathVariable long concertTimeId) {
        String username = username(token);
        var settings = access.requireEnabledPolicy(concertId, concertTimeId);
        return uncached(response(settings, access.store().joinObserved(username)));
    }

    @GetMapping("/admission")
    public ResponseEntity<?> get(@CookieValue(value = "accessToken", required = false) String token,
                                @PathVariable String concertId, @PathVariable long concertTimeId) {
        String username = username(token);
        var settings = access.requireEnabledPolicy(concertId, concertTimeId);
        var observation = access.store().getObserved(username).orElseThrow(() ->
                new AdmissionRequestException(HttpStatus.NOT_FOUND, "ADMISSION_NOT_JOINED"));
        return uncached(response(settings, observation));
    }

    @DeleteMapping("/admission")
    public ResponseEntity<?> leave(@CookieValue(value = "accessToken", required = false) String token,
                                  @PathVariable String concertId, @PathVariable long concertTimeId,
                                  @RequestParam String generation, @RequestParam String queueEntryId) {
        String username = username(token);
        var settings = access.requireEnabledPolicy(concertId, concertTimeId);
        if (settings.generation().equals(generation)) {
            if (!queueEntryId.matches("[a-f0-9-]{36}")) {
                throw new AdmissionRequestException(HttpStatus.BAD_REQUEST, "INVALID_QUEUE_ENTRY_ID");
            }
            access.store().leave(username, queueEntryId);
        }
        return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
    }

    private String username(String token) {
        try {
            if (token != null && jwt.validateToken(token)) {
                String username = jwt.getUsernameFromToken(token);
                if (username != null && !username.isBlank() && username.length() <= 128) return username;
            }
        } catch (RuntimeException invalid) { /* Do not expose parser or token details. */ }
        throw new AdmissionRequestException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED");
    }

    private AdmissionResponse response(AdmissionSettings settings, AdmissionObservation observation) {
        return new AdmissionResponse(settings.generation(), observation.admission(), observation.serverNow());
    }
    private ResponseEntity<?> uncached(Object body) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(body);
    }
    public record AdmissionResponse(String generation, AdmissionSnapshot admission, java.time.Instant serverNow) {
        @Override public String toString() { return "AdmissionResponse[state=" + admission.state() + "]"; }
    }
}
