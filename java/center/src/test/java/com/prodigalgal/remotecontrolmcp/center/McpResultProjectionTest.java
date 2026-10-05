package com.prodigalgal.remotecontrolmcp.center;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.prodigalgal.remotecontrolmcp.protocol.PollRequest;
import com.prodigalgal.remotecontrolmcp.protocol.RegisterRequest;
import com.prodigalgal.remotecontrolmcp.protocol.TaskCommand;
import com.prodigalgal.remotecontrolmcp.protocol.TaskKind;
import com.prodigalgal.remotecontrolmcp.protocol.TaskUpdateRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class McpResultProjectionTest {
    @Test
    void taskSummaryKeepsRecoveryHandlesWithoutEchoingInternalDetails() {
        var command = new TaskCommand("", TaskKind.COMMAND, "command", "echo private", "/srv/private",
                Map.of(), 0, null, null);
        var state = new TaskState("task-1", "machine-1", command, "retry-key", Instant.now());
        var queued = McpConfiguration.taskMap(new TaskView(state));

        assertEquals(Set.of("id", "machine_id", "kind", "status", "change_seq"), queued.keySet());
        assertFalse(queued.toString().contains("private"));
        var detail = McpConfiguration.taskDetailMap(new TaskView(state));
        assertEquals("echo private", detail.get("command"));
        assertEquals("/srv/private", detail.get("cwd"));
        assertTrue(detail.containsKey("created_at"));
        assertFalse(detail.containsKey("execution_session_id"));

        state.status(TaskStatus.FAILED);
        state.exitCode(2);
        state.error("process failed");
        state.outputBytes(100);
        state.outputTruncated(true);
        state.attempt(3);
        state.progressPhase("running");
        state.progressPercent(50);
        state.progressCurrent(50L);
        state.progressTotal(100L);
        var failed = McpConfiguration.taskMap(new TaskView(state));

        assertEquals(2, failed.get("exit_code"));
        assertEquals("process failed", failed.get("error"));
        assertEquals(true, failed.get("output_truncated"));
        assertEquals(3, failed.get("attempt"));
        assertEquals(50, failed.get("progress_percent"));
        assertFalse(failed.containsKey("progress_current"));
        assertFalse(failed.containsKey("command"));
        assertFalse(failed.containsKey("execution_session_id"));
    }

    @Test
    void emptyOutputIsOmittedAndFailedShortWaitShowsLogTail() {
        var registry = AgentRegistry.forTest("enroll-test");
        var machine = registry.register(new RegisterRequest("command-agent", "host-a", "host-a", "linux", "amd64",
                "dev", "/srv", List.of("command")), "enroll-test");
        var tasks = new TaskService(registry);
        var origin = TaskOrigin.configured();
        var command = new TaskCommand("", TaskKind.COMMAND, "command", "test", "/srv", Map.of(), 0, null, null);
        var created = tasks.create(new CreateTaskRequest(machine.machineId(), command, "compact-result"), "mcp", origin);

        var empty = (Map<?, ?>) McpConfiguration.taskResult(tasks, origin, created, 0, 8192).structuredContent();
        assertFalse(empty.containsKey("output"));

        var leased = tasks.poll(machine.machineId(), new PollRequest(List.of(), 1, List.of("command"))).task();
        tasks.updateState(machine.machineId(), created.id(),
                new TaskUpdateRequest(TaskStatus.RUNNING, null, null, null, null, false), leased.attempt());
        var log = ("x".repeat(18000) + "\nfatal error\n").getBytes(StandardCharsets.UTF_8);
        tasks.appendOutput(machine.machineId(), created.id(), 0, log, leased.attempt());
        tasks.updateState(machine.machineId(), created.id(),
                new TaskUpdateRequest(TaskStatus.FAILED, 1, "process failed", null, null, false), leased.attempt());

        var failed = tasks.findFor(origin, created.id()).orElseThrow();
        var payload = (Map<?, ?>) McpConfiguration.immediateTaskResult(tasks, origin, new TaskView(failed)).structuredContent();
        var output = (Map<?, ?>) payload.get("output");
        assertTrue(((String) output.get("text")).endsWith("fatal error\n"));
        assertEquals(log.length - 16 * 1024L, ((Number) output.get("cursor")).longValue());
        assertEquals(created.id(), ((Map<?, ?>) payload.get("task")).get("id"));

        var detailed = (Map<?, ?>) McpConfiguration.taskResult(tasks, origin, new TaskView(failed),
                0, 100, true).structuredContent();
        assertEquals("test", ((Map<?, ?>) detailed.get("task")).get("command"));
        assertEquals(0L, ((Number) ((Map<?, ?>) detailed.get("output")).get("cursor")).longValue());
    }

    @Test
    void transferSummaryKeepsHandlesAndOnlyUsefulProgress() {
        var inProgress = new ArtifactTransferService.TransferDescriptor("transfer-1", "artifact-1", "agent-to-web",
                "task-1", "owner", "machine-1", "report.txt", "text/plain", 100, "digest", "running",
                null, "https://example.test/file", 25);
        var summary = McpConfiguration.transferMap(inProgress);

        assertEquals(Set.of("transfer_id", "artifact_id", "status", "bytes", "bytes_transferred"), summary.keySet());
        assertEquals(25L, summary.get("bytes_transferred"));
        assertFalse(summary.containsKey("download_url"));

        var complete = new ArtifactTransferService.TransferDescriptor("transfer-1", "artifact-1", "agent-to-web",
                "task-1", "owner", "machine-1", "report.txt", "text/plain", 100, "digest", "delivered",
                null, "https://example.test/file", 100);
        assertEquals(Set.of("transfer_id", "artifact_id", "status"),
                McpConfiguration.transferMap(complete).keySet());

        var detail = McpConfiguration.transferDetailMap(inProgress, false);
        assertEquals("agent-to-web", detail.get("direction"));
        assertEquals("task-1", detail.get("task_id"));
        assertEquals("report.txt", detail.get("file_name"));
        assertFalse(detail.containsKey("download_url"));
        assertFalse(detail.containsKey("principal_id"));
    }
}
