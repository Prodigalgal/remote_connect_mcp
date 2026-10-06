package com.prodigalgal.remotecontrolmcp.center;

import static org.junit.jupiter.api.Assertions.*;

import com.prodigalgal.remotecontrolmcp.protocol.PollRequest;
import com.prodigalgal.remotecontrolmcp.protocol.LaneMode;
import com.prodigalgal.remotecontrolmcp.protocol.RegisterRequest;
import com.prodigalgal.remotecontrolmcp.protocol.TaskCommand;
import com.prodigalgal.remotecontrolmcp.protocol.TaskKind;
import com.prodigalgal.remotecontrolmcp.protocol.TaskUpdateRequest;
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
    void taskReadRecoversDeliveredFileWithoutReissuingTheTransfer(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var fixture = fixture();
        var tokens = new CenterTokenConfig() {
            @Override public String artifactDownloadSecret() { return "contract-test-file-signing"; }
        };
        var transfers = new ArtifactTransferService(null, null, new FileSystemArtifactStore(root), fixture.tasks(), tokens);
        var access = new McpAccessService();
        var original = McpConfiguration.artifactModel(fixture.registry(), fixture.tasks(), access, transfers,
                TaskOrigin.configured(), request("artifact", Map.of("operation", "get", "machine_id", fixture.machineId(),
                        "source_path", "/srv/中文报告.docx", "delivery_mode", "async")));
        var taskId = taskId(original);
        var initial = (Map<?, ?>) original.structuredContent();
        assertFalse(initial.containsKey("file"));
        assertTrue(original.content().stream().noneMatch(McpSchema.ResourceLink.class::isInstance));
        var transferId = (String) ((Map<?, ?>) initial.get("transfer")).get("transfer_id");
        var leased = fixture.tasks().poll(fixture.machineId(), new PollRequest(List.of(), 1, List.of("file_transfer"))).task();
        assertEquals(taskId, leased.id());
        fixture.tasks().updateState(fixture.machineId(), taskId, new TaskUpdateRequest(TaskStatus.RUNNING, null, null, null, null, false), leased.attempt());
        var bytes = "test document bytes".getBytes(StandardCharsets.UTF_8);
        var digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        transfers.receiveFromAgent(fixture.machineId(), transferId, new java.io.ByteArrayInputStream(bytes), bytes.length,
                digest, "中文报告.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", leased.attempt());
        fixture.tasks().updateState(fixture.machineId(), taskId, new TaskUpdateRequest(TaskStatus.COMPLETED, 0, null, null, null, false), leased.attempt());
        var result = McpConfiguration.taskReadModel(fixture.tasks(), access, transfers, TaskOrigin.configured(),
                request("task_read", Map.of("task_id", taskId)));
        assertFalse(Boolean.TRUE.equals(result.isError()), result.content().toString());
        @SuppressWarnings("unchecked") var payload = (Map<String, Object>) result.structuredContent();
        valid(McpConfiguration.modelOutputSchema("task_read"), payload);
        assertEquals(transferId, ((Map<?, ?>) payload.get("transfer")).get("transfer_id"));
        var file = (Map<?, ?>) payload.get("file");
        assertEquals("中文报告.docx", file.get("file_name"));
        assertEquals(digest, file.get("sha256"));
        assertFalse(file.containsKey("file_id"));
        assertTrue(result.content().stream().anyMatch(McpSchema.ResourceLink.class::isInstance));
        var count = fixture.tasks().totalCount();
        var card = McpConfiguration.fileCardModel(fixture.registry(), fixture.tasks(), access, transfers, TaskOrigin.configured(),
                request("file_card", Map.of("artifact_id", file.get("artifact_id"))));
        assertFalse(Boolean.TRUE.equals(card.isError()), card.content().toString());
        @SuppressWarnings("unchecked") var cardPayload = (Map<String, Object>)card.structuredContent();
        valid(McpConfiguration.modelOutputSchema("file_card"), cardPayload);
        assertEquals(count, fixture.tasks().totalCount(), "presentation must reuse the existing transfer");
        assertFalse(cardPayload.toString().contains("file_id"));
    }

    @Test
    void machineDetailAndErrorsSatisfyTheirPublishedOutputSchema() {
        var fixture = fixture();
        var result = McpConfiguration.machinesModel(fixture.registry(), new McpAccessService(),
                TaskOrigin.configured(), request("machines", Map.of("operation", "detail", "machine_id", fixture.machineId())));
        assertFalse(Boolean.TRUE.equals(result.isError()));
        @SuppressWarnings("unchecked") var payload = (Map<String, Object>) result.structuredContent();
        valid(McpConfiguration.modelOutputSchema("machines"), payload);
        assertEquals(fixture.machineId(), ((Map<?, ?>) payload.get("machine")).get("id"));
        var missing = McpConfiguration.machinesModel(fixture.registry(), new McpAccessService(),
                TaskOrigin.configured(), request("machines", Map.of("operation", "detail", "machine_id", "missing")));
        assertTrue(Boolean.TRUE.equals(missing.isError()));
        @SuppressWarnings("unchecked") var failure = (Map<String, Object>) missing.structuredContent();
        valid(McpConfiguration.modelOutputSchema("machines"), failure);
    }

    @Test void machineSearchFiltersAuthorizedInventoryBeforePagination() {
        var fixture = fixture(); var access = new McpAccessService();
        var hidden = fixture.registry().register(new RegisterRequest("hidden-tool-agent", "host-hidden", "host-hidden", "linux", "amd64",
                "dev", "/srv", List.of("command")), "enroll-test");
        var origin = new TaskOrigin("owner", "search-token", "connection");
        access.grantTokenMachines(origin.tokenId(), Map.of(fixture.machineId(), java.util.Set.of("command")));
        var found = McpConfiguration.machinesModel(fixture.registry(), access, origin, request("machines", Map.of("operation", "list", "query", "TOOL", "limit", 1)));
        var result = (Map<?, ?>)found.structuredContent(); assertEquals(1, result.get("total"));
        assertEquals(fixture.machineId(), ((Map<?, ?>)((List<?>)result.get("machines")).getFirst()).get("id"));
        assertFalse(result.toString().contains(hidden.machineId()));
        var absent = (Map<?, ?>)McpConfiguration.machinesModel(fixture.registry(), access, origin,
                request("machines", Map.of("operation", "list", "query", "not-present"))).structuredContent();
        assertEquals(0, absent.get("total"));
    }

    @Test void filesUseExistingTaskRecoveryAndRequireTheirOwnCredentialPermission() throws Exception {
        var fixture = fixture(); var access = new McpAccessService();
        var origin = new TaskOrigin("owner", "files-token", "before-reconnect");
        access.grantTokenMachines(origin.tokenId(), Map.of(fixture.machineId(), java.util.Set.of("artifact", "task_read")));
        var args = Map.<String, Object>of("machine_id", fixture.machineId(), "request", Map.of("operation", "roots"), "idempotency_key", "files-contract-retry");
        var denied = McpConfiguration.filesModel(fixture.registry(), fixture.tasks(), access, origin, request("files", args));
        assertTrue(Boolean.TRUE.equals(denied.isError())); assertEquals(0, fixture.tasks().totalCount());
        access.grantTokenMachines(origin.tokenId(), Map.of(fixture.machineId(), java.util.Set.of("files", "task_read")));
        var first = McpConfiguration.filesModel(fixture.registry(), fixture.tasks(), access, origin, request("files", args));
        var id = taskId(first);
        var repeated = McpConfiguration.filesModel(fixture.registry(), fixture.tasks(), access, origin, request("files", args));
        assertEquals(id, taskId(repeated));
        assertEquals(com.prodigalgal.remotecontrolmcp.protocol.LaneMode.READ, fixture.tasks().find(id).orElseThrow().command().contract().laneMode());
        var leased = fixture.tasks().poll(fixture.machineId(), new PollRequest(List.of(), 1, List.of("files"))).task();
        fixture.tasks().updateState(fixture.machineId(), id, new TaskUpdateRequest("running", null, null, null, null, false), leased.attempt());
        var bytes = com.prodigalgal.remotecontrolmcp.protocol.JsonCodec.write(Map.of("operation", "roots", "roots", List.of("/"), "cwd", "/srv"));
        var digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        fixture.tasks().appendArtifact(fixture.machineId(), id, "application/vnd.rcm.files+json", digest, bytes, leased.attempt());
        fixture.tasks().updateState(fixture.machineId(), id, new TaskUpdateRequest("completed", 0, null, null, null, false), leased.attempt());
        var reconnected = new TaskOrigin(origin.principalId(), origin.tokenId(), "after-reconnect");
        var observed = McpConfiguration.taskReadModel(fixture.tasks(), access, null, reconnected, request("task_read", Map.of("task_id", id)));
        assertFalse(Boolean.TRUE.equals(observed.isError()), observed.content().toString());
        @SuppressWarnings("unchecked") var payload = (Map<String, Object>)observed.structuredContent();
        valid(McpConfiguration.modelOutputSchema("files"), payload);
        assertEquals(List.of("/"), ((Map<?, ?>)payload.get("result")).get("roots"));
        assertEquals(1, fixture.tasks().totalCount());
        access.grantTokenMachines(origin.tokenId(), Map.of(fixture.machineId(), java.util.Set.of("files")));
        assertTrue(Boolean.TRUE.equals(McpConfiguration.taskReadModel(fixture.tasks(), access, null, reconnected, request("task_read", Map.of("task_id", id))).isError()));
    }

    @Test void fileRequestSchemaRejectsCrossOperationParametersAndInvalidWaitCannotCreateWork() {
        var schema = McpConfiguration.filesModelSchema();
        valid(schema, Map.of("machine_id", "m", "request", Map.of("operation", "read", "path", "/tmp/中文.txt", "encoding", "GB18030")));
        valid(schema, Map.of("machine_id", "m", "request", Map.of("operation", "write", "path", "/tmp/a.txt", "content", "", "overwrite", false)));
        invalid(schema, Map.of("machine_id", "m", "request", Map.of("operation", "stat", "path", "/tmp", "content", "ignored")));
        invalid(schema, Map.of("machine_id", "m", "request", Map.of("operation", "copy", "path", "/tmp/a.txt")));
        invalid(schema, Map.of("machine_id", "m", "request", Map.of("operation", "list", "path", "/tmp", "limit", 101)));
        var fixture = fixture();
        var result = McpConfiguration.filesModel(fixture.registry(), fixture.tasks(), new McpAccessService(), TaskOrigin.configured(),
                request("files", Map.of("machine_id", fixture.machineId(), "request", Map.of("operation", "delete", "path", "/tmp/a"), "wait_ms", 15001)));
        assertTrue(Boolean.TRUE.equals(result.isError())); assertEquals(0, fixture.tasks().totalCount());
    }

    @Test
    void hostSessionCorrelationSurvivesReconnectWithoutBecomingAnAuthority() {
        var principal = new McpPrincipal("owner", "token-1", "Owner", java.util.Set.of("mcp:read"), false, null);
        var call = new McpSchema.CallToolRequest("machines", Map.of(), Map.of("openai/session", "host-session"));
        var before = McpConfiguration.conversationOrigin(principal, "transport-1", call);
        var after = McpConfiguration.conversationOrigin(principal, "transport-2", call);
        assertEquals(before, after);
        assertEquals("owner", after.principalId());
        assertEquals("token-1", after.tokenId());
        assertFalse(after.connectionId().contains("host-session"));
        var otherToken = new McpPrincipal("owner", "token-2", "Owner", principal.scopes(), false, null);
        assertNotEquals(after.connectionId(), McpConfiguration.conversationOrigin(otherToken, "transport-2", call).connectionId());
        for (var invalid : List.of("", "x".repeat(257), "bad\nvalue", 123)) {
            assertEquals("transport-2", McpConfiguration.conversationOrigin(principal, "transport-2",
                    new McpSchema.CallToolRequest("machines", Map.of(), Map.of("openai/session", invalid))).connectionId());
        }
        assertEquals("transport-2", McpConfiguration.conversationOrigin(principal, "transport-2", request("machines", Map.of())).connectionId());
    }

    @Test
    void fileHandlesUsePortableResourceLinksWithoutImpersonatingHostFiles() {
        var file = Map.<String, Object>of("artifact_id", "rcm-artifact", "file_name", "中文报告.docx",
                "mime_type", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "bytes", 1234L, "download_url", "https://files.example.test/report?signature=test");
        var result = McpConfiguration.artifactHandleResult(Map.of("file", file,
                "transfer", Map.of("transfer_id", "transfer-1", "artifact_id", "rcm-artifact", "status", "delivered")));
        assertFalse(Boolean.TRUE.equals(result.isError()));
        var link = result.content().stream().filter(McpSchema.ResourceLink.class::isInstance)
                .map(McpSchema.ResourceLink.class::cast).findFirst().orElseThrow();
        assertEquals("中文报告.docx", link.name());
        assertEquals(file.get("download_url"), link.uri());
        assertEquals(1234L, link.size());
        @SuppressWarnings("unchecked") var payload = (Map<String, Object>) result.structuredContent();
        valid(McpConfiguration.modelOutputSchema("artifact"), payload);
        assertFalse(file.containsKey("file_id"));
        var pending = McpConfiguration.artifactHandleResult(Map.of("transfer",
                Map.of("transfer_id", "t", "artifact_id", "a", "status", "pending")));
        assertTrue(pending.content().stream().noneMatch(McpSchema.ResourceLink.class::isInstance));
    }

    @Test
    void artifactCallsWithoutRetryKeyCreateFreshTransfersAndExplicitRetriesReuseThem() {
        var fixture = fixture();
        var tokens = new CenterTokenConfig() {
            @Override
            public String artifactDownloadSecret() { return "artifact-contract-signing-secret"; }
        };
        var transfers = new ArtifactTransferService(null, null, null, fixture.tasks(), tokens);
        var args = Map.<String, Object>of("operation", "get", "machine_id", fixture.machineId(),
                "source_path", "/srv/data.txt");
        var first = McpConfiguration.artifactModel(fixture.registry(), fixture.tasks(), new McpAccessService(),
                transfers, TaskOrigin.configured(), request("artifact", args));
        var second = McpConfiguration.artifactModel(fixture.registry(), fixture.tasks(), new McpAccessService(),
                transfers, TaskOrigin.configured(), request("artifact", args));
        assertNotEquals(taskId(first), taskId(second));
        var retry = new java.util.LinkedHashMap<String, Object>(args);
        retry.put("idempotency_key", "artifact-contract-retry");
        var third = McpConfiguration.artifactModel(fixture.registry(), fixture.tasks(), new McpAccessService(),
                transfers, TaskOrigin.configured(), request("artifact", retry));
        var repeated = McpConfiguration.artifactModel(fixture.registry(), fixture.tasks(), new McpAccessService(),
                transfers, TaskOrigin.configured(), request("artifact", retry));
        assertEquals(taskId(third), taskId(repeated));
    }

    @Test
    void invalidActionReportsOnlyItsOwnMissingField() {
        var error = McpJsonDefaults.getSchemaValidator().validate(McpConfiguration.desktopModelSchema(),
                Map.of("operation", "click", "machine_id", "m", "x", 1));
        assertFalse(error.valid());
        assertTrue(error.errorMessage().contains("y"));
        assertTrue(error.errorMessage().length() < 512, error.errorMessage());
        assertFalse(error.errorMessage().contains("window_title"));
        assertFalse(error.errorMessage().contains("executable"));
    }

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
            return McpConfiguration.taskReadModel(fixture.tasks(), new McpAccessService(), TaskOrigin.configured(),
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

    @Test void nativeFileWriteCreatesSupportedContractAndReusesItsTask() {
        var fixture = fixture();
        var access = new McpAccessService();
        var args = Map.<String, Object>of("machine_id", fixture.machineId(), "request",
                Map.of("operation", "write", "path", "/srv/new.txt", "content", "中文"),
                "wait_ms", 0, "idempotency_key", "mcp-file-write-risk");
        var result = McpConfiguration.filesModel(fixture.registry(), fixture.tasks(), access,
                TaskOrigin.configured(), request("files", args));
        var id = taskId(result);
        var task = fixture.tasks().find(id).orElseThrow();
        assertEquals(LaneMode.WRITE, task.command().contract().laneMode());
        assertEquals("high", task.command().contract().risk());
        var retry = McpConfiguration.filesModel(fixture.registry(), fixture.tasks(), access,
                TaskOrigin.configured(), request("files", args));
        assertEquals(id, taskId(retry));
        assertEquals(1, fixture.tasks().totalCount());
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
                "dev", "/srv", List.of("command", "browser", "desktop", "file_transfer", "files")), "enroll-test");
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
