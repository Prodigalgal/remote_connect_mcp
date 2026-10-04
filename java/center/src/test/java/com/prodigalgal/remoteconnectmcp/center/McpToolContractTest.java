package com.prodigalgal.remoteconnectmcp.center;

import static org.junit.jupiter.api.Assertions.*;

import com.prodigalgal.remoteconnectmcp.protocol.PollRequest;
import com.prodigalgal.remoteconnectmcp.protocol.RegisterRequest;
import com.prodigalgal.remoteconnectmcp.protocol.TaskCommand;
import com.prodigalgal.remoteconnectmcp.protocol.TaskKind;
import com.prodigalgal.remoteconnectmcp.protocol.TaskUpdateRequest;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class McpToolContractTest {
    @Test
    void actionSchemasRejectMissingAndIrrelevantFields() {
        var desktop = McpConfiguration.desktopModelSchema();
        valid(desktop, Map.of("operation", "click", "machine_id", "m", "x", 1, "y", 2));
        invalid(desktop, Map.of("operation", "click", "machine_id", "m", "x", 1));
        invalid(desktop, Map.of("operation", "screenshot", "machine_id", "m", "text", "ignored"));
        invalid(desktop, Map.of("operation", "type", "machine_id", "m", "text", "x".repeat(16385)));
        valid(desktop, Map.of("operation", "clipboard_write", "machine_id", "m", "text", ""));
        valid(desktop, Map.of("operation", "shortcut", "machine_id", "m", "keys", List.of("Control", "A")));
        invalid(desktop, Map.of("operation", "shortcut", "machine_id", "m", "keys", List.of("Enter"), "key", "Enter"));

        var browser = McpConfiguration.modelBrowserRequestSchema();
        valid(browser, Map.of("action", "fill", "selector", "input", "text", ""));
        invalid(browser, Map.of("action", "fill", "selector", "input"));
        invalid(browser, Map.of("action", "click", "ref", "ref", "selector", "button"));
        invalid(browser, Map.of("action", "navigate", "url", "https://example.test", "keys", List.of("Enter")));
        valid(browser, Map.of("action", "wait", "wait_ms", 50, "timeout_ms", 100));
        invalid(browser, Map.of("action", "wait", "timeout_ms", 100));
        invalid(browser, Map.of("action", "observe", "include_snapshot", "true"));

        var machines = McpConfiguration.machinesModelSchema();
        invalid(machines, Map.of("operation", "detail"));
        invalid(machines, Map.of("operation", "list", "machine_id", "ignored"));
    }

    @Test
    void returnedLongReferenceFitsPublishedSchema() throws Exception {
        var descriptor = Map.of("kind", "role", "role", "button", "name", "中文".repeat(60), "exact", true, "nth", 0);
        var ref = "rcm-ref-v1:" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(McpJsonDefaults.getMapper().writeValueAsString(descriptor).getBytes(StandardCharsets.UTF_8));
        assertTrue(ref.length() > 256);
        valid(McpConfiguration.modelBrowserRequestSchema(), Map.of("action", "click", "ref", ref));
    }

    @Test
    void textSelectorsAndObservationOptionsAreUnambiguous() {
        var artifact = McpConfiguration.artifactModelSchema();
        valid(artifact, Map.of("operation", "read", "artifact_id", "a", "delivery_mode", "inline", "cursor", 100, "limit", 4096));
        invalid(artifact, Map.of("operation", "read", "artifact_id", "a", "transfer_id", "t"));
        invalid(artifact, Map.of("operation", "read", "artifact_id", "a", "cursor", 1));
        var task = McpConfiguration.taskReadModelSchema();
        valid(task, Map.of("task_id", "t", "change_seq", 4, "include_output", false));
        invalid(task, Map.of("task_id", "t", "include_output", false, "cursor", 0));
        invalid(task, Map.of("task_id", "t", "tail_bytes", 8192, "limit", 8192));
        assertEquals(0, McpConfiguration.artifactWaitMs("async", null));
        assertEquals(1500, McpConfiguration.artifactWaitMs("async", 1500));
    }

    @Test
    void freshIdenticalCommandsExecuteSeparatelyButExplicitRetriesReuseTheTask() {
        var fixture = fixture();
        var arguments = Map.<String, Object>of("machine_id", fixture.machineId(), "command", "git status");
        var first = command(fixture, arguments);
        var second = command(fixture, arguments);
        assertNotEquals(taskId(first), taskId(second));
        var retry = Map.<String, Object>of("machine_id", fixture.machineId(), "command", "git status",
                "idempotency_key", "same-invocation", "limit", 4096);
        var original = command(fixture, retry);
        var observed = command(fixture, Map.of("machine_id", fixture.machineId(), "command", "git status",
                "idempotency_key", "same-invocation", "limit", 1024));
        assertEquals(taskId(original), taskId(observed));
        assertFalse(Boolean.TRUE.equals(original.isError()));
    }

    @Test
    void statusObservationWaitsForChangeWithoutReplayingOldLogs() throws Exception {
        var fixture = fixture();
        var created = create(fixture, TaskKind.COMMAND, "echo", "status-test");
        var attempt = start(fixture, created.id());
        fixture.tasks().appendOutput(fixture.machineId(), created.id(), 0, "old log".getBytes(StandardCharsets.UTF_8), attempt);
        var current = fixture.tasks().findFor(TaskOrigin.configured(), created.id()).orElseThrow();
        var entered = new CountDownLatch(1);
        var waiting = CompletableFuture.supplyAsync(() -> {
            entered.countDown();
            return McpConfiguration.taskReadModel(fixture.tasks(), TaskOrigin.configured(),
                    request("task_read", Map.of("task_id", created.id(), "change_seq", current.changeSequence(), "wait_ms", 5000)));
        });
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertThrows(TimeoutException.class, () -> waiting.get(100, TimeUnit.MILLISECONDS));
        fixture.tasks().updateState(fixture.machineId(), created.id(),
                new TaskUpdateRequest(TaskStatus.COMPLETED, 0, null, null, Instant.now(), false), attempt);
        var result = (Map<?, ?>) waiting.get(2, TimeUnit.SECONDS).structuredContent();
        assertEquals(TaskStatus.COMPLETED, ((Map<?, ?>) result.get("task")).get("status"));
        assertFalse(result.containsKey("output"));
    }

    @Test
    void imageBytesAreExplicitOnObservationAndAutomaticOnImmediateScreenshotResult() throws Exception {
        var fixture = fixture();
        var created = create(fixture, TaskKind.DESKTOP, null, "image-test");
        var attempt = start(fixture, created.id());
        var image = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47};
        var sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(image));
        fixture.tasks().appendArtifact(fixture.machineId(), created.id(), "image/png", sha, image, attempt);
        fixture.tasks().updateState(fixture.machineId(), created.id(),
                new TaskUpdateRequest(TaskStatus.COMPLETED, 0, null, null, Instant.now(), false), attempt);
        var view = new TaskView(fixture.tasks().findFor(TaskOrigin.configured(), created.id()).orElseThrow());
        var metadata = McpConfiguration.taskResult(fixture.tasks(), TaskOrigin.configured(), view, 0, 4096, true, false, false);
        assertTrue(((Map<?, ?>) metadata.structuredContent()).containsKey("artifact"));
        assertFalse(metadata.content().stream().anyMatch(McpSchema.ImageContent.class::isInstance));
        var imageResult = McpConfiguration.taskResult(fixture.tasks(), TaskOrigin.configured(), view, 0, 4096, false, false, true);
        assertTrue(imageResult.content().stream().anyMatch(McpSchema.ImageContent.class::isInstance));
        assertTrue(McpConfiguration.immediateTaskResult(fixture.tasks(), TaskOrigin.configured(), view)
                .content().stream().anyMatch(McpSchema.ImageContent.class::isInstance));
    }

    @Test
    void browserProjectionKeepsWarningsAndAllowsFullDiagnosticsOnDemand() throws Exception {
        var full = Map.<String, Object>of("operation", "snapshot", "snapshot", "中文😀".repeat(10000),
                "elements", List.of(Map.of("ref", "complete-ref")), "warnings", List.of(Map.of("text", "warning")),
                "diagnostics", Map.of("console", List.of(Map.of("type", "log", "text", "routine"))));
        var compact = McpConfiguration.compactBrowserOutput(full, 4096, false);
        assertEquals(true, compact.get("truncated"));
        assertEquals(true, compact.get("detail_available"));
        assertFalse(compact.containsKey("diagnostics"));
        assertTrue(compact.containsKey("warnings"));
        assertTrue(McpJsonDefaults.getMapper().writeValueAsString(compact).getBytes(StandardCharsets.UTF_8).length <= 4096);
        var detailed = McpConfiguration.compactBrowserOutput(Map.of("diagnostics", full.get("diagnostics")), 65536, true);
        assertTrue(detailed.containsKey("diagnostics"));
    }

    @Test
    void outputContractsDistinguishTextAndStructuredBrowserData() {
        var task = Map.of("id", "t", "machine_id", "m", "kind", "browser", "status", "completed", "change_seq", 1);
        valid(McpConfiguration.modelOutputSchema("browser"), Map.of("task", task,
                "output", Map.of("data", Map.of("operation", "navigate"), "cursor", 0, "next_cursor", 50, "more", false)));
        invalid(McpConfiguration.modelOutputSchema("browser"), Map.of("task", task,
                "output", Map.of("data", Map.of(), "text", "duplicate", "cursor", 0, "next_cursor", 0, "more", false)));
        invalid(McpConfiguration.modelOutputSchema("task_cancel"), Map.of("task",
                Map.of("id", "t", "machine_id", "m", "kind", "command", "status", "invented", "change_seq", 1)));
    }

    @Test
    void utf8PaginationPreservesChineseAndEmojiWithTinyBudgets() {
        var bytes = "中文😀end".getBytes(StandardCharsets.UTF_8);
        for (var limit : List.of(1, 2, 3, 4, 5, 7)) {
            long cursor = 0;
            var output = new StringBuilder();
            while (cursor < bytes.length) {
                var end = Math.min(bytes.length, (int) cursor + Math.max(4, limit));
                var page = Utf8OutputPage.align(new OutputPage(java.util.Arrays.copyOfRange(bytes, (int) cursor, end),
                        cursor, end, end < bytes.length), limit);
                assertTrue(page.nextCursor() > cursor);
                output.append(new String(page.data(), StandardCharsets.UTF_8));
                cursor = page.nextCursor();
            }
            assertEquals("中文😀end", output.toString());
        }
    }

    private static void valid(Map<String, Object> schema, Map<String, Object> value) {
        var result = McpJsonDefaults.getSchemaValidator().validate(schema, value);
        assertTrue(result.valid(), result.errorMessage());
    }

    private static void invalid(Map<String, Object> schema, Map<String, Object> value) {
        assertFalse(McpJsonDefaults.getSchemaValidator().validate(schema, value).valid());
    }

    private static Fixture fixture() {
        var registry = AgentRegistry.forTest("enroll-test");
        var machine = registry.register(new RegisterRequest("tool-agent", "host-tool", "host-tool", "linux", "amd64",
                "dev", "/srv", List.of("command", "browser", "desktop")), "enroll-test");
        return new Fixture(registry, new TaskService(registry), machine.machineId());
    }

    private static McpSchema.CallToolRequest request(String name, Map<String, Object> values) {
        return new McpSchema.CallToolRequest(name, values, Map.of());
    }

    private static McpSchema.CallToolResult command(Fixture fixture, Map<String, Object> values) {
        return McpConfiguration.commandModel(fixture.registry(), fixture.tasks(), new McpAccessService(),
                TaskOrigin.configured(), request("command", values));
    }

    private static String taskId(McpSchema.CallToolResult result) {
        assertFalse(Boolean.TRUE.equals(result.isError()), result.content().toString());
        return (String) ((Map<?, ?>) ((Map<?, ?>) result.structuredContent()).get("task")).get("id");
    }

    private static TaskView create(Fixture fixture, TaskKind kind, String command, String key) {
        var action = kind == TaskKind.DESKTOP
                ? new TaskCommand.DesktopAction("screenshot", null, List.of(), "/srv", null, null, null, null) : null;
        return fixture.tasks().create(new CreateTaskRequest(fixture.machineId(),
                new TaskCommand("", kind, kind.name().toLowerCase(), command, "/srv", Map.of(), 30, action, Instant.now()),
                key), "mcp", TaskOrigin.configured());
    }

    private static int start(Fixture fixture, String taskId) {
        var leased = fixture.tasks().poll(fixture.machineId(), new PollRequest(List.of(), 1, List.of("command", "desktop", "browser"))).task();
        assertNotNull(leased);
        assertEquals(taskId, leased.id());
        fixture.tasks().updateState(fixture.machineId(), taskId,
                new TaskUpdateRequest(TaskStatus.RUNNING, null, null, null, null, false), leased.attempt());
        return leased.attempt();
    }

    private record Fixture(AgentRegistry registry, TaskService tasks, String machineId) {}
}
