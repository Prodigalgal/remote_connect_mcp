package com.prodigalgal.remoteconnectmcp.center;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

class McpAccessServiceTest {
    @Test
    void userNeedsExplicitMachineAndProjectGrants() {
        var access = new McpAccessService();
        var origin = new TaskOrigin("user-a", "token-a", "conversation-a");

        assertThrows(SecurityException.class, () -> access.authorizeMachine(origin, "machine-a", "read"));
        access.grantMachine("user-a", "machine-a", Set.of("read"), null);
        assertDoesNotThrow(() -> access.authorizeMachine(origin, "machine-a", "read"));
        assertThrows(SecurityException.class, () -> access.authorizeMachine(origin, "machine-a", "execute"));

        access.grantMachine("user-a", "machine-a", Set.of("read", "execute"), null);
        assertThrows(SecurityException.class, () -> access.authorizeExecution(origin, "machine-a", "project-a"));
        access.grantProject("user-a", "project-a", Set.of("write"), null);
        assertDoesNotThrow(() -> access.authorizeExecution(origin, "machine-a", "project-a"));
    }

    @Test
    void grantsExpireAndCompatibilityPrincipalRemainsExplicitlyGlobal() {
        var access = new McpAccessService();
        var origin = new TaskOrigin("user-b", "token-b", "conversation-b");
        access.grantMachine("user-b", "machine-b", Set.of("admin"), Instant.now().minusSeconds(1));
        assertThrows(SecurityException.class, () -> access.authorizeMachine(origin, "machine-b", "read"));

        assertDoesNotThrow(() -> access.authorizeExecution(TaskOrigin.shared(), "machine-b", "project-b"));
        assertEquals(1, access.machineCount("user-b"));
        assertEquals(0, access.projectCount("user-b"));
    }

    @Test
    void invalidGrantScopesFailBeforeStorage() {
        var access = new McpAccessService();
        assertThrows(IllegalArgumentException.class,
                () -> access.grantMachine("user-c", "machine-c", Set.of("write"), null));
        assertThrows(IllegalArgumentException.class,
                () -> access.grantProject("user-c", "project-c", Set.of("execute"), null));
    }
}
