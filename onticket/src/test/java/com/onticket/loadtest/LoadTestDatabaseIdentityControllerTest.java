package com.onticket.loadtest;

import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class LoadTestDatabaseIdentityControllerTest {

    @Test
    void returnsReadOnlyDatabaseFingerprint() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        String fingerprint = "a".repeat(64);
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class))).thenReturn(fingerprint);
        MockMvc mockMvc = standaloneSetup(new LoadTestDatabaseIdentityController(jdbcTemplate))
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();

        mockMvc.perform(get("/loadtest/database-identity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fingerprint").value(fingerprint));
    }
}
