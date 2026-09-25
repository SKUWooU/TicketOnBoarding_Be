package com.onticket.user.jwt;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtUtilTest {

    private static final String ISSUER = "onticket-two-instance-test";
    private static final String SHARED_SECRET = "b250aWNrZXQtdGVzdC1qd3Qtc2hhcmVkLXNlY3JldC1rZXktMDAwMDAw";

    @Test
    void validatesATokenIssuedByAnotherInstanceWithTheSameConfiguredSecret() {
        JwtUtil issuingInstance = jwtUtil(SHARED_SECRET);
        JwtUtil verifyingInstance = jwtUtil(SHARED_SECRET);

        String token = issuingInstance.generateAccessToken("two-instance-user");

        assertThat(verifyingInstance.validateToken(token)).isTrue();
        assertThat(verifyingInstance.getUsernameFromToken(token)).isEqualTo("two-instance-user");
    }

    @Test
    void rejectsATooShortConfiguredSecretAtStartup() {
        JwtUtil jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "issuer", ISSUER);
        ReflectionTestUtils.setField(jwtUtil, "secret", "dG9vLXNob3J0");

        assertThatThrownBy(jwtUtil::init)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jwt.secret");
    }

    @Test
    void rejectsAMalformedBase64ConfiguredSecretAtStartup() {
        JwtUtil jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "issuer", ISSUER);
        ReflectionTestUtils.setField(jwtUtil, "secret", "not-base64!");

        assertThatThrownBy(jwtUtil::init)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jwt.secret");
    }

    @Test
    void rejectsAMissingConfiguredSecretAtStartup() {
        JwtUtil jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "issuer", ISSUER);
        ReflectionTestUtils.setField(jwtUtil, "secret", null);

        assertThatThrownBy(jwtUtil::init)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jwt.secret");
    }

    private JwtUtil jwtUtil(String secret) {
        JwtUtil jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "issuer", ISSUER);
        ReflectionTestUtils.setField(jwtUtil, "secret", secret);
        jwtUtil.init();
        return jwtUtil;
    }
}
