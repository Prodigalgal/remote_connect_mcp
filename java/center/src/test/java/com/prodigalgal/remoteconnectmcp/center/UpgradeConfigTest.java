package com.prodigalgal.remoteconnectmcp.center;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class UpgradeConfigTest {
    @Test
    void keepsJavaTagPrefixSeparateFromPublicVersion() {
        var config = new UpgradeConfig(true, "https://releases.example.test/download", "java-");

        assertEquals("https://releases.example.test/download", config.releaseBaseUrl());
        assertEquals("java-", config.releaseTagPrefix());
    }

    @Test
    void rejectsPathCharactersInTagPrefix() {
        assertThrows(IllegalArgumentException.class,
                () -> new UpgradeConfig(true, "https://releases.example.test/download", "../"));
    }
}
