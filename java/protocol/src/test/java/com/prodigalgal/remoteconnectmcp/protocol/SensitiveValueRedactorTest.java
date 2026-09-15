package com.prodigalgal.remoteconnectmcp.protocol;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SensitiveValueRedactorTest {
    @Test
    void redactsAssignmentsAndBearerHeadersWithoutDestroyingContext() {
        var input = "request failed authorization: Bearer abcdefghijkl token=secret-value path=/work";
        var output = SensitiveValueRedactor.redact(input);
        assertTrue(output.contains("request failed"));
        assertTrue(output.contains("path=/work"));
        assertFalse(output.contains("abcdefghijkl"));
        assertFalse(output.contains("secret-value"));
        assertTrue(output.contains("[redacted]"));
    }

    @Test
    void redactsJsonAndPrivateKeyMaterial() {
        // Build the PEM delimiters from pieces so repository hygiene scanners
        // never mistake this redaction fixture for an accidentally committed
        // private key block.
        var begin = "-----BEGIN " + "OPENSSH PRIVATE KEY-----";
        var end = "-----END " + "OPENSSH PRIVATE KEY-----";
        var output = SensitiveValueRedactor.redact("{\"api_key\":\"abc\"} " + begin + "\nsecret\n" + end);
        assertFalse(output.contains("abc"));
        assertFalse(output.contains("secret"));
        assertTrue(output.contains("[private-key redacted]"));
    }

    @Test
    void redactsShortBearerValuesToo() {
        var output = SensitiveValueRedactor.redact("Authorization: Bearer x");
        assertFalse(output.endsWith("Bearer x"));
        assertTrue(output.contains("Bearer [redacted]"));
    }
}
