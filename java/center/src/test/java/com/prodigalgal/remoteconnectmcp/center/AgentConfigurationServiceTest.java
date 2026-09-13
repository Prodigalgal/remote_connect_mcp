package com.prodigalgal.remoteconnectmcp.center;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.prodigalgal.remoteconnectmcp.protocol.AgentConfigUpdate;
import org.junit.jupiter.api.Test;

class AgentConfigurationServiceTest {
    @Test
    void versionsMemoryUpdatesAndKeepsUnchangedFields() {
        var service = new AgentConfigurationService((org.springframework.jdbc.core.JdbcTemplate) null,
                (org.springframework.transaction.support.TransactionTemplate) null);

        var first = service.update("machine-a", new AgentConfigUpdateRequest(1000L, 2));
        assertEquals(new AgentConfigUpdate(1, 1000, 2), first);
        assertEquals(first, service.current("machine-a"));

        var second = service.update("machine-a", new AgentConfigUpdateRequest(null, 4));
        assertEquals(new AgentConfigUpdate(2, 1000, 4), second);
    }

    @Test
    void rejectsUnsafeValuesBeforeTheyCanReachAnAgent() {
        var service = new AgentConfigurationService((org.springframework.jdbc.core.JdbcTemplate) null,
                (org.springframework.transaction.support.TransactionTemplate) null);

        assertThrows(IllegalArgumentException.class,
                () -> service.update("machine-a", new AgentConfigUpdateRequest(10L, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> service.update("machine-a", new AgentConfigUpdateRequest(100L, 33)));
        assertThrows(IllegalArgumentException.class,
                () -> service.update("machine-a", new AgentConfigUpdateRequest(100L, null)));
    }
}
