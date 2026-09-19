package com.prodigalgal.remoteconnectmcp.agent;

import com.prodigalgal.remoteconnectmcp.protocol.OutputResponse;
import com.prodigalgal.remoteconnectmcp.protocol.TaskCommand;
import com.prodigalgal.remoteconnectmcp.protocol.TaskUpdateRequest;
import com.prodigalgal.remoteconnectmcp.protocol.TaskProgressUpdate;
import com.prodigalgal.remoteconnectmcp.protocol.SensitiveValueRedactor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs a no-timeout command with stdout redirected to a durable local file.
 * The file/PID record survives Agent restarts; the next runtime can attach to
 * the still-running process and resume the same Center task without replaying
 * the command.
 */
final class DurableCommandRunner implements Runnable {
    private static final Logger LOG = Logger.getLogger(DurableCommandRunner.class.getName());
    private static final int CHUNK_SIZE = 16 * 1024;
    /**
     * Windows can deliver Process.onExit after the process handle is already
     * observed as terminated. Give the original watcher a bounded, event-based
     * grace period to persist the real exit code before recovery treats the
     * result as an unknown offline failure.
     */
    private static final Duration COMPLETION_RECORD_GRACE = Duration.ofSeconds(10);

    private final AgentConfig config;
    private final AgentIdentity identity;
    private final TaskCommand task;
    private final AgentTransport transport;
    private final DurableTaskStore store;
    private final DurableTaskStore.Record recovered;
    private final AgentResourceBudget resourceBudget;
    private final AgentProcessBudget processBudget;
    private volatile boolean cancelRequested;

    DurableCommandRunner(AgentConfig config, AgentIdentity identity, TaskCommand task,
                         AgentTransport transport, DurableTaskStore store) {
        this(config, identity, task, transport, store, null, null);
    }

    DurableCommandRunner(AgentConfig config, AgentIdentity identity, TaskCommand task,
                         AgentTransport transport, DurableTaskStore store, DurableTaskStore.Record recovered) {
        this(config, identity, task, transport, store, recovered, null);
    }

    DurableCommandRunner(AgentConfig config, AgentIdentity identity, TaskCommand task,
                         AgentTransport transport, DurableTaskStore store, DurableTaskStore.Record recovered,
                         AgentResourceBudget resourceBudget) {
        this(config, identity, task, transport, store, recovered, resourceBudget,
                new AgentProcessBudget(config.maxTotalChildProcesses()));
    }

    DurableCommandRunner(AgentConfig config, AgentIdentity identity, TaskCommand task,
                         AgentTransport transport, DurableTaskStore store, DurableTaskStore.Record recovered,
                         AgentResourceBudget resourceBudget, AgentProcessBudget processBudget) {
        this.config = config;
        this.identity = identity;
        this.task = task;
        this.transport = transport;
        this.store = store;
        this.recovered = recovered;
        this.resourceBudget = resourceBudget;
        this.processBudget = processBudget;
    }

    void requestCancel() {
        cancelRequested = true;
    }

    @Override
    public void run() {
        DurableTaskStore.Record record = recovered;
        Process process = null;
        ProcessResourceSupervisor resourceSupervisor = null;
        TaskProgressFileWatcher progressWatcher = null;
        try {
            if (record == null) {
                var cwd = AgentPaths.resolveCwd(config, identity.machineId(), task, task.cwd());
                var started = store.start(task, cwd, TaskLimits.outputBytes(config, task), resourceBudget);
                process = started.process();
                record = started.record();
            }
            var handle = process == null ? ProcessHandle.of(record.pid()).orElse(null) : process.toHandle();
            if (handle == null) {
                throw new IOException("durable process is no longer available");
            }
            if (process == null && !record.completed()) {
                store.watchRecoveredCompletion(record, handle);
            }
            var handleForSupervisor = handle;
            resourceSupervisor = ProcessResourceSupervisor.start(handleForSupervisor, config, task,
                    () -> terminate(handleForSupervisor), processBudget);
            progressWatcher = TaskProgressFileWatcher.startIfConfigured(LOG, config, identity, task, transport);
            store.guard(record, TaskLimits.outputBytes(config, task), resourceBudget);

            if (!record.completed()) {
                sendState(new TaskUpdateRequest("running", null, null, Instant.now(), null, false));
                TaskProgressReporter.send(LOG, transport, identity, task,
                        new TaskProgressUpdate("running", 0, "durable command resumed", null, null, null));
            }
            var relay = relayOutput(Path.of(record.outputPath()), handle);
            // The original Agent may have been interrupted while its watcher
            // still had the child completion callback in flight. Give that
            // atomic record update a short, bounded window before treating a
            // recovered process as an offline failure. This is especially
            // important on Windows, where cmd.exe can release its process
            // handle just before the onExit callback runs.
            record = awaitCompletedRecord(record);
            var exitCode = record.completed() && record.exitCode() != null ? record.exitCode() : -1;
            var error = record.completed() ? record.error() : null;
            if (!record.completed()) {
                if (process != null) {
                    exitCode = process.exitValue();
                } else {
                    error = "durable process exited while Agent was offline";
                }
                if (exitCode != 0 && (error == null || error.isBlank())) {
                    error = "command exited with code " + exitCode;
                }
                store.markCompleted(record, exitCode, error);
            }
            var resourceViolation = resourceSupervisor == null ? null : resourceSupervisor.violation();
            if (resourceViolation != null && !resourceViolation.isBlank()) {
                error = resourceViolation;
                if (exitCode == 0) exitCode = -1;
            }
            var guardError = store.guardError(record.taskId());
            var finalError = relay.error() == null || relay.error().isBlank()
                    ? (guardError == null || guardError.isBlank() ? error : guardError) : relay.error();
            var status = exitCode == 0 && (finalError == null || finalError.isBlank()) ? "completed" : "failed";
            sendState(new TaskUpdateRequest(status, exitCode, finalError, null, Instant.now(), relay.truncated()));
            store.remove(record);
        } catch (InterruptedException exception) {
            if (cancelRequested) {
                terminate(process, record);
                reportCanceled(record);
            } else {
                // Service shutdown: leave the process and record untouched so a
                // new Agent instance can recover it.
                LOG.info(() -> "durable task detached for Agent shutdown: " + task.id());
            }
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            if (process != null && process.isAlive()) terminate(process, record);
            if (record != null) {
                try {
                    store.markCompleted(record, -1, compactError(exception.getMessage()));
                    sendState(new TaskUpdateRequest("failed", -1, compactError(exception.getMessage()), null, Instant.now(), false));
                    store.remove(record);
                } catch (Exception reportFailure) {
                    LOG.log(Level.WARNING, "could not report durable task failure " + task.id(), reportFailure);
                }
            } else {
                try {
                    sendState(new TaskUpdateRequest("failed", -1, compactError(exception.getMessage()), null, Instant.now(), false));
                } catch (Exception reportFailure) {
                    LOG.log(Level.WARNING, "could not report durable task failure " + task.id(), reportFailure);
                }
            }
        } finally {
            if (resourceSupervisor != null) resourceSupervisor.close();
            if (progressWatcher != null) progressWatcher.close();
        }
    }

    private RelayResult relayOutput(Path outputPath, ProcessHandle process) throws IOException, InterruptedException {
        if (!java.nio.file.Files.isRegularFile(outputPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("durable output file is missing");
        }
        var outputLimit = TaskLimits.outputBytes(config, task);
        var truncated = false;
        var limitExceeded = false;
        var centerTruncated = false;
        long offset = 0;
        try (var channel = FileChannel.open(outputPath, LinkOption.NOFOLLOW_LINKS, StandardOpenOption.READ);
             var watcher = openWatcher(outputPath)) {
            // ProcessHandle.onExit is the authoritative completion event. Do
            // not repeatedly query isAlive() at the bottom of the output loop:
            // a completed process must wake the runner exactly once even when
            // its final directory event races the callback.
            var completion = process.onExit();
            var exited = new AtomicBoolean(completion.isDone());
            if (watcher != null) {
                completion.thenRun(() -> {
                    exited.set(true);
                    closeQuietly(watcher);
                });
            }
            while (true) {
                var fileSize = channel.size();
                var boundedSize = Math.min(fileSize, outputLimit);
                while (offset < boundedSize) {
                    var length = (int) Math.min(CHUNK_SIZE, boundedSize - offset);
                    var data = new byte[length];
                    var buffer = ByteBuffer.wrap(data);
                    channel.position(offset);
                    while (buffer.hasRemaining()) {
                        if (channel.read(buffer) < 0) throw new IOException("durable output ended early");
                    }
                    var uploadOffset = offset;
                    OutputResponse response = AgentRetry.call(LOG, "durable output upload " + task.id(),
                            () -> transport.appendOutput(identity.machineId(), identity.token(), task.id(), task.attempt(), uploadOffset, data));
                    var next = response.nextOffset();
                    if (next < uploadOffset || next > uploadOffset + data.length) {
                        throw new IOException("Center returned an invalid output cursor: " + next);
                    }
                    if (next < uploadOffset + data.length) {
                        // The Center retained only a bounded prefix. Stop
                        // sending beyond its cursor; otherwise the next
                        // request would be rejected as an offset-ahead replay.
                        truncated = true;
                        centerTruncated = true;
                        break;
                    }
                    offset = Math.max(uploadOffset + data.length, next);
                    fileSize = channel.size();
                    boundedSize = Math.min(fileSize, outputLimit);
                }
                if (fileSize > outputLimit) {
                    truncated = true;
                    if (!limitExceeded) {
                        // Redirect.to(...) is deliberately used so a durable
                        // command survives an Agent restart.  The companion
                        // trade-off is that the child can otherwise grow the
                        // file without bound while the Center is offline.
                        // Enforce the configured disk/output ceiling by
                        // terminating that task and report a deterministic
                        // failure after the retained prefix is uploaded.
                        limitExceeded = true;
                        terminate(process);
                    }
                }
                if (centerTruncated) {
                    // Keep observing the durable process so its final exit
                    // state is still reported.  The output guard terminates a
                    // process that keeps growing offline; this wait is tied to
                    // the process completion future, not a timer loop.
                    awaitProcessExit(process, 0);
                    return new RelayResult(true,
                            limitExceeded ? "durable command output exceeded " + outputLimit + " bytes" : null);
                }
                if (completion.isDone() && offset >= Math.min(channel.size(), outputLimit)) {
                    return new RelayResult(truncated,
                            limitExceeded ? "durable command output exceeded " + outputLimit + " bytes" : null);
                }
                if (completion.isDone()) {
                    // Completion is already observed and the final drain above
                    // found no remaining bytes. Returning here avoids a tight
                    // post-exit retry if the filesystem emitted no extra event.
                    return new RelayResult(truncated,
                            limitExceeded ? "durable command output exceeded " + outputLimit + " bytes" : null);
                }
                if (watcher == null) {
                    // A filesystem without WatchService support cannot stream
                    // incremental output safely.  Wait for process completion
                    // and perform one final drain instead of busy checking the
                    // file size.
                    awaitProcessExit(process, 0);
                } else {
                    try {
                        awaitOutputEvent(watcher, outputPath);
                    } catch (IOException watcherClosed) {
                        if (!exited.get() && process.isAlive()) throw watcherClosed;
                    }
                    if (exited.get()) continue;
                }
            }
        }
    }

    private static WatchService openWatcher(Path outputPath) {
        var parent = outputPath.toAbsolutePath().normalize().getParent();
        if (parent == null) return null;
        try {
            var watcher = FileSystems.getDefault().newWatchService();
            parent.register(watcher, StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
            return watcher;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void closeQuietly(WatchService watcher) {
        if (watcher == null) return;
        try {
            watcher.close();
        } catch (Exception ignored) {
            // Closing an already closed watcher is harmless.
        }
    }

    private static void awaitOutputEvent(WatchService watcher, Path outputPath)
            throws IOException, InterruptedException {
        var filename = outputPath.getFileName();
        while (true) {
            final WatchKey key;
            try {
                key = watcher.take();
            } catch (ClosedWatchServiceException closed) {
                throw new IOException("durable output watcher closed", closed);
            }
            var relevant = false;
            for (var event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    relevant = true;
                    continue;
                }
                var context = event.context();
                if (context instanceof Path path && path.getFileName().equals(filename)) relevant = true;
            }
            if (!key.reset()) throw new IOException("durable output watcher became invalid");
            if (relevant) return;
        }
    }

    private static void awaitProcessExit(ProcessHandle process, long timeoutMillis) throws InterruptedException {
        try {
            var completion = process.onExit();
            if (timeoutMillis <= 0) {
                completion.get();
            } else {
                completion.get(timeoutMillis, TimeUnit.MILLISECONDS);
            }
        } catch (TimeoutException | ExecutionException ignored) {
            // A bounded termination wait is best effort; the caller reports
            // an unfinished process as failed or detaches it for recovery.
        }
    }

    private DurableTaskStore.Record awaitCompletedRecord(DurableTaskStore.Record current)
            throws InterruptedException {
        if (current == null || current.completed()) return current;
        return store.awaitCompleted(current, COMPLETION_RECORD_GRACE);
    }

    private void sendState(TaskUpdateRequest update) throws IOException, InterruptedException {
        AgentRetry.call(LOG, "durable state upload " + task.id(), () -> {
            transport.updateState(identity.machineId(), identity.token(), task.id(), task.attempt(), update);
            return null;
        });
    }

    private void reportCanceled(DurableTaskStore.Record record) {
        if (record == null) return;
        try {
            store.markCompleted(record, -1, "command canceled");
            sendState(new TaskUpdateRequest("canceled", -1, "command canceled", null, Instant.now(), false));
            store.remove(record);
        } catch (Exception exception) {
            LOG.log(Level.WARNING, "could not report canceled durable task " + task.id(), exception);
        }
    }

    private static void terminate(Process process, DurableTaskStore.Record record) {
        if (process != null) {
            terminate(process.toHandle());
            return;
        }
        if (record != null) {
            ProcessHandle.of(record.pid()).ifPresent(DurableCommandRunner::terminate);
        }
    }

    private static void terminate(ProcessHandle process) {
        var tree = new ArrayList<ProcessHandle>();
        tree.add(process);
        try {
            tree.addAll(process.descendants().toList());
        } catch (RuntimeException ignored) {
            // The process may disappear between discovery and termination.
        }
        // Terminate children first, then the shell/adapter parent.  Waiting
        // for the whole tree is important on Windows: an inherited handle can
        // keep the durable log locked even after the top-level cmd.exe exits.
        for (var index = tree.size() - 1; index >= 0; index--) {
            tree.get(index).destroy();
        }
        awaitExit(tree, 2000);
        for (var index = tree.size() - 1; index >= 0; index--) {
            var child = tree.get(index);
            if (child.isAlive()) child.destroyForcibly();
        }
        awaitExit(tree, 2000);
    }

    private static void awaitExit(List<ProcessHandle> processes, long timeoutMillis) {
        var completions = processes.stream().map(ProcessHandle::onExit).toArray(CompletableFuture[]::new);
        if (completions.length == 0) return;
        try {
            CompletableFuture.allOf(completions).get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (TimeoutException | ExecutionException ignored) {
            // A bounded termination wait is best effort; forcible termination
            // follows immediately for any process that remains alive.
        }
    }

    private record RelayResult(boolean truncated, String error) {
    }

    private static String compactError(String value) {
        var error = value == null || value.isBlank() ? "durable command failed" : SensitiveValueRedactor.redact(value.trim());
        return error.length() <= 4096 ? error : error.substring(0, 4096);
    }
}
