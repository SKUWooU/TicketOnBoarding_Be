package com.onticket.admission;

import com.onticket.OnticketApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.stereotype.Component;
import static org.assertj.core.api.Assertions.assertThat;

class AdmissionActivationBoundaryTest {
    @Test void redisCoreIsNotAutomaticallyConnectedOrRegisteredAsAService() {
        assertThat(OnticketApplication.class.getAnnotation(SpringBootApplication.class).exclude())
                .contains(RedisAutoConfiguration.class, RedisRepositoriesAutoConfiguration.class);
        assertThat(RedisAdmissionStore.class.getAnnotation(Component.class)).isNull();
    }
    @Test void snapshotStringDoesNotContainCredentialsOrIdentifiers() {
        var snapshot = new AdmissionSnapshot("entry-sensitive", AdmissionSnapshot.State.ADMITTED,
                java.time.Instant.ofEpochMilli(1000), 1, 0, "admission-sensitive", "token-sensitive");
        assertThat(snapshot.toString()).contains("ADMITTED")
                .doesNotContain("entry-sensitive", "admission-sensitive", "token-sensitive");
    }
}
