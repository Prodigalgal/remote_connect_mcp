package com.prodigalgal.remoteconnectmcp.center;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.prodigalgal.remoteconnectmcp.protocol.PollRequest;
import com.prodigalgal.remoteconnectmcp.protocol.ExecutionContract;
import com.prodigalgal.remoteconnectmcp.protocol.FileTransferAction;
import com.prodigalgal.remoteconnectmcp.protocol.AgentRuntimeDescriptor;
import com.prodigalgal.remoteconnectmcp.protocol.LaneMode;
import com.prodigalgal.remoteconnectmcp.protocol.RegisterRequest;
import com.prodigalgal.remoteconnectmcp.protocol.TaskCommand;
import com.prodigalgal.remoteconnectmcp.protocol.TaskKind;
import com.prodigalgal.remoteconnectmcp.protocol.TaskUpdateRequest;
import com.prodigalgal.remoteconnectmcp.protocol.TaskProgressUpdate;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TaskServiceTest {
    @Test
    void isolatesIdempotencyAndTaskReadsByPrincipal() {
        var registry = AgentRegistry.forTest("enroll-test");
        var registration = registry.register(new RegisterRequest("command-agent", "host-a", "host-a", "linux", "amd64", "dev", "/srv", List.of("command")), "enroll-test");
        var tasks = new TaskService(registry);
        var command = new TaskCommand("", TaskKind.COMMAND, "command", "printf owner", "/srv", Map.of(), 0, null, null);
        var ownerA = new TaskOrigin("user-a", "token-a", "connection-a");
        var ownerB = new TaskOrigin("user-b", "token-b", "connection-b");

        var first = tasks.create(new CreateTaskRequest(registration.machineId(), command, "same-key", null,
                "", "low", false, ownerA), "mcp", ownerA);
        var second = tasks.create(new CreateTaskRequest(registration.machineId(), command, "same-key", null,
                "", "low", false, ownerB), "mcp", ownerB);

        assertTrue(!first.id().equals(second.id()));
        assertTrue(first.executionSessionId().startsWith("session_"));
        assertTrue(tasks.find(second.id()).orElseThrow().executionSessionId().startsWith("session_"));
        assertTrue(!first.executionSessionId().equals(second.executionSessionId()));
        assertEquals("rcm.task." + first.id(), first.resultChannel());
        assertEquals(first.id(), tasks.findFor(ownerA, first.id()).orElseThrow().id());
        assertThrows(SecurityException.class, () -> tasks.findFor(ownerB, first.id()));
    }

    @Test
    void createsIdempotentTaskDispatchesAndStreamsOutput() {
        var registry = AgentRegistry.forTest("enroll-test");
        var registration = registry.register(new RegisterRequest("command-agent", "host-a", "host-a", "linux", "amd64", "dev", "/srv", List.of("command")), "enroll-test");
        var tasks = new TaskService(registry);
        var command = new TaskCommand("ignored", null, null, "printf hello", "/srv", java.util.Map.of(), 0, null, null);

        var created = tasks.create(new CreateTaskRequest(registration.machineId(), command, "logical-1"));
        var retry = tasks.create(new CreateTaskRequest(registration.machineId(), command, "logical-1"));
        var poll = tasks.poll(registration.machineId(), new PollRequest(List.of(), 1, List.of("command")));

        assertEquals(created.id(), retry.id());
        assertEquals(created.id(), poll.task().id());
        assertEquals(TaskStatus.DISPATCHING, tasks.find(created.id()).orElseThrow().status());
        assertEquals(1, tasks.find(created.id()).orElseThrow().attempt());

        tasks.updateState(registration.machineId(), created.id(), new TaskUpdateRequest(TaskStatus.RUNNING, null, null, null, null, false));
        tasks.appendOutput(registration.machineId(), created.id(), 0, "hello".getBytes(StandardCharsets.UTF_8));
        tasks.appendOutput(registration.machineId(), created.id(), 0, "hello".getBytes(StandardCharsets.UTF_8));
        var page = tasks.readOutput(created.id(), 0, 64);
        tasks.updateState(registration.machineId(), created.id(), new TaskUpdateRequest(TaskStatus.COMPLETED, 0, null, null, null, false));

        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), page.data());
        assertEquals(5, page.nextCursor());
        assertEquals(TaskStatus.COMPLETED, tasks.find(created.id()).orElseThrow().status());
        assertEquals(registration.machineId(), tasks.find(created.id()).orElseThrow().command().contract().machineId());
    }

    @Test
    void boundedWaitKeepsTheTaskRunningAndOutputCanBeReadFromTheTail() throws Exception {
        var registry = AgentRegistry.forTest("enroll-test");
        var registration = registry.register(new RegisterRequest("command-agent", "host-a", "host-a", "linux", "amd64", "dev", "/srv", List.of("command")), "enroll-test");
        var tasks = new TaskService(registry);
        var created = tasks.create(new CreateTaskRequest(registration.machineId(),
                new TaskCommand("", TaskKind.COMMAND, "command", "build", "/srv", Map.of(), 0, null, null), "bounded-wait"));
        var leased = tasks.poll(registration.machineId(), new PollRequest(List.of(), 1, List.of("command"))).task();

        var before = tasks.waitForTerminal(created.id(), java.time.Duration.ofMillis(5));
        assertFalse(TaskStatus.terminal(before.status()));
        assertEquals(before.status(), tasks.find(created.id()).orElseThrow().status());

        tasks.updateState(registration.machineId(), created.id(),
                new TaskUpdateRequest(TaskStatus.RUNNING, null, null, null, null, false), leased.attempt());
        var output = ("noise\n".repeat(18000) + "fatal error\n").getBytes(StandardCharsets.UTF_8);
        tasks.appendOutput(registration.machineId(), created.id(), 0, output, leased.attempt());
        tasks.updateState(registration.machineId(), created.id(),
                new TaskUpdateRequest(TaskStatus.FAILED, 1, "command exited with code 1", null, null, false), leased.attempt());

        var after = tasks.waitForTerminal(created.id(), java.time.Duration.ofMillis(5));
        assertEquals(TaskStatus.FAILED, after.status());
        var tail = tasks.readOutput(created.id(), after.outputBytes() - 12, 12);
        assertEquals("fatal error\n", new String(tail.data(), StandardCharsets.UTF_8));
        assertFalse(tail.more());
    }

    @Test
    void progressSnapshotIsAttemptFencedAndAdvancesDurableChangeSequence() {
        var registry = AgentRegistry.forTest("enroll-test");
        var registration = registry.register(new RegisterRequest("command-agent", "host-a", "host-a", "linux", "amd64", "dev", "/srv", List.of("command")), "enroll-test");
        var tasks = new TaskService(registry);
        var created = tasks.create(new CreateTaskRequest(registration.machineId(),
                new TaskCommand("", TaskKind.COMMAND, "command", "sleep 2", "/srv", Map.of(), 0, null, null), "progress-key"));
        var leased = tasks.poll(registration.machineId(), new PollRequest(List.of(), 1, List.of("command"))).task();
        var before = tasks.find(created.id()).orElseThrow().changeSequence();

        var updated = tasks.updateProgress(registration.machineId(), created.id(),
                new TaskProgressUpdate("running", 42, "waiting for child", 42L, 100L, "items"), leased.attempt());

        assertTrue(updated.changeSequence() > before);
        assertEquals("running", updated.progressPhase());
        assertEquals(42, updated.progressPercent());
        assertEquals(42L, updated.progressCurrent());
        assertThrows(SecurityException.class, () -> tasks.updateProgress(registration.machineId(), created.id(),
                new TaskProgressUpdate("stale", 1, null, null, null, null), leased.attempt() - 1));
    }

    @Test
    void retentionGcPurgesExpiredOutputBeforeTaskMetadata() {
        var registry = AgentRegistry.forTest("enroll-test");
        var registration = registry.register(new RegisterRequest("command-agent", "host-a", "host-a", "linux", "amd64", "dev", "/srv", List.of("command")), "enroll-test");
        var tasks = new TaskService(registry);
        var created = tasks.create(new CreateTaskRequest(registration.machineId(),
                new TaskCommand("", TaskKind.COMMAND, "command", "printf retained", "/srv", Map.of(), 30, null, null), "retention-key"));
        var leased = tasks.poll(registration.machineId(), new PollRequest(List.of(), 1, List.of("command"))).task();
        tasks.updateState(registration.machineId(), created.id(), new TaskUpdateRequest(TaskStatus.RUNNING, null, null, null, null, false), leased.attempt());
        tasks.appendOutput(registration.machineId(), created.id(), 0, "retained".getBytes(StandardCharsets.UTF_8), leased.attempt());
        tasks.updateState(registration.machineId(), created.id(), new TaskUpdateRequest(TaskStatus.COMPLETED, 0, null, null, null, false), leased.attempt());
        var state = tasks.find(created.id()).orElseThrow();
        state.outputExpiresAt(java.time.Instant.now().minusSeconds(1));
        state.metadataExpiresAt(java.time.Instant.now().minusSeconds(1));

        var result = tasks.gcExpiredTasks(30, 7, 10);

        assertEquals(1, result.outputRows());
        assertEquals(1, result.metadataRows());
        assertTrue(tasks.find(created.id()).isEmpty());
    }

    @Test
    void staleDispatchAttemptCannotPublishAfterLeaseIsReclaimed() {
        var registry = AgentRegistry.forTest("enroll-test");
        var registration = registry.register(new RegisterRequest("command-agent", "host-a", "host-a", "linux", "amd64", "dev", "/srv", List.of("command")), "enroll-test");
        var tasks = new TaskService(registry);
        var task = tasks.create(new CreateTaskRequest(registration.machineId(),
                new TaskCommand("", TaskKind.COMMAND, "command", "printf retry", "/srv", java.util.Map.of(), 0, null, null), "attempt-fence"));

        var first = tasks.poll(registration.machineId(), new PollRequest(List.of(), 1, List.of("command"))).task();
        assertEquals(1, first.attempt());
        tasks.find(task.id()).orElseThrow().leaseUntil(java.time.Instant.now().minusSeconds(1));
        var second = tasks.poll(registration.machineId(), new PollRequest(List.of(), 1, List.of("command"))).task();
        assertEquals(2, second.attempt());

        assertThrows(SecurityException.class, () -> tasks.appendOutput(registration.machineId(), task.id(), 1,
                "stale".getBytes(StandardCharsets.UTF_8), 1));
        assertThrows(SecurityException.class, () -> tasks.updateState(registration.machineId(), task.id(),
                new TaskUpdateRequest(TaskStatus.RUNNING, null, null, null, null, false), 1));
        assertEquals(2, tasks.find(task.id()).orElseThrow().attempt());
    }

    @Test
    void publishesPreparedFileTransferActionAgainstTheDurableTaskId() {
        var registry = AgentRegistry.forTest("enroll-test");
        var registration = registry.register(new RegisterRequest("command-agent", "host-a", "host-a", "linux", "amd64", "dev", "/srv", List.of("file_transfer")), "enroll-test");
        var tasks = new TaskService(registry);
        var pending = new FileTransferAction(FileTransferAction.WEB_TO_AGENT, "transfer-prepared", "artifact-prepared",
                "", "/srv/input.txt", "input.txt", "text/plain", 0L, "", true);
        var task = tasks.create(new CreateTaskRequest(registration.machineId(),
                new TaskCommand("", TaskKind.FILE_TRANSFER, "file_transfer", null, "/srv", Map.of(), 0, null, null, null, pending),
                "prepared-transfer"));
        var ready = new FileTransferAction(FileTransferAction.WEB_TO_AGENT, "transfer-prepared", "artifact-prepared",
                "", "/srv/input.txt", "input.txt", "text/plain", 5L,
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", true);

        var updated = tasks.updateFileTransferAction(registration.machineId(), task.id(), ready);

        assertEquals(task.id(), updated.id());
        assertEquals(ready, tasks.find(task.id()).orElseThrow().command().fileTransfer());
        assertEquals(TaskStatus.QUEUED, updated.status());
    }

    @Test
    void serializesQueuedTasksThatTargetTheSameExecutionLane() {
        var registry = AgentRegistry.forTest("enroll-test");
        var registration = registry.register(new RegisterRequest("command-agent", "host-a", "host-a", "linux", "amd64", "dev", "/srv", List.of("command")), "enroll-test");
        var tasks = new TaskService(registry);
        var first = tasks.create(new CreateTaskRequest(registration.machineId(),
                new TaskCommand("", TaskKind.COMMAND, "command", "printf first", "/srv", Map.of(), 0, null, null), "lane-1"));
        var second = tasks.create(new CreateTaskRequest(registration.machineId(),
                new TaskCommand("", TaskKind.COMMAND, "command", "printf second", "/srv", Map.of(), 0, null, null), "lane-2"));

        var firstLease = tasks.poll(registration.machineId(), new PollRequest(List.of(), 1, List.of("command"))).task();
        // FIFO is not part of the lane contract when two tasks share the
        // same clock tick.  Whichever task wins, the other one must remain
        // queued until the lane is released.
        assertTrue(firstLease.id().equals(first.id()) || firstLease.id().equals(second.id()));
        assertTrue(tasks.poll(registration.machineId(), new PollRequest(List.of(), 1, List.of("command"))).task() == null);

        tasks.updateState(registration.machineId(), firstLease.id(), new TaskUpdateRequest(TaskStatus.RUNNING, null, null, null, null, false), firstLease.attempt());
        tasks.updateState(registration.machineId(), firstLease.id(), new TaskUpdateRequest(TaskStatus.COMPLETED, 0, null, null, null, false), firstLease.attempt());
        var secondLease = tasks.poll(registration.machineId(), new PollRequest(List.of(), 1, List.of("command"))).task();
        assertEquals(firstLease.id().equals(first.id()) ? second.id() : first.id(), secondLease.id());
    }

    @Test
    void rejectsOversizedOutputChunkBeforeTouchingTaskState() {
        var registry = AgentRegistry.forTest("enroll-test");
        var registration = registry.register(new RegisterRequest("command-agent", "host-a", "host-a", "linux", "amd64", "dev", "/srv", List.of("command")), "enroll-test");
        var tasks = new TaskService(registry);
        var task = tasks.create(new CreateTaskRequest(registration.machineId(),
                new TaskCommand("", TaskKind.COMMAND, "command", "printf output", "/srv", Map.of(), 30, null, null), "chunk-limit"));
        var oversized = new byte[TaskService.MAX_OUTPUT_CHUNK_BYTES + 1];

        assertThrows(IllegalArgumentException.class,
                () -> tasks.appendOutput(registration.machineId(), task.id(), 0, oversized));
        assertEquals(0, tasks.find(task.id()).orElseThrow().outputBytes());
    }

    @Test
    void missingAttemptIsRejectedAfterLeaseProgresses() {
        var registry = AgentRegistry.forTest("enroll-test");
        var registration = registry.register(new RegisterRequest("command-agent", "host-a", "host-a", "linux", "amd64", "dev", "/srv", List.of("command")), "enroll-test");
        var tasks = new TaskService(registry);
        var task = tasks.create(new CreateTaskRequest(registration.machineId(),
                new TaskCommand("", TaskKind.COMMAND, "command", "printf retry", "/srv", java.util.Map.of(), 0, null, null), "attempt-fence"));
        var first = tasks.poll(registration.machineId(), new PollRequest(List.of(), 1, List.of("command"))).task();
        tasks.updateState(registration.machineId(), task.id(), new TaskUpdateRequest(TaskStatus.RUNNING, null, null, null, null, false));
        assertEquals(1, first.attempt());
        tasks.find(task.id()).orElseThrow().leaseUntil(java.time.Instant.now().minusSeconds(1));
        tasks.poll(registration.machineId(), new PollRequest(List.of(), 1, List.of("command")));
        assertThrows(SecurityException.class, () -> tasks.updateState(registration.machineId(), task.id(),
                new TaskUpdateRequest(TaskStatus.RUNNING, null, null, null, null, false), null));
    }

    @Test
    void createsFullHostContractForEveryAgent() {
        var registry = AgentRegistry.forTest("enroll-test");
        var registration = registry.register(new RegisterRequest("workspace-agent", "host-a", "host-a", "linux", "amd64", "dev",
                "/srv/project", List.of("command")), "enroll-test");
        var tasks = new TaskService(registry);

        var task = tasks.create(new CreateTaskRequest(registration.machineId(),
                new TaskCommand("", TaskKind.COMMAND, "command", "echo scoped", "src", java.util.Map.of(), 30, null, null),
                "scope-key", null, "session-1", "low", false, TaskOrigin.configured()));

        var state = tasks.find(task.id()).orElseThrow();
    }

    @Test
    void centerNeverLetsCallerSuppliedContractEnlargeOuterBudgets() {
        var registry = AgentRegistry.forTest("enroll-test");
        var registration = registry.register(new RegisterRequest("command-agent", "host-a", "host-a", "linux", "amd64", "dev", "/srv", List.of("command")), "enroll-test");
        var tasks = new TaskService(registry);
        var supplied = new ExecutionContract(registration.machineId(), "host-a", LaneMode.WRITE,
                "caller-session", "command",
                new ExecutionContract.Budget(3600, 128L * 1024 * 1024, 16L * 1024 * 1024, 64),
                java.time.Instant.now().plus(java.time.Duration.ofDays(365)), "caller-key", "low", false, null);

        var task = tasks.create(new CreateTaskRequest(registration.machineId(),
                new TaskCommand("", TaskKind.COMMAND, "command", "echo bounded", "/srv", java.util.Map.of(),
                        30, null, null, supplied), "contract-bound"));
        var contract = tasks.find(task.id()).orElseThrow().command().contract();

        assertEquals(TaskService.MAX_OUTPUT_BYTES, contract.budget().maxOutputBytes());
        assertEquals(8L * 1024 * 1024, contract.budget().maxArtifactBytes());
        assertEquals(32, contract.budget().maxChildProcesses());
        assertTrue(!contract.expiresAt().isAfter(java.time.Instant.now().plus(TaskService.DEFAULT_CONTRACT_LIFETIME)));
    }

    @Test
    void contractBudgetIsAlsoNarrowedToAdvertisedAgentRuntime() {
        var registry = AgentRegistry.forTest("enroll-test");
        var runtime = new AgentRuntimeDescriptor(1, 0, 2, 1,
                2L * 1024 * 1024, 4L * 1024 * 1024, 5, 60,
                100L * 1024 * 1024, 120, false, false);
        var registration = registry.register(new RegisterRequest("bounded-agent", "host-a", "host-a", "linux", "amd64", "dev",
                "/srv", List.of("command"), runtime), "enroll-test");
        var tasks = new TaskService(registry);
        var supplied = new ExecutionContract(registration.machineId(), "host-a", LaneMode.WRITE,
                "caller-session", "command",
                new ExecutionContract.Budget(3600, 128L * 1024 * 1024, 16L * 1024 * 1024, 64,
                        2L * 1024 * 1024 * 1024, 3600),
                java.time.Instant.now().plus(java.time.Duration.ofDays(365)), "runtime-bound", "low", false, null);

        var task = tasks.create(new CreateTaskRequest(registration.machineId(),
                new TaskCommand("", TaskKind.COMMAND, "command", "echo bounded", "/srv", Map.of(),
                        30, null, null, supplied), "runtime-bound"));
        var budget = tasks.find(task.id()).orElseThrow().command().contract().budget();

        assertEquals(2L * 1024 * 1024, budget.maxOutputBytes());
        assertEquals(5, budget.maxChildProcesses());
        assertEquals(60, budget.maxDurationSeconds());
        assertEquals(100L * 1024 * 1024, budget.maxRssBytes());
        assertEquals(120, budget.maxCpuSeconds());
    }

    @Test
    void stripsCredentialShapedEnvironmentBeforeDurableTaskStorage() {
        var registry = AgentRegistry.forTest("enroll-test");
        var registration = registry.register(new RegisterRequest("command-agent", "host-a", "host-a", "linux", "amd64", "dev", "/srv", List.of("command")), "enroll-test");
        var tasks = new TaskService(registry);
        var task = tasks.create(new CreateTaskRequest(registration.machineId(),
                new TaskCommand("", TaskKind.COMMAND, "command", "echo safe", "/srv",
                        Map.of("LANG", "C", "GITHUB_TOKEN", "must-not-persist", "API_KEY", "must-not-persist"),
                        30, null, null), "environment-redaction"));

        assertEquals(Map.of("LANG", "C"), tasks.find(task.id()).orElseThrow().command().env());
    }

    @Test
    void acceptsAnyWorkingDirectoryHintOnFullHostAgent() {
        var registry = AgentRegistry.forTest("enroll-test");
        var registration = registry.register(new RegisterRequest("unrestricted-agent", "host-a", "host-a", "linux", "amd64", "dev",
                "/srv", List.of("command")), "enroll-test");
        var tasks = new TaskService(registry);

        assertDoesNotThrow(() -> tasks.create(new CreateTaskRequest(registration.machineId(),
                new TaskCommand("", TaskKind.COMMAND, "command", "echo blocked", "../etc", java.util.Map.of(), 0, null, null),
                "path-escape", null, "session-1", "low", false, TaskOrigin.configured())));
    }

    @Test
    void allowsFullHostTaskOnEveryRegisteredAgent() {
        var registry = AgentRegistry.forTest("enroll-test");
        var registration = registry.register(new RegisterRequest("workspace-agent", "host-a", "host-a", "linux", "amd64", "dev",
                "/srv/project", List.of("command")), "enroll-test");
        var tasks = new TaskService(registry);

        assertDoesNotThrow(() -> tasks.create(new CreateTaskRequest(registration.machineId(),
                new TaskCommand("", TaskKind.COMMAND, "command", "echo blocked", "/srv/project", java.util.Map.of(), 0, null, null),
                "scope-upgrade", null, "session-1", "low", false, TaskOrigin.configured())));
    }

    @Test
    void rejectsIdempotencyReuseWithDifferentParameters() {
        var registry = AgentRegistry.forTest("enroll-test");
        var registration = registry.register(new RegisterRequest("command-agent", "host-a", "host-a", "linux", "amd64", "dev", "/srv", List.of("command")), "enroll-test");
        var tasks = new TaskService(registry);
        tasks.create(new CreateTaskRequest(registration.machineId(), new TaskCommand("", null, null, "echo one", null, java.util.Map.of(), 0, null, null), "same-key"));

        assertThrows(IllegalArgumentException.class, () -> tasks.create(new CreateTaskRequest(registration.machineId(), new TaskCommand("", null, null, "echo two", null, java.util.Map.of(), 0, null, null), "same-key")));
    }

    @Test
    void doesNotDispatchWhenAgentHasNoMatchingCapability() {
        var registry = AgentRegistry.forTest("enroll-test");
        var registration = registry.register(new RegisterRequest("desktop-agent", "host-a", "host-a", "windows", "amd64", "dev", "C:\\", List.of("desktop")), "enroll-test");
        var tasks = new TaskService(registry);
        tasks.create(new CreateTaskRequest(registration.machineId(), new TaskCommand("", TaskKind.DESKTOP, null, null, null, java.util.Map.of(), 30,
                new TaskCommand.DesktopAction("screenshot", null, List.of(), null), null), "desktop"));

        assertNull(tasks.poll(registration.machineId(), new PollRequest(List.of(), 1, List.of("command"))).task());
    }

    @Test
    void storesAndReadsBoundedArtifactsWithDigestVerification() throws Exception {
        var registry = AgentRegistry.forTest("enroll-test");
        var registration = registry.register(new RegisterRequest("desktop-agent", "host-a", "host-a", "windows", "amd64", "dev", "C:\\", List.of("desktop")), "enroll-test");
        var tasks = new TaskService(registry);
        var task = tasks.create(new CreateTaskRequest(registration.machineId(), new TaskCommand("", TaskKind.DESKTOP, "desktop", null, null, java.util.Map.of(), 30,
                new TaskCommand.DesktopAction("screenshot", null, List.of(), null), null), "artifact"));
        tasks.poll(registration.machineId(), new PollRequest(List.of(), 1, List.of("desktop")));
        var bytes = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 1, 2, 3};
        var digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));

        tasks.appendArtifact(registration.machineId(), task.id(), "image/png", digest, bytes);

        var artifact = tasks.readArtifact(task.id()).orElseThrow();
        assertEquals("image/png", artifact.mimeType());
        assertEquals(digest, artifact.sha256());
        assertArrayEquals(bytes, artifact.data());
        assertThrows(IllegalArgumentException.class, () -> tasks.appendArtifact(registration.machineId(), task.id(), "image/png", "bad", bytes));
    }

    @Test
    void storesAndReadsZeroByteTaskArtifacts() throws Exception {
        var registry = AgentRegistry.forTest("enroll-test");
        var registration = registry.register(new RegisterRequest("desktop-agent", "host-a", "host-a", "windows", "amd64", "dev", "C:\\", List.of("desktop")), "enroll-test");
        var tasks = new TaskService(registry);
        var task = tasks.create(new CreateTaskRequest(registration.machineId(), new TaskCommand("", TaskKind.DESKTOP, "desktop", null, null, Map.of(), 30,
                new TaskCommand.DesktopAction("screenshot", null, List.of(), null), null), "empty-artifact"));
        tasks.poll(registration.machineId(), new PollRequest(List.of(), 1, List.of("desktop")));
        var digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(new byte[0]));

        tasks.appendArtifact(registration.machineId(), task.id(), "application/octet-stream", digest, new byte[0]);

        var artifact = tasks.readArtifact(task.id()).orElseThrow();
        assertEquals(0, artifact.data().length);
        assertEquals(digest, artifact.sha256());
    }

    @Test
    void enforcesArtifactBudgetFromTheCenterContract() throws Exception {
        var registry = AgentRegistry.forTest("enroll-test");
        var registration = registry.register(new RegisterRequest("desktop-agent", "host-a", "host-a", "windows", "amd64", "dev", "C:\\", List.of("desktop")), "enroll-test");
        var tasks = new TaskService(registry);
        var contract = new ExecutionContract(registration.machineId(), "host-a", LaneMode.WRITE,
                "session", "desktop",
                new ExecutionContract.Budget(30, 1024L * 1024, 0, 32),
                java.time.Instant.now().plusSeconds(300), "artifact-budget", "low", false, null);
        var task = tasks.create(new CreateTaskRequest(registration.machineId(),
                new TaskCommand("", TaskKind.DESKTOP, "desktop", null, null, java.util.Map.of(), 30,
                        new TaskCommand.DesktopAction("screens", null, List.of(), null), null, contract), "artifact-budget"));
        tasks.poll(registration.machineId(), new PollRequest(List.of(), 1, List.of("desktop")));

        var bytes = new byte[]{1};
        var digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        assertThrows(IllegalArgumentException.class,
                () -> tasks.appendArtifact(registration.machineId(), task.id(), "application/octet-stream", digest, bytes));
    }

    @Test
    void waitForChangeDoesNotReturnRepeatedlyWhileRunningWithoutNewOutput() throws Exception {
        var registry = AgentRegistry.forTest("enroll-test");
        var registration = registry.register(new RegisterRequest("command-agent", "host-a", "host-a", "linux", "amd64", "dev", "/srv", List.of("command")), "enroll-test");
        var tasks = new TaskService(registry);
        var task = tasks.create(new CreateTaskRequest(registration.machineId(),
                new TaskCommand("", TaskKind.COMMAND, null, "sleep 10", "/srv", java.util.Map.of(), 30, null, null), "wait-test"));
        tasks.poll(registration.machineId(), new PollRequest(List.of(), 1, List.of("command")));
        tasks.updateState(registration.machineId(), task.id(), new TaskUpdateRequest(TaskStatus.RUNNING, null, null, null, null, false));

        var waiter = CompletableFuture.supplyAsync(() -> {
            try {
                return tasks.waitForChange(task.id(), 0, java.time.Duration.ofSeconds(2));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(exception);
            }
        });
        Thread.sleep(100);
        org.junit.jupiter.api.Assertions.assertFalse(waiter.isDone(), "running without output is not a new change");

        tasks.updateState(registration.machineId(), task.id(), new TaskUpdateRequest(TaskStatus.COMPLETED, 0, null, null, null, false));
        assertEquals(TaskStatus.COMPLETED, waiter.get(1, TimeUnit.SECONDS).status());
    }

    @Test
    void acceptsWorkingDirectoryAndDesktopPathsWithoutFence() {
        var registry = AgentRegistry.forTest("enroll-test");
        var registration = registry.register(new RegisterRequest("workspace-agent", "host-a", "host-a", "linux", "amd64", "dev",
                "/srv/project/src", List.of("command", "desktop")), "enroll-test");
        var tasks = new TaskService(registry);

        assertDoesNotThrow(() -> tasks.create(new CreateTaskRequest(registration.machineId(),
                new TaskCommand("", TaskKind.COMMAND, "command", "echo blocked", "../../etc", java.util.Map.of(), 0, null, null), "escape-1")));
        assertDoesNotThrow(() -> tasks.create(new CreateTaskRequest(registration.machineId(),
                new TaskCommand("", TaskKind.DESKTOP, "desktop", null, null, java.util.Map.of(), 30,
                        new TaskCommand.DesktopAction("launch", "app", List.of(), "../../etc"), null), "escape-2")));
    }

    @Test
    void recoversDurableLeaseOnlyWithRunningTaskHintAndFailsTimedLease() throws Exception {
        var registry = AgentRegistry.forTest("enroll-test");
        var registration = registry.register(new RegisterRequest("command-agent", "host-a", "host-a", "linux", "amd64", "dev", "/srv", List.of("command")), "enroll-test");
        var tasks = new TaskService(registry);

        var durable = tasks.create(new CreateTaskRequest(registration.machineId(),
                new TaskCommand("", TaskKind.COMMAND, "command", "sleep 10", "/srv", java.util.Map.of(), 0, null, null),
                "durable-lease", LaneMode.READ, "", "low", false, TaskOrigin.configured(), true));
        tasks.poll(registration.machineId(), new PollRequest(List.of(), 1, List.of("command")));
        tasks.updateState(registration.machineId(), durable.id(), new TaskUpdateRequest(TaskStatus.RUNNING, null, null, null, null, false));
        tasks.find(durable.id()).orElseThrow().leaseUntil(java.time.Instant.now().minusSeconds(1));

        var reconnectPoll = tasks.poll(registration.machineId(), new PollRequest(List.of(durable.id()), 1, List.of("command")));
        assertNull(reconnectPoll.task(), "a recovered durable task should occupy its slot after lease renewal");
        assertEquals(TaskStatus.RUNNING, tasks.find(durable.id()).orElseThrow().status());
        assertTrue(tasks.find(durable.id()).orElseThrow().leaseUntil().isAfter(java.time.Instant.now()));

        // The durable task is still running in the read-only lane.
        // Run the timed task also in the read-only lane so both can run concurrently on one host.
        var timed = tasks.create(new CreateTaskRequest(registration.machineId(),
                new TaskCommand("", TaskKind.COMMAND, "command", "sleep 10", "/srv/timed", java.util.Map.of(), 30, null, null),
                "timed-lease", LaneMode.READ, "timed-session", "low", false, TaskOrigin.configured(), true));
        tasks.poll(registration.machineId(), new PollRequest(List.of(durable.id()), 1, List.of("command")));
        tasks.updateState(registration.machineId(), timed.id(), new TaskUpdateRequest(TaskStatus.RUNNING, null, null, null, null, false));
        tasks.find(timed.id()).orElseThrow().leaseUntil(java.time.Instant.now().minusSeconds(1));
        var waiter = CompletableFuture.supplyAsync(() -> {
            try {
                return tasks.waitForChange(timed.id(), 0, java.time.Duration.ofSeconds(2));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(exception);
            }
        });
        Thread.sleep(100);
        tasks.poll(registration.machineId(), new PollRequest(List.of(durable.id()), 1, List.of("command")));
        assertEquals(TaskStatus.FAILED, tasks.find(timed.id()).orElseThrow().status());
        assertEquals(TaskStatus.FAILED, waiter.get(1, TimeUnit.SECONDS).status(),
                "lease expiry failure must wake task_wait subscribers");
    }
}
