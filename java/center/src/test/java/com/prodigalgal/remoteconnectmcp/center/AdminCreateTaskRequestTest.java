package com.prodigalgal.remoteconnectmcp.center;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
