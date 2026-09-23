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
                "/srv", List.of("command", "browser"), runtime);
        assertEquals(runtime, request.metadata().runtime());
    }

    @Test
    void validatesExplicitWorkspaceExecutionContract() {
        var contract = new ExecutionContract("machine-1", "host-1", LaneMode.WRITE,
                "session-1",
                "command", new ExecutionContract.Budget(300, 64L * 1024 * 1024, 8L * 1024 * 1024, 32),
                Instant.now().plusSeconds(300), "retry-1", "low", false, null);
        var task = new TaskCommand("task-contract", TaskKind.COMMAND, "command", "echo ok",
                "/srv/project/.rcm-worktrees/wt-1", Map.of(), 30, null, Instant.now(), contract);

        assertDoesNotThrow(() -> ProtocolValidation.validateTask(task));
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
        var windows = new TaskCommand("task-windows", TaskKind.DESKTOP, "desktop", null, null, Map.of(), 30,
                new TaskCommand.DesktopAction("windows", null, java.util.List.of(), null), Instant.now());
        assertDoesNotThrow(() -> ProtocolValidation.validateTask(windows));
    }

    @Test
    void acceptsPointerAndRegionDesktopActions() {
        for (var operation : java.util.List.of("double_click", "right_click", "move")) {
            var action = new TaskCommand.DesktopAction(operation, null, java.util.List.of(), null,
                    null, 100, 200, null);
            assertDoesNotThrow(() -> ProtocolValidation.validateTask(new TaskCommand(
                    "task-" + operation, TaskKind.DESKTOP, "desktop", null, null, Map.of(), 30, action, Instant.now())));
        }
        var region = new TaskCommand.DesktopAction("screenshot_region", null, java.util.List.of(), null,
                null, 10, 20, null, 640, 480, null, null, null);
        assertDoesNotThrow(() -> ProtocolValidation.validateTask(new TaskCommand(
                "task-region", TaskKind.DESKTOP, "desktop", null, null, Map.of(), 30, region, Instant.now())));
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

    @Test
    void acceptsPendingWebToAgentTransferMarker() {
        var action = new FileTransferAction(FileTransferAction.WEB_TO_AGENT, "transfer-pending", "artifact-pending",
                "", "/srv/incoming", "payload.bin", "application/octet-stream", 0L, "", false);
        assertDoesNotThrow(() -> ProtocolValidation.validateTask(new TaskCommand(
                "task-pending", TaskKind.FILE_TRANSFER, "file_transfer", null, "/srv", Map.of(), 0, null,
                Instant.now(), null, action)));
    }

    @Test
    void allowsTransferDisplayNameToDefaultToPathLeaf() {
        var action = new FileTransferAction(FileTransferAction.AGENT_TO_WEB, "transfer-leaf", "artifact-leaf",
                "/srv/results/report.pdf", "", "", "application/pdf", 0L, "", false);
        assertEquals("report.pdf", action.displayName());
        assertDoesNotThrow(() -> ProtocolValidation.validateTask(new TaskCommand(
                "task-leaf", TaskKind.FILE_TRANSFER, "file_transfer", null, "/srv", Map.of(), 0, null,
                Instant.now(), null, action)));
    }
}
