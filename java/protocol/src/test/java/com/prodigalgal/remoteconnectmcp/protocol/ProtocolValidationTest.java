package com.prodigalgal.remoteconnectmcp.protocol;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProtocolValidationTest {
    @Test
    void acceptsBoundedCommand() {
        var task = new TaskCommand("task-1", TaskKind.COMMAND, "command", "echo ok", "work", Map.of("LANG", "C"), 30, null, Instant.now());
        assertDoesNotThrow(() -> ProtocolValidation.validateTask(task));
    }

    @Test
    void rejectsInvalidEnvironmentKey() {
        var task = new TaskCommand("task-1", TaskKind.COMMAND, "command", "echo ok", null, Map.of("BAD-KEY", "x"), 0, null, Instant.now());
        assertThrows(IllegalArgumentException.class, () -> ProtocolValidation.validateTask(task));
    }

    @Test
    void rejectsWorkspaceMetadataWithoutRoot() {
        var metadata = new AgentMetadata("agent", "host", "host", "linux", "amd64", "dev", "/tmp", ScopeMode.WORKSPACE, null, java.util.List.of("command"));
        assertThrows(IllegalArgumentException.class, () -> ProtocolValidation.validateMetadata(metadata));
    }

    @Test
    void acceptsBoundedDesktopInputActions() {
        var click = new TaskCommand("task-click", TaskKind.DESKTOP, "desktop", null, null, Map.of(), 30,
                new TaskCommand.DesktopAction("click", null, java.util.List.of(), null, null, 100, 200, null), Instant.now());
        var type = new TaskCommand("task-type", TaskKind.DESKTOP, "desktop", null, null, Map.of(), 30,
                new TaskCommand.DesktopAction("type", null, java.util.List.of(), null, "hello", null, null, null), Instant.now());
        assertDoesNotThrow(() -> ProtocolValidation.validateTask(click));
        assertDoesNotThrow(() -> ProtocolValidation.validateTask(type));
    }

    @Test
    void acceptsExtendedDesktopActions() {
        var drag = new TaskCommand("task-drag", TaskKind.DESKTOP, "desktop", null, null, Map.of(), 30,
                new TaskCommand.DesktopAction("drag", null, java.util.List.of(), null, null, 10, 20, null,
                        100, 120, 250, 1, null), Instant.now());
        var clipboard = new TaskCommand("task-clipboard", TaskKind.DESKTOP, "desktop", null, null, Map.of(), 30,
                new TaskCommand.DesktopAction("clipboard_write", null, java.util.List.of(), null,
                        "hello", null, null, null), Instant.now());
        var focus = new TaskCommand("task-focus", TaskKind.DESKTOP, "desktop", null, null, Map.of(), 30,
                new TaskCommand.DesktopAction("focus", null, java.util.List.of(), null, null, null, null, null,
                        null, null, null, null, "Notepad"), Instant.now());
        assertDoesNotThrow(() -> ProtocolValidation.validateTask(drag));
        assertDoesNotThrow(() -> ProtocolValidation.validateTask(clipboard));
        assertDoesNotThrow(() -> ProtocolValidation.validateTask(focus));
    }

    @Test
    void rejectsDesktopExecutableForNonLaunchAction() {
        var task = new TaskCommand("task-screen", TaskKind.DESKTOP, "desktop", null, null, Map.of(), 30,
                new TaskCommand.DesktopAction("screens", "powershell.exe", java.util.List.of(), null), Instant.now());
        assertThrows(IllegalArgumentException.class, () -> ProtocolValidation.validateTask(task));
    }
}
