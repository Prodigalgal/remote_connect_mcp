package com.prodigalgal.remoteconnectmcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentResourceBudgetTest {
    @Test
    void grantsOnlyTheRemainingAggregateBytesAndReleasesOnSpoolClose(@TempDir Path stateDir) throws Exception {
        var budget = new AgentResourceBudget(10);
        assertEquals(6, budget.tryReserve(6));
        assertEquals(4, budget.tryReserve(8));
        assertEquals(0, budget.tryReserve(1));
        budget.release(10);
        assertEquals(8, budget.tryReserve(8));

        try (var spool = new TaskOutputSpool(stateDir, "task", 100, budget)) {
            var bytes = "1234567".getBytes(StandardCharsets.UTF_8);
            spool.append(bytes, 0, bytes.length);
            assertEquals(2, spool.size());
            assertTrue(spool.truncated());
        }
        budget.release(8);
        assertEquals(0, budget.usedBytes());
        var spoolDir = stateDir.resolve("output-spool");
        if (Files.isDirectory(spoolDir)) {
            try (var files = Files.list(spoolDir)) {
                assertTrue(files.findAny().isEmpty());
            }
        }
    }
}
