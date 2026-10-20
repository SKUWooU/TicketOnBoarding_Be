package com.onticket.admission;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import static org.assertj.core.api.Assertions.*;

class AdmissionConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AdmissionConfiguration.class);
    private final String[] configured = {"onticket.admission.enabled=true", "onticket.admission.concert-id=LOAD-TEST-gate",
            "onticket.admission.concert-time-id=7", "onticket.admission.generation=g1",
            "onticket.admission.opens-at=2030-01-01T00:00:00Z"};

    @Test void disabledDefaultDoesNotRegisterRedisOrInitializeAnything() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(RedisAdmissionStore.class)
                    .doesNotHaveBean(LettuceConnectionFactory.class);
            assertThat(context.getBean(AdmissionSettings.class).enabled()).isFalse();
            assertThat(context.getBean(AdmissionSettings.class).pollIntervalMillis()).isEqualTo(5000);
        });
    }
    @Test void enabledOutsideLocalAndLoadtestFailsStartup() {
        runner.withPropertyValues(configured).withPropertyValues("spring.profiles.active=loadtest")
                .run(context -> assertThat(context).hasFailed());
    }
    @Test void enabledWithIncompleteOrNonFixturePolicyFailsStartup() {
        runner.withPropertyValues("spring.profiles.active=local,loadtest", "onticket.admission.enabled=true")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues(configured).withPropertyValues("spring.profiles.active=local,loadtest",
                        "onticket.admission.concert-id=REAL-CONCERT")
                .run(context -> assertThat(context).hasFailed());
    }
    @Test void repositoryRestExporterIsNotOnApplicationClasspath() {
        assertThat(org.springframework.util.ClassUtils.isPresent(
                "org.springframework.data.rest.webmvc.RepositoryRestController", getClass().getClassLoader())).isFalse();
    }
    @Test void validOptInRegistersStoreButDoesNotBootstrapNamespace() {
        runner.withPropertyValues(configured).withPropertyValues("spring.profiles.active=local,loadtest")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(RedisAdmissionStore.class));
    }

    @Test void pollingHintBindsBoundedIntegerWithoutEnablingAdmissionOrRedis() {
        for (int interval : new int[]{1000, 2000, 30000}) {
            runner.withPropertyValues("onticket.admission.poll-interval-millis=" + interval).run(context -> {
                assertThat(context).hasNotFailed().doesNotHaveBean(RedisAdmissionStore.class);
                var settings = context.getBean(AdmissionSettings.class);
                assertThat(settings.enabled()).isFalse();
                assertThat(settings.pollIntervalMillis()).isEqualTo(interval);
            });
        }
    }

    @Test void invalidPollingHintFailsBeforeRuntimeCreation() {
        for (String interval : new String[]{"0", "999", "30001", "-1", "2.5", "invalid"}) {
            runner.withPropertyValues("onticket.admission.poll-interval-millis=" + interval)
                    .run(context -> assertThat(context).hasFailed());
        }
    }
}
