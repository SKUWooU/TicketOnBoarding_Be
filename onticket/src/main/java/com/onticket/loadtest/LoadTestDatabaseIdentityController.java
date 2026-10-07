package com.onticket.loadtest;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/loadtest")
@RequiredArgsConstructor
@Profile("loadtest")
public class LoadTestDatabaseIdentityController {

    private static final String DATABASE_FINGERPRINT_SQL =
            "SELECT SHA2(CONCAT(@@hostname, ':', DATABASE()), 256)";

    private final JdbcTemplate jdbcTemplate;

    @GetMapping("/database-identity")
    public DatabaseIdentity databaseIdentity() {
        return new DatabaseIdentity(jdbcTemplate.queryForObject(DATABASE_FINGERPRINT_SQL, String.class));
    }

    public record DatabaseIdentity(String fingerprint) {
    }
}
