package com.prodigalgal.remoteconnectmcp.protocol;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProtocolValidationTest {
    @Test
    void registrationCarriesInitialRuntimeDescriptor() {
        var runtime = new AgentRuntimeDescriptor(1, 7, 2, 1,
                2L * 1024 * 1024, 8L * 1024 * 1024, 8, 60, 0, 0,
                false, true);
        var request = new RegisterRequest("agent", "host", "node", "linux", "amd64", "v1",
                "/srv", ScopeMode.WORKSPACE, "/srv", List.of("command", "browser"), runtime);
        assertEquals(runtime, request.metadata().runtime());
    }

    @Test
    void validatesExplicitWorktreeExecutionContract() {
        var contract = new ExecutionContract("machine-1", "host-1", ScopeMode.WORKTREE,
                "project-1", "worktree-1", "/srv/project/.rcm-worktrees/wt-1", "session-1",
                "command", new ExecutionContract.Budget(300, 64L * 1024 * 1024, 8L * 1024 * 1024, 32),
                Instant.now().plusSeconds(300), "retry-1", "low", false, null);
        var task = new TaskCommand("task-contract", TaskKind.COMMAND, "command", "echo ok",
                "/srv/project/.rcm-worktrees/wt-1", Map.of(), 30, null, Instant.now(), contract);

        assertDoesNotThrow(() -> ProtocolValidation.validateTask(task));
    }

    @Test
    void rejectsUnrestrictedContractWithAPathRoot() {
        assertThrows(IllegalArgumentException.class, () -> new ExecutionContract("machine-1", "host-1",
                ScopeMode.UNRESTRICTED, null, null, "/srv/project", "session-1", "command",
                ExecutionContract.Budget.defaults(), Instant.now().plusSeconds(60), "", "low", false, null));
    }

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
    void rejectsWorkspaceMetadataWithDefaultOutsideRoot() {
        var metadata = new AgentMetadata("agent", "host", "host", "linux", "amd64", "dev",
                "/srv/other", ScopeMode.WORKSPACE, "/srv/project", java.util.List.of("command"));
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

    @Test
    void rejectsCapabilityKindConfusion() {
        var desktopAsCommand = new TaskCommand("task-confused", TaskKind.COMMAND, "desktop", "echo blocked",
                "/srv", Map.of(), 30, null, Instant.now());
        var browserAsDesktop = new TaskCommand("task-confused-desktop", TaskKind.DESKTOP, "browser", null,
                "/srv", Map.of(), 30,
                new TaskCommand.DesktopAction("screens", null, List.of(), null), Instant.now());
        assertThrows(IllegalArgumentException.class, () -> ProtocolValidation.validateTask(desktopAsCommand));
        assertThrows(IllegalArgumentException.class, () -> ProtocolValidation.validateTask(browserAsDesktop));
    }
}
