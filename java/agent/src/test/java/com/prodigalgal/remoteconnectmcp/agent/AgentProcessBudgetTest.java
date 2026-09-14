package com.prodigalgal.remoteconnectmcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AgentProcessBudgetTest {
    @Test
    void reservesOnlyAvailableCapacityAndReleasesSafely() {
        var budget = new AgentProcessBudget(3);

        assertEquals(2, budget.tryReserve(2));
        assertEquals(1, budget.tryReserve(4));
        assertEquals(0, budget.tryReserve(1));
        assertEquals(3, budget.usedProcesses());

        budget.release(2);
        assertEquals(1, budget.usedProcesses());
        assertEquals(2, budget.tryReserve(2));
        budget.release(99);
        assertEquals(0, budget.usedProcesses());
    }

    @Test
    void rejectsInvalidCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new AgentProcessBudget(0));
        assertThrows(IllegalArgumentException.class, () -> new AgentProcessBudget(4097));
    }
}
