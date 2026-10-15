package com.onticket.admission;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdmissionWorkerConfigurationTest {
    final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AdmissionWorkerConfiguration.class)
            .withBean(RedisAdmissionStore.class, () -> mock(RedisAdmissionStore.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Test void noSchedulerOrWorkerUnlessAdmissionEnabled() {
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(AdmissionWorker.class)
                .doesNotHaveBean(ThreadPoolTaskScheduler.class));
    }
    @Test void explicitWorkerDisableKeepsCoreWithoutScheduling() {
        runner.withPropertyValues("onticket.admission.enabled=true", "onticket.admission.worker.enabled=false")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(RedisAdmissionStore.class)
                        .doesNotHaveBean(AdmissionWorker.class).doesNotHaveBean(ThreadPoolTaskScheduler.class));
    }
    @Test void optInRegistersSingleThreadSchedulerWithoutBootstrap() {
        runner.withPropertyValues("onticket.admission.enabled=true").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(AdmissionWorker.class);
            assertThat(context.getBean(ThreadPoolTaskScheduler.class).getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
            verifyNoInteractions(context.getBean(RedisAdmissionStore.class));
        });
    }
    @Test void invalidPeriodFailsStartup() {
        runner.withPropertyValues("onticket.admission.enabled=true", "onticket.admission.worker.delay-ms=0")
                .run(context -> assertThat(context).hasFailed());
        assertThatThrownBy(() -> new AdmissionWorkerSettings(true, 60001)).isInstanceOf(IllegalArgumentException.class);
    }
}
