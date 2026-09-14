package com.prodigalgal.remoteconnectmcp.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AgentRuntimeDescriptorTest {
    @Test
    void roundTripsVersionedNonSecretRuntimeDescription() {
        var descriptor = new AgentRuntimeDescriptor(1, 12, 4, 2,
                32L * 1024 * 1024, 128L * 1024 * 1024, 24, 3600, 512L * 1024 * 1024, 1800,
                true, true);
        var json = new String(JsonCodec.write(descriptor), StandardCharsets.UTF_8);
        var decoded = JsonCodec.read(json.getBytes(StandardCharsets.UTF_8), AgentRuntimeDescriptor.class);

        assertEquals(12, decoded.configGeneration());
        assertEquals(4, decoded.maxConcurrency());
        assertEquals(512L * 1024 * 1024, decoded.maxRssBytes());
        assertTrue(decoded.desktopEnabled());
        assertTrue(json.contains("max_rss_bytes"));
        assertTrue(decoded.compatibleWith(AgentRuntimeDescriptor.CURRENT_SCHEMA_VERSION));
    }

    @Test
    void rejectsUnknownFutureSchemaInsteadOfGuessingLimits() {
        assertThrows(IllegalArgumentException.class, () -> new AgentRuntimeDescriptor(
                AgentRuntimeDescriptor.CURRENT_SCHEMA_VERSION + 1, 0, 1, 1,
                64L * 1024 * 1024, 64L * 1024 * 1024, 32, 0, 0, 0, false, false));
    }
}
