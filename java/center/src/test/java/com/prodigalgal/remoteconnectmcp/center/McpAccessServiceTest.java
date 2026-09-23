package com.prodigalgal.remoteconnectmcp.center;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

class McpAccessServiceTest {
    @Test
    void userNeedsExplicitMachineGrant() {
        var access = new McpAccessService();
        var origin = new TaskOrigin("user-a", "token-a", "conversation-a");

        assertThrows(SecurityException.class, () -> access.authorizeMachine(origin, "machine-a", "read"));
        access.grantMachine("user-a", "machine-a", Set.of("read"), null);
        assertDoesNotThrow(() -> access.authorizeMachine(origin, "machine-a", "read"));
        assertThrows(SecurityException.class, () -> access.authorizeMachine(origin, "machine-a", "execute"));

        access.grantMachine("user-a", "machine-a", Set.of("read", "execute"), null);
        assertDoesNotThrow(() -> access.authorizeExecution(origin, "machine-a"));
    }

    @Test
    void wildcardMachineGrantAuthorizesAnyMachine() {
        var access = new McpAccessService();
        var origin = new TaskOrigin("user-wildcard", "token-w", "conversation-w");

        assertThrows(SecurityException.class, () -> access.authorizeExecution(origin, "machine-1"));
        assertThrows(SecurityException.class, () -> access.authorizeExecution(origin, "machine-2"));

        access.grantMachine("user-wildcard", "*", Set.of("read", "execute"), null);
        assertDoesNotThrow(() -> access.authorizeMachine(origin, "machine-1", "read"));
        assertDoesNotThrow(() -> access.authorizeExecution(origin, "machine-1"));
        assertDoesNotThrow(() -> access.authorizeExecution(origin, "machine-2"));
    }

    @Test
    void grantsExpireAndConfiguredPrincipalRemainsExplicitlyGlobal() {
        var access = new McpAccessService();
        var origin = new TaskOrigin("user-b", "token-b", "conversation-b");
        access.grantMachine("user-b", "machine-b", Set.of("admin"), Instant.now().minusSeconds(1));
        assertThrows(SecurityException.class, () -> access.authorizeMachine(origin, "machine-b", "read"));

        assertDoesNotThrow(() -> access.authorizeExecution(TaskOrigin.configured(), "machine-b"));
        assertEquals(1, access.machineCount("user-b"));
    }

    @Test
    void invalidGrantScopesFailBeforeStorage() {
        var access = new McpAccessService();
        assertThrows(IllegalArgumentException.class,
                () -> access.grantMachine("user-c", "machine-c", Set.of("invalid_scope"), null));
    }
}
