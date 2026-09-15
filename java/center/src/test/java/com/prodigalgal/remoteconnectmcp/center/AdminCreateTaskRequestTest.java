package com.prodigalgal.remoteconnectmcp.center;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.prodigalgal.remoteconnectmcp.protocol.ScopeMode;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AdminCreateTaskRequestTest {
    @Test
    void convertsFlatConsolePayloadToInternalCommand() {
        var request = new AdminCreateTaskRequest("machine-1", "echo ok", "/srv/project",
                Map.of("LANG", "C"), 30, "retry-1", "project-1", "worktree-1");
        var internal = request.toInternal();

        assertEquals("machine-1", internal.machineId());
        assertEquals("echo ok", internal.command().command());
        assertEquals("/srv/project", internal.command().cwd());
        assertEquals(Map.of("LANG", "C"), internal.command().env());
        assertEquals(30, internal.command().timeoutSeconds());
        assertEquals("retry-1", internal.idempotencyKey());
        assertNull(internal.command().desktop());
    }

    @Test
    void carriesExplicitPathContractInputsWithoutEmbeddingAContract() {
        var request = new AdminCreateTaskRequest("machine-1", "echo ok", "/srv/project",
                Map.of(), 30, "retry-path", "", "", "path", "/srv/project", "session-1", "high", true);
        var internal = request.toInternal();

        assertEquals(ScopeMode.PATH, internal.scopeMode());
        assertEquals("/srv/project", internal.scopeRoot());
        assertEquals("session-1", internal.sessionId());
        assertEquals("high", internal.risk());
        assertEquals(true, internal.elevationRequired());
        assertNull(internal.command().contract());
    }
}
