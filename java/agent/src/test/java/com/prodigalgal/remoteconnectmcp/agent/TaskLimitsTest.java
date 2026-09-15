package com.prodigalgal.remoteconnectmcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.prodigalgal.remoteconnectmcp.protocol.ExecutionContract;
import com.prodigalgal.remoteconnectmcp.protocol.ScopeMode;
import com.prodigalgal.remoteconnectmcp.protocol.TaskCommand;
import com.prodigalgal.remoteconnectmcp.protocol.TaskKind;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskLimitsTest {
    @Test
    void contractCanOnlyNarrowAgentOutputAndOperationLimits() {
        var config = new AgentConfig(URI.create("http://127.0.0.1"), "token", "agent", "host-1",
                "/srv", ScopeMode.UNRESTRICTED, null, List.of("command"), false,
                Path.of("."), Duration.ofSeconds(1), 1, 16L * 1024 * 1024);
        var contract = new ExecutionContract("machine-1", "host-1", ScopeMode.UNRESTRICTED, null, null, null,
                "session-1", "command", new ExecutionContract.Budget(7, 2L * 1024 * 1024, 1024, 1,
                        64L * 1024 * 1024, 2),
                Instant.now().plusSeconds(60), "retry-1", "low", false, null);
        var task = new TaskCommand("task-1", TaskKind.COMMAND, "command", "echo ok", "/srv", Map.of(), 0,
                null, Instant.now(), contract);

        assertEquals(2L * 1024 * 1024, TaskLimits.outputBytes(config, task));
        assertEquals(7, TaskLimits.timeoutSeconds(task, 300));
        assertEquals(1024, TaskLimits.artifactBytes(task, 8L * 1024 * 1024));
        assertEquals(64L * 1024 * 1024, TaskLimits.rssBytes(config, task));
        assertEquals(2, TaskLimits.cpuSeconds(config, task));
        assertEquals(1, TaskLimits.childProcesses(config, task));
    }

    @Test
    void absentContractKeepsAgentDefaults() {
        var config = new AgentConfig(URI.create("http://127.0.0.1"), "token", "agent", "host-1",
                "/srv", ScopeMode.UNRESTRICTED, null, List.of("command"), false,
                Path.of("."), Duration.ofSeconds(1), 1, 4L * 1024 * 1024);
        var task = new TaskCommand("task-1", TaskKind.COMMAND, "command", "echo ok", "/srv", Map.of(), 0,
                null, Instant.now());

        assertEquals(4L * 1024 * 1024, TaskLimits.outputBytes(config, task));
        assertEquals(300, TaskLimits.timeoutSeconds(task, 300));
    }
}
