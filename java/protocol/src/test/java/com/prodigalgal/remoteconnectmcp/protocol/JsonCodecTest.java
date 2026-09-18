package com.prodigalgal.remoteconnectmcp.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class JsonCodecTest {
    @Test
    void usesTheCurrentSnakeCaseWireFormat() {
        var request = new RegisterRequest("agent", "host-a", "host-a", "linux", "amd64", "dev", "/srv", ScopeMode.WORKSPACE, "/srv/project", List.of("command"));
        var json = new String(JsonCodec.write(request), java.nio.charset.StandardCharsets.UTF_8);

        assertTrue(json.contains("\"host_id\""));
        assertTrue(json.contains("\"default_cwd\""));
        assertTrue(json.contains("\"scope_mode\":\"workspace\""));
        assertEquals(request, JsonCodec.read(JsonCodec.write(request), RegisterRequest.class));
    }

    @Test
    void rejectsIncompletePollRequests() {
        assertThrows(IllegalArgumentException.class,
                () -> JsonCodec.read("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), PollRequest.class));
    }

    @Test
    void rejectsNullConfigGeneration() {
        assertThrows(IllegalArgumentException.class, () -> JsonCodec.read("{\"config_generation\":null}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8), PollRequest.class));
    }

    @Test
    void allowsOptionalCurrentRuntimeConfig() {
        var response = JsonCodec.read("{\"cancel_task_ids\":[],\"config\":{\"generation\":2,\"poll_interval_ms\":1500,\"max_concurrency\":3}}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8), PollResponse.class);
        assertEquals(2, response.config().generation());
        assertEquals(1500, response.config().pollIntervalMs());
        assertEquals(3, response.config().maxConcurrency());
        assertEquals(null, JsonCodec.read("{\"cancel_task_ids\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8), PollResponse.class).config());
    }

    @Test
    void roundTripsExecutionContractInSnakeCase() {
        var contract = new ExecutionContract("machine-1", "host-1", ScopeMode.WORKTREE,
                "project-1", "worktree-1", "/srv/project/.rcm-worktrees/wt-1", "session-1", "command",
                new ExecutionContract.Budget(30, 2L * 1024 * 1024, 1024, 2),
                Instant.parse("2030-01-01T00:00:00Z"), "retry-1", "high", false, "lease-1");
        var json = new String(JsonCodec.write(contract), java.nio.charset.StandardCharsets.UTF_8);

        assertTrue(json.contains("\"machine_id\""));
        assertTrue(json.contains("\"scope_mode\":\"worktree\""));
        assertTrue(json.contains("\"max_duration_seconds\""));
        assertEquals(contract, JsonCodec.read(JsonCodec.write(contract), ExecutionContract.class));
    }
}
