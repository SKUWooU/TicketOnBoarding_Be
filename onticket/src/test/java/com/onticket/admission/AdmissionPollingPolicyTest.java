package com.onticket.admission;

import com.onticket.user.jwt.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.HashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdmissionPollingPolicyTest {
    AdmissionSettings settings(Integer interval) {
        var values = new HashMap<String, Object>(Map.of(
                "onticket.admission.enabled", "true", "onticket.admission.concert-id", "LOAD-TEST-polling",
                "onticket.admission.concert-time-id", "7", "onticket.admission.generation", "poll-g1",
                "onticket.admission.opens-at", "2030-01-01T00:00:00Z"));
        if (interval != null) values.put("onticket.admission.poll-interval-millis", interval.toString());
        return new Binder(new MapConfigurationPropertySource(values))
                .bind("onticket.admission", Bindable.of(AdmissionSettings.class)).orElseThrow(IllegalArgumentException::new);
    }

    @Test void policyAdvertisesConfiguredIntervalWhileKeepingDefaultAndNoStoreAccess() throws Exception {
        for (Integer interval : new Integer[]{null, 1000, 2000, 30000}) {
            var access = mock(AdmissionAccess.class);
            when(access.enabledPolicy("LOAD-TEST-polling", 7)).thenReturn(true);
            when(access.requireEnabledPolicy("LOAD-TEST-polling", 7)).thenReturn(settings(interval));
            var mvc = MockMvcBuilders.standaloneSetup(new AdmissionController(access, mock(JwtUtil.class))).build();
            mvc.perform(get("/main/detail/LOAD-TEST-polling/times/7/admission-policy")
                            .accept(org.springframework.http.MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.enabled").value(true))
                    .andExpect(jsonPath("$.pollIntervalMillis").value(interval == null ? 5000 : interval));
            verify(access, never()).store();
        }
    }

    @Test void clientHintDoesNotChangeAtomicRedisPolicyOrLeaseIdentity() {
        assertThat(settings(2000).policy()).isEqualTo(settings(null).policy());
        assertThat(settings(2000).policy().toString()).isEqualTo(settings(null).policy().toString());
    }
}
