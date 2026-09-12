package com.prodigalgal.remoteconnectmcp.center;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class EnrollmentTokenServiceTest {
    @Test
    void issuedTokenIsBoundToNameAndCanOnlyBeConsumedOnce() {
        var service = new EnrollmentTokenService();
        var issued = service.issue("desktop-01", Duration.ofHours(1));

        assertFalse(service.consume(issued.token(), "command-01"));
        assertTrue(service.consume(issued.token(), "desktop-01"));
        assertFalse(service.consume(issued.token(), "desktop-01"));
    }
}
