package com.onticket.loadtest;

import com.onticket.user.jwt.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class LoadTestControllerTest {

    private LoadTestFixtureService fixtureService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        fixtureService = mock(LoadTestFixtureService.class);
        mockMvc = standaloneSetup(new LoadTestController(fixtureService, mock(JwtUtil.class)))
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    void mapsInvalidRunIdToStableBadRequestContract() throws Exception {
        when(fixtureService.initialize(anyString())).thenThrow(new InvalidLoadTestRunIdException());

        mockMvc.perform(post("/loadtest/runs").param("runId", "invalid_run_id"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LOADTEST_RUN_ID"))
                .andExpect(jsonPath("$.message").value("loadtest runId는 영문·숫자·하이픈 1~32자여야 합니다."));
    }
}
