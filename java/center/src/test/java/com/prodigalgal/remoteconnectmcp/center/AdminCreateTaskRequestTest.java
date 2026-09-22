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

    @Test
    void convertsDesktopTaskPayloadWithAction() {
        var action = new com.prodigalgal.remoteconnectmcp.protocol.TaskCommand.DesktopAction(
                "launch", "notepad.exe", java.util.List.of("test.txt"), "/srv/project");
        var request = new AdminCreateTaskRequest("machine-1", "desktop", "desktop", "",
                "/srv/project", Map.of(), 60, "retry-desktop", "project-1", "",
                "project", "/srv/project", "session-desktop", "low", false, null, null, action);
        var internal = request.toInternal();

        assertEquals("machine-1", internal.machineId());
        assertEquals(com.prodigalgal.remoteconnectmcp.protocol.TaskKind.DESKTOP, internal.command().kind());
        assertEquals("desktop", internal.command().requiredCapability());
        assertEquals(action, internal.command().desktop());
        assertEquals(60, internal.command().timeoutSeconds());
    }

    @Test
    void convertsBrowserTaskPayload() {
        var request = new AdminCreateTaskRequest("machine-1", "browser", "browser", "{\"operation\":\"snapshot\"}",
                "", Map.of(), 120, "retry-browser", "", "",
                "", "", "", "low", false, null, null, null);
        var internal = request.toInternal();

        assertEquals("machine-1", internal.machineId());
        assertEquals(com.prodigalgal.remoteconnectmcp.protocol.TaskKind.BROWSER, internal.command().kind());
        assertEquals("browser", internal.command().requiredCapability());
        assertEquals("{\"operation\":\"snapshot\"}", internal.command().command());
        assertNull(internal.command().desktop());
    }
}
