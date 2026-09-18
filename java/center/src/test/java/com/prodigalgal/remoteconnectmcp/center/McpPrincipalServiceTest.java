package com.prodigalgal.remoteconnectmcp.center;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class McpPrincipalServiceTest {
    @Test
    void issuesResolvesAndRevokesAnOpaqueMemoryToken() {
        var service = new McpPrincipalService(new CenterTokenConfig());
        var issued = service.issue(new McpPrincipalService.IssueRequest(
                "user-a", "User A", 3600L, Set.of("mcp:read", "mcp:execute")));

        var resolved = service.resolve(issued.token()).orElseThrow();
        assertEquals("user-a", resolved.principalId());
        assertEquals(issued.tokenId(), resolved.tokenId());
        assertTrue(resolved.allows("mcp:execute"));
        assertTrue(service.revoke(issued.tokenId()));
        assertTrue(service.resolve(issued.token()).isEmpty());
    }

    @Test
    void rejectsInvalidPrincipalAndTtlBeforeIssuing() {
        var service = new McpPrincipalService(new CenterTokenConfig());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.issue(new McpPrincipalService.IssueRequest("bad id", "", 3600L, Set.of())));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.issue(new McpPrincipalService.IssueRequest(TaskOrigin.CONFIGURED_PRINCIPAL, "", 3600L, Set.of())));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.issue(new McpPrincipalService.IssueRequest("user-b", "", 60L, Set.of())));
    }
}
