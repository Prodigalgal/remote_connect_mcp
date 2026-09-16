package com.prodigalgal.remoteconnectmcp.center;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.prodigalgal.remoteconnectmcp.protocol.ExecutionContract;
import com.prodigalgal.remoteconnectmcp.protocol.ScopeMode;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ExecutionSessionServiceTest {
    @Test
    void ensureIsIdempotentByPrincipalAndSessionAndCloseIsOwnerScoped() {
        var sessions = new ExecutionSessionService();
        var origin = new TaskOrigin("user-session", "token", "conversation-1");
        var contract = contract("machine-1", "session-1");

        var created = sessions.ensure(origin, contract);
        assertEquals("user-session", created.principalId());
        assertEquals("conversation-1", created.conversationId());
        assertEquals("active", created.status());
        assertEquals(1, sessions.count("user-session"));
        assertEquals(1, sessions.list("user-session", 0, 10).size());

        var touched = sessions.ensure(origin, contract);
        assertEquals(created.sessionId(), touched.sessionId());
        assertTrue(sessions.close(origin, "session-1"));
        assertEquals("closed", sessions.list("user-session", 0, 10).get(0).status());
        assertDoesNotThrow(() -> sessions.close(origin, "session-1"));
    }

    private static ExecutionContract contract(String machineId, String sessionId) {
        return new ExecutionContract(machineId, "host-1", ScopeMode.WORKSPACE, null, null, "/workspace",
                sessionId, "command", ExecutionContract.Budget.defaults(), Instant.now().plusSeconds(3600),
                "idempotency", "low", false, null);
    }
}
