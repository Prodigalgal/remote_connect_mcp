package com.prodigalgal.remotecontrolmcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class AgentUpgradeRunnerTest {
    @Test
    void downloadReadReturnsAvailableBytes() throws Exception {
        var buffer = new byte[8];
        var count = AgentUpgradeRunner.readWithDeadline(new ByteArrayInputStream(new byte[]{1, 2, 3}),
                buffer, System.nanoTime() + Duration.ofSeconds(1).toNanos(), Duration.ofMillis(100));
        assertEquals(3, count);
        assertEquals(1, buffer[0]);
    }

    @Test
    void stalledDownloadReadEndsInsteadOfHoldingUpgradeForever() throws Exception {
        try (var writer = new PipedOutputStream(); var input = new PipedInputStream(writer)) {
            var error = assertThrows(IOException.class, () -> AgentUpgradeRunner.readWithDeadline(
                    input, new byte[8], System.nanoTime() + Duration.ofSeconds(2).toNanos(),
                    Duration.ofMillis(30)));
            assertTrue(error.getMessage().contains("stalled"));
        }
    }
}
