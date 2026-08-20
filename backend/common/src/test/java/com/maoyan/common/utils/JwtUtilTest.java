package com.maoyan.common.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtUtilTest {

    private static final String VALID_SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void generateAndValidate() {
        JwtUtil jwtUtil = new JwtUtil(VALID_SECRET, 1, true);

        String token = jwtUtil.generateToken(42L, "tester");

        assertNotNull(token);
        assertTrue(jwtUtil.validate(token));
        assertTrue(jwtUtil.getUserId(token) == 42L);
    }

    @Test
    void rejectShortSecret() {
        assertThrows(IllegalStateException.class, () -> new JwtUtil("short", 1, true));
    }

    @Test
    void rejectDefaultSecretWhenNotAllowed() {
        assertThrows(IllegalStateException.class, () ->
                new JwtUtil("change-me-to-a-random-256-bit-jwt-secret-at-least-32-chars", 1, false));
    }
}
