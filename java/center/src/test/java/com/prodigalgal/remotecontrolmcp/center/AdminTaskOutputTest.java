package com.prodigalgal.remotecontrolmcp.center;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.prodigalgal.remotecontrolmcp.protocol.PollRequest;
import com.prodigalgal.remotecontrolmcp.protocol.RegisterRequest;
import com.prodigalgal.remotecontrolmcp.protocol.TaskCommand;
import com.prodigalgal.remotecontrolmcp.protocol.TaskProgressUpdate;
import com.prodigalgal.remotecontrolmcp.protocol.TaskUpdateRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class AdminTaskOutputTest {
    @Test
    void holdsPartialGbkUntilItIsCompleteAndReturnsUtf8WithoutChangingRawBytes() throws Exception {
        var registry = AgentRegistry.forTest("enroll");
        var machine = registry.register(new RegisterRequest("gbk-agent", "host", "host",
                "windows", "amd64", "test", "C:/", List.of("command")), "enroll");
        var tasks = new TaskService(registry);
        var task = tasks.create(new CreateTaskRequest(machine.machineId(),
                new TaskCommand("", null, null, "work", "C:/", Map.of(), 30, null, null), "gbk-output"));
        tasks.poll(machine.machineId(), new PollRequest(List.of(), 1, List.of("command")));
        tasks.updateState(machine.machineId(), task.id(),
                new TaskUpdateRequest(TaskStatus.RUNNING, null, null, null, null, false));
        var bytes = "中".getBytes(java.nio.charset.Charset.forName("GBK"));
        tasks.appendOutput(machine.machineId(), task.id(), 0, new byte[] {bytes[0]});
        try (var async = new CenterAsyncExecutor()) {
            var controller = new AdminController(new AdminTokens(), registry, tasks, null, null, null, async);
            var partial = (Map<?, ?>) controller.output("Bearer admin", task.id(), 0, 64, 0, -1, "GBK")
                    .get(1, TimeUnit.SECONDS).getBody();
            assertEquals("", partial.get("text"));
            assertEquals(0L, partial.get("next_cursor"));
            assertEquals(1, partial.get("pending_bytes"));
            assertEquals(false, partial.get("more"));
            var before = (TaskView) partial.get("task");
            var waiting = controller.output("Bearer admin", task.id(), 0, 64, 1000, before.changeSequence(), "GBK");
            assertThrows(TimeoutException.class, () -> waiting.get(30, TimeUnit.MILLISECONDS));
            tasks.appendOutput(machine.machineId(), task.id(), 1, new byte[] {bytes[1]});
            var decoded = (Map<?, ?>) waiting.get(1, TimeUnit.SECONDS).getBody();
            assertEquals("中", decoded.get("text"));
            assertEquals("UTF-8", decoded.get("encoding"));
            assertEquals("GBK", decoded.get("source_encoding"));
            assertEquals(2L, decoded.get("next_cursor"));
            assertEquals(Base64.getEncoder().encodeToString(bytes), decoded.get("data_base64"));
            assertEquals("中", new String(tasks.readOutput(task.id(), 0, 64).data(), java.nio.charset.Charset.forName("GBK")));
        }
    }

    @Test
    void wakesForOutputProgressAndCompletionWithoutRepeatingOldLogs() throws Exception {
        var registry = AgentRegistry.forTest("enroll");
        var machine = registry.register(new RegisterRequest("command-agent", "host", "host",
                "linux", "amd64", "test", "/tmp", List.of("command")), "enroll");
        var tasks = new TaskService(registry);
        var task = tasks.create(new CreateTaskRequest(machine.machineId(),
                new TaskCommand("", null, null, "work", "/tmp", Map.of(), 30, null, null), "live-output"));
        tasks.poll(machine.machineId(), new PollRequest(List.of(), 1, List.of("command")));
        tasks.updateState(machine.machineId(), task.id(),
                new TaskUpdateRequest(TaskStatus.RUNNING, null, null, null, null, false));

        try (var async = new CenterAsyncExecutor()) {
            var controller = new AdminController(new AdminTokens(), registry, tasks, null, null, null, async);
            var first = controller.output("Bearer admin", task.id(), 0, 64, 0, -1).get(1, TimeUnit.SECONDS);
            assertEquals("no-store", first.getHeaders().getFirst("Cache-Control"));
            var before = (TaskView) ((Map<?, ?>) first.getBody()).get("task");
            var waiting = controller.output("Bearer admin", task.id(), 0, 64, 1000, before.changeSequence());
            assertThrows(TimeoutException.class, () -> waiting.get(30, TimeUnit.MILLISECONDS));

            var bytes = "步骤一\n".getBytes(StandardCharsets.UTF_8);
            tasks.appendOutput(machine.machineId(), task.id(), 0, bytes);
            var output = (Map<?, ?>) waiting.get(1, TimeUnit.SECONDS).getBody();
            assertEquals("步骤一\n", output.get("text"));
            assertEquals(Base64.getEncoder().encodeToString(bytes), output.get("data_base64"));
            assertEquals((long) bytes.length, output.get("next_cursor"));
            var running = (TaskView) output.get("task");
            assertEquals(TaskStatus.RUNNING, running.status());

            var progressWait = controller.output("Bearer admin", task.id(), bytes.length, 64,
                    1000, running.changeSequence());
            assertThrows(TimeoutException.class, () -> progressWait.get(30, TimeUnit.MILLISECONDS));
            tasks.updateProgress(machine.machineId(), task.id(),
                    new TaskProgressUpdate("build", 50, "step two", 1L, 2L, "steps"), running.attempt());
            var progress = (Map<?, ?>) progressWait.get(1, TimeUnit.SECONDS).getBody();
            assertEquals("", progress.get("text"));
            assertEquals((long) bytes.length, progress.get("next_cursor"));
            var progressing = (TaskView) progress.get("task");
            assertEquals("step two", progressing.progressMessage());

            var finalWait = controller.output("Bearer admin", task.id(), bytes.length, 64,
                    1000, progressing.changeSequence());
            assertThrows(TimeoutException.class, () -> finalWait.get(30, TimeUnit.MILLISECONDS));
            tasks.updateState(machine.machineId(), task.id(),
                    new TaskUpdateRequest(TaskStatus.COMPLETED, 0, null, null, null, false));
            var completed = (Map<?, ?>) finalWait.get(1, TimeUnit.SECONDS).getBody();
            assertEquals("", completed.get("text"));
            assertEquals(TaskStatus.COMPLETED, ((TaskView) completed.get("task")).status());
        }
    }

    @Test
    void rejectsUnauthenticatedOrUnboundedWaitBeforeAllocatingAWaiter() throws Exception {
        try (var async = new CenterAsyncExecutor()) {
            var controller = new AdminController(new AdminTokens(), null, null, null, null, null, async);
            assertEquals(401, controller.output("Bearer wrong", "unknown", 0, 64, 25_000, -1)
                    .get(1, TimeUnit.SECONDS).getStatusCode().value());
            assertEquals(400, controller.output("Bearer admin", "unknown", 0, 64, 25_001, -1)
                    .get(1, TimeUnit.SECONDS).getStatusCode().value());
            assertEquals(400, controller.output("Bearer admin", "unknown", 0, 65_537, 25_000, -1)
                    .get(1, TimeUnit.SECONDS).getStatusCode().value());
        }
    }

    private static final class AdminTokens extends CenterTokenConfig {
        @Override
        public boolean acceptsAdmin(String candidate) {
            return "admin".equals(candidate);
        }
    }
}
