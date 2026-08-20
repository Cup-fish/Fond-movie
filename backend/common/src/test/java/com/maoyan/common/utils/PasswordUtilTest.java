package com.maoyan.common.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordUtilTest {

    @Test
    void encodeAndMatches() {
        String raw = "my-password-123";
        String encoded = PasswordUtil.encode(raw);

        assertTrue(PasswordUtil.matches(raw, encoded));
        assertFalse(PasswordUtil.matches("wrong-password", encoded));
    }
}
