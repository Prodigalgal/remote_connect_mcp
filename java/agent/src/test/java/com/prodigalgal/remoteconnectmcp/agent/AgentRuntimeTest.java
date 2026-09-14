package com.prodigalgal.remoteconnectmcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.prodigalgal.remoteconnectmcp.protocol.PollRequest;
import com.prodigalgal.remoteconnectmcp.protocol.PollResponse;
import com.prodigalgal.remoteconnectmcp.protocol.OutputResponse;
import com.prodigalgal.remoteconnectmcp.protocol.ArtifactResponse;
import com.prodigalgal.remoteconnectmcp.protocol.RegisterResponse;
import com.prodigalgal.remoteconnectmcp.protocol.ScopeMode;
import com.prodigalgal.remoteconnectmcp.protocol.TaskUpdateRequest;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentRuntimeTest {
    @Test
    void duplicateTaskRegistrationKeepsTheFirstRunner() {
        var running = new ConcurrentHashMap<String, java.util.concurrent.Future<?>>();
        var first = new FutureTask<Void>(() -> null);
        var duplicate = new FutureTask<Void>(() -> null);

        assertTrue(AgentRuntime.registerTaskIfAbsent(running, "task-1", first));
        assertTrue(!AgentRuntime.registerTaskIfAbsent(running, "task-1", duplicate));
        assertEquals(first, running.get("task-1"));
    }

    @Test
    void registersOncePersistsIdentityAndAdvertisesAvailableSlot(@TempDir Path tempDir) throws Exception {
        var transport = new ScriptedTransport();
        transport.registerResponse = new RegisterResponse("machine_new", "daily-new");
        transport.pollFailure = new InterruptedException("stop test");
        var store = new AgentIdentityStore(tempDir);
        var runtime = new AgentRuntime(config(tempDir), transport, store);

        assertThrows(InterruptedException.class, runtime::run);

        assertEquals(1, transport.registerCalls.get());
        assertEquals(new AgentIdentity("machine_new", "daily-new"), store.load().orElseThrow());
        assertEquals(1, transport.lastPoll.availableSlots());
    }

    @Test
    void rejectedDailyTokenTriggersReEnrollment(@TempDir Path tempDir) throws Exception {
        var transport = new ScriptedTransport();
        transport.registerResponse = new RegisterResponse("machine_new", "daily-new");
        transport.pollFailure = new CenterTransportException("expired", 401);
        transport.secondPollFailure = new InterruptedException("stop test");
        var store = new AgentIdentityStore(tempDir);
        var runtime = new AgentRuntime(config(tempDir), transport, store);

        assertThrows(InterruptedException.class, runtime::run);

        assertEquals(2, transport.registerCalls.get());
        assertEquals(2, transport.pollCalls.get());
        assertEquals(new AgentIdentity("machine_new", "daily-new"), store.load().orElseThrow());
    }

    @Test
    void missingEnrollmentTokenIsAllowedOnlyWhenIdentityAlreadyExists(@TempDir Path tempDir) throws Exception {
        var transport = new ScriptedTransport();
        var store = new AgentIdentityStore(tempDir);
        store.save(new AgentIdentity("machine_existing", "daily-existing"));
        transport.pollFailure = new InterruptedException("stop test");
        var config = new AgentConfig(URI.create("https://center.invalid"), "", "agent", "host", tempDir.toString(),
                ScopeMode.UNRESTRICTED, null, List.of("command"), false, tempDir, Duration.ofMillis(1), 1);

        assertThrows(InterruptedException.class, () -> new AgentRuntime(config, transport, store).run());
        assertEquals(0, transport.registerCalls.get());
        assertEquals(1, transport.pollCalls.get());
    }

    @Test
    void rejectedDailyTokenWithoutEnrollmentKeepsIdentityAndRetries(@TempDir Path tempDir) throws Exception {
        var transport = new ScriptedTransport();
        transport.pollFailure = new CenterTransportException("expired", 401);
        transport.secondPollFailure = new InterruptedException("stop test");
        var store = new AgentIdentityStore(tempDir);
        var existing = new AgentIdentity("machine_existing", "daily-existing");
        store.save(existing);
        var config = new AgentConfig(URI.create("https://center.invalid"), "", "agent", "host", tempDir.toString(),
                ScopeMode.UNRESTRICTED, null, List.of("command"), false, tempDir, Duration.ofMillis(1), 1);

        assertThrows(InterruptedException.class, () -> new AgentRuntime(config, transport, store).run());

        assertEquals(0, transport.registerCalls.get());
        assertEquals(2, transport.pollCalls.get());
        assertEquals(existing, store.load().orElseThrow());
    }

    @Test
    void idleLoopHonorsHotReloadedPollInterval(@TempDir Path tempDir) throws Exception {
        var transport = new HotConfigTransport();
        var store = new AgentIdentityStore(tempDir);
        var base = new AgentConfig(URI.create("https://center.invalid"), "enrollment", "agent", "host",
                tempDir.toString(), ScopeMode.UNRESTRICTED, null, List.of("command"), false,
                tempDir, Duration.ofSeconds(5), 1);
        var started = System.nanoTime();

        assertThrows(InterruptedException.class, () -> new AgentRuntime(base, transport, store).run());

        var elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertEquals(2, transport.pollCalls.get());
        assertTrue(elapsedMillis < 3000,
                "idle loop ignored the hot-reloaded 250ms interval: " + elapsedMillis + "ms");
    }

    @Test
    void commandTimeoutIsNotBlockedByOutputDrain(@TempDir Path tempDir) {
        var transport = new RecordingTransport();
        var config = config(tempDir);
        var task = new com.prodigalgal.remoteconnectmcp.protocol.TaskCommand(
                "task_timeout", com.prodigalgal.remoteconnectmcp.protocol.TaskKind.COMMAND,
                "command", longRunningCommand(), null, java.util.Map.of(), 1, null, java.time.Instant.now());
        var started = System.nanoTime();

        new CommandRunner(config, new AgentIdentity("machine_new", "daily-new"), task, transport).run();

        var elapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(elapsedMillis < 5000, "timeout path was blocked by stdout draining: " + elapsedMillis + "ms");
        assertTrue(transport.statuses.contains("running"));
        assertTrue(transport.statuses.contains("failed"));
        assertEquals("command timed out", transport.errors.getLast());
    }

    @Test
    void transientOutputFailureIsRetriedWithoutKillingTheChild(@TempDir Path tempDir) {
        var transport = new FlakyOutputTransport();
        var task = new com.prodigalgal.remoteconnectmcp.protocol.TaskCommand(
                "task_retry", com.prodigalgal.remoteconnectmcp.protocol.TaskKind.COMMAND,
                "command", "echo retry-ok", tempDir.toString(), java.util.Map.of(), 10, null, java.time.Instant.now());

        new CommandRunner(config(tempDir), new AgentIdentity("machine_new", "daily-new"), task, transport).run();

        assertEquals(1, transport.outputFailures.get());
        assertTrue(transport.statuses.contains("completed"), "statuses=" + transport.statuses);
    }

    @Test
    void noTimeoutCommandUsesDurableRecordAndCleansItAfterDelivery(@TempDir Path tempDir) throws Exception {
        var transport = new RecordingTransport();
        var config = config(tempDir);
        var store = new DurableTaskStore(tempDir);
        var task = new com.prodigalgal.remoteconnectmcp.protocol.TaskCommand(
                "task_durable", com.prodigalgal.remoteconnectmcp.protocol.TaskKind.COMMAND,
                "command", "echo durable-ok", tempDir.toString(), java.util.Map.of(), 0, null, java.time.Instant.now());

        new DurableCommandRunner(config, new AgentIdentity("machine_new", "daily-new"), task, transport, store).run();

        assertTrue(transport.statuses.contains("running"));
        assertTrue(transport.statuses.contains("completed"), "statuses=" + transport.statuses);
        assertTrue(store.load().isEmpty(), "records=" + store.load());
    }

    @Test
    void durableProcessCanBeAttachedAfterRunnerInterruption(@TempDir Path tempDir) throws Exception {
        var transport = new RecordingTransport();
        var config = config(tempDir);
        var store = new DurableTaskStore(tempDir);
        var task = new com.prodigalgal.remoteconnectmcp.protocol.TaskCommand(
                "task_recover", com.prodigalgal.remoteconnectmcp.protocol.TaskKind.COMMAND,
                "command", durableSleepCommand(), tempDir.toString(), java.util.Map.of(), 0, null, java.time.Instant.now());
        var first = new DurableCommandRunner(config, new AgentIdentity("machine_new", "daily-new"), task, transport, store);
        var thread = Thread.startVirtualThread(first);
        for (var i = 0; i < 40 && store.load().isEmpty(); i++) {
            Thread.sleep(50);
        }
        assertTrue(!store.load().isEmpty(), "durable record was not written");
        thread.interrupt();
        thread.join(3000);

        var record = store.load().stream().findFirst().orElseThrow();
        new DurableCommandRunner(config, new AgentIdentity("machine_new", "daily-new"), record.taskCommand(), transport, store, record).run();

        assertTrue(transport.statuses.contains("completed"), "statuses=" + transport.statuses);
        assertTrue(store.load().isEmpty(), "records=" + store.load());
    }

    @Test
    void durableOutputCeilingStopsNoisyOfflineCommand(@TempDir Path tempDir) throws Exception {
        var transport = new RecordingTransport();
        var config = config(tempDir, 1024L * 1024);
        var store = new DurableTaskStore(tempDir);
        var task = new com.prodigalgal.remoteconnectmcp.protocol.TaskCommand(
                "task_durable_limit", com.prodigalgal.remoteconnectmcp.protocol.TaskKind.COMMAND,
                "command", noisyCommand(), tempDir.toString(), java.util.Map.of(), 0, null, java.time.Instant.now());

        new DurableCommandRunner(config, new AgentIdentity("machine_new", "daily-new"), task, transport, store).run();

        assertTrue(transport.statuses.contains("failed"), "statuses=" + transport.statuses);
        assertTrue(transport.errors.stream().anyMatch(value -> value.contains("output exceeded")),
                "errors=" + transport.errors);
        assertTrue(store.load().isEmpty(), "records=" + store.load());
    }

    private static String durableSleepCommand() {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            // Leave a generous, deterministic attach window on busy Windows
            // runners.  The first runner is intentionally interrupted before
            // the process exits; a short ping made the test race with the
            // offline-completion fallback and report a false failure.
            return "ping -n 20 127.0.0.1 > NUL & echo recovered";
        }
        return "sleep 5; echo recovered";
    }

    private static String longRunningCommand() {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return "ping -n 8 127.0.0.1 > NUL";
        }
        return "sleep 8";
    }

    private static String noisyCommand() {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return "powershell.exe -NoLogo -NoProfile -NonInteractive -Command \"$s='x' * 1100000; [Console]::Out.Write($s)\"";
        }
        return "head -c 1100000 /dev/zero";
    }

    private static AgentConfig config(Path stateDir) {
        return config(stateDir, 64L * 1024 * 1024);
    }

    private static AgentConfig config(Path stateDir, long maxOutputBytes) {
        return new AgentConfig(URI.create("https://center.invalid"), "enrollment", "agent", "host", stateDir.toString(),
                ScopeMode.UNRESTRICTED, null, List.of("command"), false, stateDir, Duration.ofMillis(1), 1, maxOutputBytes);
    }

    private static final class ScriptedTransport implements AgentTransport {
        private final AtomicInteger registerCalls = new AtomicInteger();
        private final AtomicInteger pollCalls = new AtomicInteger();
        private RegisterResponse registerResponse;
        private Exception pollFailure;
        private Exception secondPollFailure;
        private PollRequest lastPoll;

        @Override
        public RegisterResponse register(AgentConfig config) {
            registerCalls.incrementAndGet();
            return registerResponse;
        }

        @Override
        public PollResponse poll(String machineId, String token, PollRequest request) throws IOException, InterruptedException {
            lastPoll = request;
            var count = pollCalls.incrementAndGet();
            var failure = count == 1 ? pollFailure : secondPollFailure;
            if (failure instanceof IOException io) {
                throw io;
            }
            if (failure instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            return PollResponse.empty();
        }

        @Override
        public void updateState(String machineId, String token, String taskId, TaskUpdateRequest request) {
        }

        @Override
        public OutputResponse appendOutput(String machineId, String token, String taskId, long offset, byte[] data) {
            return new OutputResponse(offset + data.length);
        }

        @Override
        public ArtifactResponse appendArtifact(String machineId, String token, String taskId, String mimeType, String sha256, byte[] data) {
            return new ArtifactResponse(data.length, sha256);
        }
    }

    private static final class HotConfigTransport implements AgentTransport {
        private final AtomicInteger pollCalls = new AtomicInteger();

        @Override
        public RegisterResponse register(AgentConfig config) {
            return new RegisterResponse("machine_new", "daily-new");
        }

        @Override
        public PollResponse poll(String machineId, String token, PollRequest request) throws InterruptedException {
            if (pollCalls.incrementAndGet() == 1) {
                return new PollResponse(null, List.of(), null,
                        new com.prodigalgal.remoteconnectmcp.protocol.AgentConfigUpdate(1, 250, 1));
            }
            throw new InterruptedException("stop test");
        }

        @Override
        public void updateState(String machineId, String token, String taskId, TaskUpdateRequest request) {
        }

        @Override
        public OutputResponse appendOutput(String machineId, String token, String taskId, long offset, byte[] data) {
            return new OutputResponse(offset + data.length);
        }

        @Override
        public ArtifactResponse appendArtifact(String machineId, String token, String taskId, String mimeType,
                                               String sha256, byte[] data) {
            return new ArtifactResponse(data.length, sha256);
        }
    }

    private static class RecordingTransport implements AgentTransport {
        protected final List<String> statuses = new CopyOnWriteArrayList<>();
        private final List<String> errors = new CopyOnWriteArrayList<>();

        @Override
        public RegisterResponse register(AgentConfig config) {
            return new RegisterResponse("machine_new", "daily-new");
        }

        @Override
        public PollResponse poll(String machineId, String token, PollRequest request) {
            return PollResponse.empty();
        }

        @Override
        public void updateState(String machineId, String token, String taskId, TaskUpdateRequest request) {
            statuses.add(request.status());
            if (request.error() != null) {
                errors.add(request.error());
            }
        }

        @Override
        public OutputResponse appendOutput(String machineId, String token, String taskId, long offset, byte[] data) throws IOException {
            return new OutputResponse(offset + data.length);
        }

        @Override
        public ArtifactResponse appendArtifact(String machineId, String token, String taskId, String mimeType, String sha256, byte[] data) {
            return new ArtifactResponse(data.length, sha256);
        }
    }

    private static final class FlakyOutputTransport extends RecordingTransport {
        private final AtomicInteger outputFailures = new AtomicInteger();

        @Override
        public OutputResponse appendOutput(String machineId, String token, String taskId, long offset, byte[] data) throws IOException {
            if (outputFailures.compareAndSet(0, 1)) {
                throw new IOException("temporary center outage");
            }
            return new OutputResponse(offset + data.length);
        }
    }
}
