package com.prodigalgal.remotecontrolmcp.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AgentRuntimeDescriptorTest {
    @Test
    void optionalUserContextKeepsOlderRuntimeAndCompanionEndpointsReadable() {
        var baseline = AgentRuntimeDescriptor.defaults();
        var oldJson = JsonCodec.write(baseline);
        assertTrue(!new String(oldJson, StandardCharsets.UTF_8).contains("user_context"));
        assertEquals(null, JsonCodec.read(oldJson, AgentRuntimeDescriptor.class).userContext());
        var context = new AgentUserContext("SYSTEM", "C:\\Windows\\System32\\config\\systemprofile", "zzp84", "C:\\Users\\zzp84\\OneDrive\\桌面");
        var described = new AgentRuntimeDescriptor(1, 0, 1, 1, baseline.maxOutputBytes(), baseline.maxAggregateOutputBytes(),
                32, 32, 0, 0, 0, true, false, true, false, "process-tree", context);
        assertEquals(context, JsonCodec.read(JsonCodec.write(described), AgentRuntimeDescriptor.class).userContext());
        var endpoint = new DesktopCompanionProtocol.Endpoint(12345, "test-ipc-token");
        assertEquals(endpoint, JsonCodec.read(JsonCodec.write(endpoint), DesktopCompanionProtocol.Endpoint.class));
        assertThrows(IllegalArgumentException.class, () -> new AgentUserContext("bad\nuser", "", "", ""));
        assertThrows(IllegalArgumentException.class, () -> new AgentUserContext("", "", "", "x".repeat(2049)));
    }

    @Test
    void roundTripsVersionedNonSecretRuntimeDescription() {
        var descriptor = new AgentRuntimeDescriptor(1, 12, 4, 2,
                32L * 1024 * 1024, 128L * 1024 * 1024, 24, 3600, 512L * 1024 * 1024, 1800,
                true, true);
        var json = new String(JsonCodec.write(descriptor), StandardCharsets.UTF_8);
        var decoded = JsonCodec.read(json.getBytes(StandardCharsets.UTF_8), AgentRuntimeDescriptor.class);

        assertEquals(12, decoded.configGeneration());
        assertEquals(4, decoded.maxConcurrency());
        assertEquals(128, decoded.maxTotalChildProcesses());
        assertEquals(512L * 1024 * 1024, decoded.maxRssBytes());
        assertTrue(decoded.desktopEnabled());
        assertTrue(json.contains("max_rss_bytes"));
        assertTrue(json.contains("max_total_child_processes"));
        assertEquals(AgentRuntimeDescriptor.CURRENT_SCHEMA_VERSION, decoded.schemaVersion());
    }

    @Test
    void rejectsIncompleteRuntimeDescriptor() {
        var incomplete = "{\"schema_version\":1,\"config_generation\":0,\"max_concurrency\":2,"
                + "\"max_browser_workers\":1,\"max_output_bytes\":1048576,"
                + "\"max_aggregate_output_bytes\":1048576,\"max_child_processes\":8,"
                + "\"max_task_duration_seconds\":0,\"max_rss_bytes\":0,\"max_cpu_seconds\":0,"
                + "\"desktop_enabled\":false,\"browser_adapter_configured\":false}";
        assertThrows(IllegalArgumentException.class,
                () -> JsonCodec.read(incomplete.getBytes(StandardCharsets.UTF_8), AgentRuntimeDescriptor.class));
    }

    @Test
    void rejectsUnknownFutureSchemaInsteadOfGuessingLimits() {
        assertThrows(IllegalArgumentException.class, () -> new AgentRuntimeDescriptor(
                AgentRuntimeDescriptor.CURRENT_SCHEMA_VERSION + 1, 0, 1, 1,
                64L * 1024 * 1024, 64L * 1024 * 1024, 32, 0, 0, 0, false, false));
    }
}
