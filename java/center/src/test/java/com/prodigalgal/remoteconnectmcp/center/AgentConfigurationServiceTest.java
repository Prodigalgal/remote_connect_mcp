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

    @Test
    void rollbackPublishesANewGenerationWithoutRewindingTheAgentCounter() {
        var service = new AgentConfigurationService((org.springframework.jdbc.core.JdbcTemplate) null,
                (org.springframework.transaction.support.TransactionTemplate) null);

        var first = service.update("machine-a", new AgentConfigUpdateRequest(1000L, 2));
        var second = service.update("machine-a", new AgentConfigUpdateRequest(2000L, 4));
        var rolledBack = service.rollback("machine-a");

        assertEquals(1, first.generation());
        assertEquals(3, rolledBack.generation());
        assertEquals(1000L, rolledBack.pollIntervalMs());
        assertEquals(2, rolledBack.maxConcurrency());
        assertEquals(rolledBack, service.current("machine-a"));
    }

    @Test
    void expectedGenerationPreventsLostUpdatesButLegacyRequestsRemainCompatible() {
        var service = new AgentConfigurationService((org.springframework.jdbc.core.JdbcTemplate) null,
                (org.springframework.transaction.support.TransactionTemplate) null);

        var first = service.update("machine-a", new AgentConfigUpdateRequest(1000L, 2));
        var second = service.update("machine-a", new AgentConfigUpdateRequest(2000L, 3, first.generation()));
        assertEquals(2, second.generation());
        assertThrows(IllegalArgumentException.class,
                () -> service.update("machine-a", new AgentConfigUpdateRequest(3000L, 4, first.generation())));
        // Existing clients which do not send expected_generation retain the
        // intentionally supported unconditional update behavior.
        assertEquals(3, service.update("machine-a", new AgentConfigUpdateRequest(null, 5)).generation());
    }
}
