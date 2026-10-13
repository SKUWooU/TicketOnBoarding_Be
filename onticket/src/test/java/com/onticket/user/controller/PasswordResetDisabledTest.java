package com.onticket.user.controller;

import com.onticket.user.jwt.JwtUtil;
import com.onticket.user.repository.UserRepository;
import com.onticket.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class PasswordResetDisabledTest {
    private UserService users;
    private UserRepository repository;
    private JwtUtil jwt;
    private AuthenticationManager authentication;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        users = mock(UserService.class);
        repository = mock(UserRepository.class);
        jwt = mock(JwtUtil.class);
        authentication = mock(AuthenticationManager.class);
        mvc = standaloneSetup(new UserController(users, authentication, repository, jwt)).build();
    }

    @Test
    void directResetWithoutVerificationCannotChangePassword() throws Exception {
        assertDisabled("/users/changepwd", """
                {"phonenumber":"01000000001","password1":"Fixture!123","password2":"Fixture!123"}
                """);
        assertNoAccountAccess();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/users/issmscode", "/users/changepwd"})
    void suppliedCodeOrForgedProofCannotEnableRecovery(String path) throws Exception {
        assertDisabled(path, """
                {"phonenumber":"01000000002","smscode":"123456","verified":true,
                 "resetToken":"untrusted-fixture","password1":"Fixture!123","password2":"Fixture!123"}
                """);
        assertNoAccountAccess();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/users/issmscode", "/users/changepwd"})
    void emptyOrMalformedBodyStillReturnsDisabledContract(String path) throws Exception {
        assertDisabled(path, "");
        assertDisabled(path, "{not-json");
        assertNoAccountAccess();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/users/issmscode", "/users/changepwd"})
    void repeatedRequestsDoNotCreateVerificationState(String path) throws Exception {
        for (int i = 0; i < 3; i++) {
            assertDisabled(path, "{\"smscode\":\"123456\"}");
        }
        assertNoAccountAccess();
    }

    @Test
    void concurrentRequestsCannotReachAccountServices() throws Exception {
        try (var executor = Executors.newFixedThreadPool(4)) {
            var attempts = new ArrayList<Callable<Void>>();
            for (int i = 0; i < 8; i++) {
                String path = i % 2 == 0 ? "/users/issmscode" : "/users/changepwd";
                attempts.add(() -> {
                    assertDisabled(path, "{\"verified\":true}");
                    return null;
                });
            }
            for (var result : executor.invokeAll(attempts, 10, TimeUnit.SECONDS)) {
                result.get();
            }
        }
        assertNoAccountAccess();
    }

    @Test
    void authenticatedPasswordChangeRemainsSeparate() throws Exception {
        when(jwt.validateToken("fixture-access")).thenReturn(true);
        when(jwt.getUsernameFromToken("fixture-access")).thenReturn("fixture-owner");
        mvc.perform(post("/users/change")
                        .cookie(new jakarta.servlet.http.Cookie("accessToken", "fixture-access"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password1\":\"Fixture!123\",\"password2\":\"Fixture!123\"}"))
                .andExpect(status().isOk());
        verify(users).change("fixture-owner", "Fixture!123");
        verifyNoInteractions(repository, authentication);
    }

    @Test
    void authenticatedChangeStillRejectsMissingToken() throws Exception {
        mvc.perform(post("/users/change")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password1\":\"Fixture!123\",\"password2\":\"Fixture!123\"}"))
                .andExpect(status().isUnauthorized());
        assertNoAccountAccess();
    }

    private void assertDisabled(String path, String body) throws Exception {
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("PASSWORD_RECOVERY_DISABLED"))
                .andExpect(jsonPath("$.message").value("비밀번호 찾기는 안전한 인증 절차 준비 중으로 일시 중단되었습니다."));
    }

    private void assertNoAccountAccess() {
        verifyNoInteractions(users, repository, jwt, authentication);
    }
}
