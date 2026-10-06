package com.prodigalgal.remotecontrolmcp.center;

import com.prodigalgal.remotecontrolmcp.protocol.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import static org.junit.jupiter.api.Assertions.*;

class AdminFilesControllerTest {
    @Test void authorizationAndInvalidWaitCannotCreateWork() throws Exception {
        var registry = AgentRegistry.forTest("enroll"); var tasks = new TaskService(registry);
        try (var async = new CenterAsyncExecutor()) {
            var controller = new AdminFilesController(tokens(), registry, tasks, null, async);
            var request = new AdminFilesController.OperationBody(Map.of("operation", "roots"), null, "invalid-wait", 15001);
            assertEquals(401, controller.operate("Bearer wrong", "missing", request).get(1, TimeUnit.SECONDS).getStatusCode().value());
            assertEquals(400, controller.operate("Bearer admin", "missing", request).get(1, TimeUnit.SECONDS).getStatusCode().value());
            var invalidFields = new AdminFilesController.OperationBody(Map.of("operation", "roots", "unknown_field", true), null, "unknown-field", 0);
            assertEquals(400, controller.operate("Bearer admin", "missing", invalidFields).get(1, TimeUnit.SECONDS).getStatusCode().value(),
                    "wrapped JSON validation errors remain client errors, not uncertain server failures");
            assertEquals(0, tasks.totalCount());
        }
    }

    @Test void longPollReturnsExistingNativeResultWithoutExecutingAnotherTask(@TempDir Path root) throws Exception {
        var registry = AgentRegistry.forTest("enroll");
        var machine = registry.register(new RegisterRequest("files-agent", "files-host", "files-host", "linux", "amd64",
                "test", root.toString(), List.of("files", "file_transfer")), "enroll");
        var tasks = new TaskService(registry);
        var transfers = new ArtifactTransferService(null, null, new FileSystemArtifactStore(root.resolve("objects")), tasks, tokens());
        try (var async = new CenterAsyncExecutor()) {
            var controller = new AdminFilesController(tokens(), registry, tasks, transfers, async);
            var request = new AdminFilesController.OperationBody(Map.of("operation", "list", "path", root.toString()), null, "files-read-test", 0);
            var initial = controller.operate("Bearer admin", machine.machineId(), request).get(1, TimeUnit.SECONDS);
            var created = (TaskView)((Map<?, ?>)initial.getBody()).get("task");
            assertEquals("files", created.kind()); assertEquals(LaneMode.READ, created.laneMode());
            var repeated = controller.operate("Bearer admin", machine.machineId(), request).get(1, TimeUnit.SECONDS);
            assertEquals(created.id(), ((TaskView)((Map<?, ?>)repeated.getBody()).get("task")).id());
            var recoveredCreation = controller.recover("Bearer admin", machine.machineId(), "files-read-test").get(1, TimeUnit.SECONDS);
            assertEquals(created.id(), ((TaskView)((Map<?, ?>)recoveredCreation.getBody()).get("task")).id());
            assertEquals(404, controller.recover("Bearer admin", machine.machineId(), "not-created").get(1, TimeUnit.SECONDS).getStatusCode().value());
            var leased = tasks.poll(machine.machineId(), new PollRequest(List.of(), 1, List.of("files"))).task();
            assertNotNull(leased);
            tasks.updateState(machine.machineId(), created.id(), new TaskUpdateRequest("running", null, null, null, null, false), leased.attempt());
            var running = tasks.find(created.id()).orElseThrow();
            var waiting = controller.result("Bearer admin", created.id(), 1000, running.changeSequence());
            assertThrows(TimeoutException.class, () -> waiting.get(25, TimeUnit.MILLISECONDS));
            var bytes = JsonCodec.write(Map.of("operation", "list", "path", root.toString(), "entries", List.of(Map.of("name", "中文.txt")), "has_more", false));
            var sha = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            tasks.appendArtifact(machine.machineId(), created.id(), "application/vnd.rcm.files+json", sha, bytes, leased.attempt());
            var response = (Map<?, ?>)waiting.get(1, TimeUnit.SECONDS).getBody();
            assertEquals("中文.txt", ((Map<?, ?>)((List<?>)((Map<?, ?>)response.get("result")).get("entries")).getFirst()).get("name"));
            tasks.updateState(machine.machineId(), created.id(), new TaskUpdateRequest("completed", 0, null, null, null, false), leased.attempt());
            var finished = controller.result("Bearer admin", created.id(), 1000, -1).get(1, TimeUnit.SECONDS);
            assertEquals("completed", ((TaskView)((Map<?, ?>)finished.getBody()).get("task")).status());
            assertEquals(1, tasks.totalCount(), "observation and retries must reuse the task");
        }
    }

    @Test void allFileOperationsCreateReusableTasksWithSupportedRisk(@TempDir Path root) throws Exception {
        var registry = AgentRegistry.forTest("enroll");
        var machine = registry.register(new RegisterRequest("files-agent", "files-host", "files-host", "linux", "amd64",
                "test", root.toString(), List.of("files")), "enroll");
        var tasks = new TaskService(registry);
        var source = root.resolve("source").toString();
        var target = root.resolve("target").toString();
        var requests = List.<Map<String, Object>>of(
                Map.of("operation", "roots"), Map.of("operation", "list", "path", source),
                Map.of("operation", "search", "path", source, "pattern", "*.txt"),
                Map.of("operation", "stat", "path", source), Map.of("operation", "read", "path", source),
                Map.of("operation", "write", "path", target, "content", "中文"),
                Map.of("operation", "mkdir", "path", target),
                Map.of("operation", "copy", "path", source, "destination_path", target),
                Map.of("operation", "move", "path", source, "destination_path", target),
                Map.of("operation", "delete", "path", source),
                Map.of("operation", "archive", "path", source, "destination_path", target),
                Map.of("operation", "extract", "path", source, "destination_path", target));
        var transfers = new ArtifactTransferService(null, null, new FileSystemArtifactStore(root.resolve("objects")), tasks, tokens());
        try (var async = new CenterAsyncExecutor()) {
            var controller = new AdminFilesController(tokens(), registry, tasks, transfers, async);
            for (var operation : requests) {
                var name = (String)operation.get("operation");
                var body = new AdminFilesController.OperationBody(operation, null, "risk-" + name, 0);
                var response = controller.operate("Bearer admin", machine.machineId(), body).get(1, TimeUnit.SECONDS);
                assertEquals(200, response.getStatusCode().value(), name + ": " + response.getBody());
                var task = (TaskView)((Map<?, ?>)response.getBody()).get("task");
                var readOnly = FileRequest.READ_ONLY.contains(name);
                assertEquals(readOnly ? "low" : "high", task.risk(), name);
                assertEquals(readOnly ? LaneMode.READ : LaneMode.WRITE, task.laneMode(), name);
                assertEquals("files", task.kind());
                var retry = controller.operate("Bearer admin", machine.machineId(), body).get(1, TimeUnit.SECONDS);
                assertEquals(task.id(), ((TaskView)((Map<?, ?>)retry.getBody()).get("task")).id(), name);
            }
            assertEquals(requests.size(), tasks.totalCount());
        }
    }

    private static CenterTokenConfig tokens() {
        return new CenterTokenConfig() {
            @Override public boolean acceptsAdmin(String candidate) { return "admin".equals(candidate); }
            @Override public String artifactDownloadSecret() { return "files-controller-test-secret"; }
        };
    }
}
