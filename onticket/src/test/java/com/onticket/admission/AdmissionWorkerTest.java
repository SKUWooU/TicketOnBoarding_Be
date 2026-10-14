package com.onticket.admission;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class AdmissionWorkerTest {
    @Test void unavailableTicksAreCountedWithoutBootstrapAndLogsOnlyOnStateChange(CapturedOutput output) {
        var store = mock(RedisAdmissionStore.class); var meters = new SimpleMeterRegistry();
        when(store.tick()).thenThrow(new AdmissionUnavailableException()).thenThrow(new AdmissionUnavailableException())
                .thenReturn(2).thenReturn(0);
        var worker = new AdmissionWorker(store, meters);
        for (int i = 0; i < 4; i++) worker.tick();
        verify(store, times(4)).tick(); verifyNoMoreInteractions(store);
        assertThat(meters.get("onticket.admission.worker.ticks").tag("result", "unavailable").counter().count()).isEqualTo(2);
        assertThat(meters.get("onticket.admission.worker.ticks").tag("result", "success").counter().count()).isEqualTo(2);
        assertThat(meters.get("onticket.admission.worker.promoted").counter().count()).isEqualTo(2);
        assertThat(output.getAll()).containsOnlyOnce("Admission worker unavailable;").containsOnlyOnce("available again")
                .doesNotContain("AdmissionUnavailableException", "token", "username", "generation");
        assertThat(meters.getMeters()).hasSize(3);
        assertThat(meters.getMeters().stream().flatMap(m -> m.getId().getTags().stream()).map(t -> t.getKey()).distinct())
                .containsExactly("result");
    }
}
