package com.prodigalgal.remoteconnectmcp.agent;

import com.prodigalgal.remoteconnectmcp.protocol.OutputResponse;
import com.prodigalgal.remoteconnectmcp.protocol.TaskCommand;
import com.prodigalgal.remoteconnectmcp.protocol.TaskUpdateRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
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

    private final AgentConfig config;
    private final AgentIdentity identity;
    private final TaskCommand task;
    private final AgentTransport transport;
    private final DurableTaskStore store;
    private final DurableTaskStore.Record recovered;
    private volatile boolean cancelRequested;

    DurableCommandRunner(AgentConfig config, AgentIdentity identity, TaskCommand task,
                         AgentTransport transport, DurableTaskStore store) {
        this(config, identity, task, transport, store, null);
    }

    DurableCommandRunner(AgentConfig config, AgentIdentity identity, TaskCommand task,
                         AgentTransport transport, DurableTaskStore store, DurableTaskStore.Record recovered) {
        this.config = config;
        this.identity = identity;
        this.task = task;
        this.transport = transport;
        this.store = store;
        this.recovered = recovered;
    }

    void requestCancel() {
        cancelRequested = true;
    }

    @Override
    public void run() {
        DurableTaskStore.Record record = recovered;
        Process process = null;
        try {
            if (record == null) {
                var cwd = AgentPaths.resolveCwd(config, task.cwd());
                var started = store.start(task, cwd, config.maxOutputBytes());
                process = started.process();
                record = started.record();
            }
            var handle = process == null ? ProcessHandle.of(record.pid()).orElse(null) : process.toHandle();
            if (handle == null) {
                throw new IOException("durable process is no longer available");
            }
            store.guard(record, config.maxOutputBytes());

            if (!record.completed()) {
                sendState(new TaskUpdateRequest("running", null, null, Instant.now(), null, false));
            }
            var relay = relayOutput(Path.of(record.outputPath()), handle);
            // The original Agent may have been interrupted while its watcher
            // still had the child completion callback in flight. Refresh the
            // record before treating a recovered process as an offline failure.
            record = store.find(task.id()).orElse(record);
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
            var finalError = relay.error() == null || relay.error().isBlank() ? error : relay.error();
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
        }
    }

    private RelayResult relayOutput(Path outputPath, ProcessHandle process) throws IOException, InterruptedException {
        if (!java.nio.file.Files.isRegularFile(outputPath)) {
            throw new IOException("durable output file is missing");
        }
        var truncated = false;
        var limitExceeded = false;
        var centerTruncated = false;
        long offset = 0;
        try (var channel = FileChannel.open(outputPath, StandardOpenOption.READ)) {
            while (true) {
                var fileSize = channel.size();
                var boundedSize = Math.min(fileSize, config.maxOutputBytes());
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
                            () -> transport.appendOutput(identity.machineId(), identity.token(), task.id(), uploadOffset, data));
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
                    boundedSize = Math.min(fileSize, config.maxOutputBytes());
                }
                if (fileSize > config.maxOutputBytes()) {
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
                    // state is still reported. The local guard remains active
                    // and will terminate a process that keeps growing offline.
                    while (process.isAlive()) Thread.sleep(250);
                    return new RelayResult(true,
                            limitExceeded ? "durable command output exceeded " + config.maxOutputBytes() + " bytes" : null);
                }
                if (!process.isAlive() && offset >= Math.min(channel.size(), config.maxOutputBytes())) {
                    return new RelayResult(truncated,
                            limitExceeded ? "durable command output exceeded " + config.maxOutputBytes() + " bytes" : null);
                }
                Thread.sleep(250);
            }
        }
    }

    private void sendState(TaskUpdateRequest update) throws IOException, InterruptedException {
        AgentRetry.call(LOG, "durable state upload " + task.id(), () -> {
            transport.updateState(identity.machineId(), identity.token(), task.id(), update);
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
        var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        var interrupted = false;
        while (processes.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException exception) {
                interrupted = true;
                break;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private record RelayResult(boolean truncated, String error) {
    }

    private static String compactError(String value) {
        var error = value == null || value.isBlank() ? "durable command failed" : value.trim();
        return error.length() <= 4096 ? error : error.substring(0, 4096);
    }
}
