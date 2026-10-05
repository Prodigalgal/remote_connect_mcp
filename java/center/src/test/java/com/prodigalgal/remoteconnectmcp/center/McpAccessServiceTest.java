package com.prodigalgal.remoteconnectmcp.center;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class McpAccessServiceTest {
    @Test
    void credentialCanUseOnlyItsSelectedToolsOnEachMachine() {
        var access = new McpAccessService();
        var origin = new TaskOrigin("user-a", "token-a", "conversation-a");
        access.grantTokenMachines("token-a", Map.of(
                "machine-a", Set.of("command", "task_read"),
                "machine-b", Set.of("browser")));

        assertTrue(access.canReadMachine(origin, "machine-a"));
        assertTrue(access.canReadMachine(origin, "machine-b"));
        assertFalse(access.canReadMachine(origin, "machine-c"));
        assertDoesNotThrow(() -> access.authorizeTool(origin, "machine-a", "command"));
        assertThrows(SecurityException.class, () -> access.authorizeTool(origin, "machine-a", "desktop"));
        assertThrows(SecurityException.class, () -> access.authorizeTool(origin, "machine-a", "browser"));
        assertDoesNotThrow(() -> access.authorizeTool(origin, "machine-b", "browser"));
        assertThrows(SecurityException.class, () -> access.authorizeTool(origin, "machine-b", "command"));
    }

    @Test
    void legacyWildcardGrantCanBeAppliedToAnyMachine() {
        var access = new McpAccessService();
        var origin = new TaskOrigin("user-wildcard", "token-w", "conversation-w");
        access.grantTokenMachines("token-w", Map.of("*", Set.of("command", "task_read")));

        assertDoesNotThrow(() -> access.authorizeTool(origin, "machine-1", "command"));
        assertDoesNotThrow(() -> access.authorizeTool(origin, "machine-2", "command"));
        assertThrows(SecurityException.class, () -> access.authorizeTool(origin, "machine-2", "browser"));
    }

    @Test
    void permissionsDoNotLeakBetweenCredentialsOwnedByTheSamePrincipal() {
        var access = new McpAccessService();
        var first = new TaskOrigin("owner", "token-chatgpt", "conversation-a");
        var second = new TaskOrigin("owner", "token-cli", "conversation-b");
        access.grantTokenMachines("token-chatgpt", Map.of("machine-a", Set.of("command")));
        access.grantTokenMachines("token-cli", Map.of("machine-a", Set.of("browser")));

        assertDoesNotThrow(() -> access.authorizeTool(first, "machine-a", "command"));
        assertThrows(SecurityException.class, () -> access.authorizeTool(first, "machine-a", "browser"));
        assertDoesNotThrow(() -> access.authorizeTool(second, "machine-a", "browser"));
        assertThrows(SecurityException.class, () -> access.authorizeTool(second, "machine-a", "command"));
    }

    @Test
    void machinePermissionsRejectUnsupportedToolsAndEmptySelections() {
        assertThrows(IllegalArgumentException.class,
                () -> McpAccessService.normalizeMachinePermissions(Map.of("machine-a", Set.of("admin")), false));
        assertThrows(IllegalArgumentException.class,
                () -> McpAccessService.normalizeMachinePermissions(Map.of(), false));
        assertThrows(IllegalArgumentException.class,
                () -> McpAccessService.normalizeMachinePermissions(Map.of("machine-a", Set.of()), false));
        assertThrows(IllegalArgumentException.class,
                () -> McpAccessService.normalizeMachinePermissions(Map.of("*", Set.of("command")), false));
    }

    @Test
    void serverCreatedTasksKeepTheirInternalPermissionPath() {
        var access = new McpAccessService();
        assertDoesNotThrow(() -> access.authorizeTool(TaskOrigin.configured(), "machine-b", "command"));
    }
}
