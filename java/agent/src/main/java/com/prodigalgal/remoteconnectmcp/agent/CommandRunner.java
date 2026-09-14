package com.prodigalgal.remoteconnectmcp.agent;

import com.prodigalgal.remoteconnectmcp.protocol.TaskCommand;
import com.prodigalgal.remoteconnectmcp.protocol.TaskUpdateRequest;
import com.prodigalgal.remoteconnectmcp.protocol.SensitiveValueRedactor;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Executes one command task and streams bounded output to Center. */
final class CommandRunner implements Runnable {
    private static final Logger LOG = Logger.getLogger(CommandRunner.class.getName());
    private static final int CHUNK_SIZE = 16 * 1024;

    private final AgentConfig config;
    private final AgentIdentity identity;
    private final TaskCommand task;
    private final AgentTransport transport;
    private final AgentResourceBudget resourceBudget;

    CommandRunner(AgentConfig config, AgentIdentity identity, TaskCommand task, AgentTransport transport) {
        this(config, identity, task, transport, new AgentResourceBudget(config.maxAggregateOutputBytes()));
    }

    CommandRunner(AgentConfig config, AgentIdentity identity, TaskCommand task,
                  AgentTransport transport, AgentResourceBudget resourceBudget) {
        this.config = config;
        this.identity = identity;
        this.task = task;
        this.transport = transport;
        this.resourceBudget = resourceBudget;
    }

    @Override
    public void run() {
        Process process = null;
        ExecutorService outputExecutor = null;
        Future<?> outputDrainFuture = null;
        Future<?> outputUploadFuture = null;
        TaskOutputSpool outputSpool = null;
        ProcessResourceSupervisor resourceSupervisor = null;
        var outputFailure = new AtomicReference<Throwable>();
        try {
            var cwd = resolveCwd(task.cwd());
            var command = shellCommand(task.command());
            var builder = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true);
            cleanEnvironment(builder.environment());
            if (task.env() != null) {
                task.env().forEach((key, value) -> {
                    if (key != null && !key.isBlank() && value != null && !isSensitive(key)) {
                        builder.environment().put(key, value);
                    }
                });
            }
            outputSpool = new TaskOutputSpool(config.stateDir(), task.id(), TaskLimits.outputBytes(config, task), resourceBudget);
            process = builder.start();
            var processForSupervisor = process;
            resourceSupervisor = ProcessResourceSupervisor.start(processForSupervisor, config, task,
                    () -> terminate(processForSupervisor));

            // Reading the child and uploading to Center are separate workers.
            // A transient network outage therefore cannot fill the child pipe
            // or terminate a long-running process.
            outputExecutor = Executors.newVirtualThreadPerTaskExecutor();
            var startedProcess = process;
            var spool = outputSpool;
            outputDrainFuture = outputExecutor.submit(() -> {
                try {
                    drainOutput(startedProcess, spool);
                } catch (Throwable failure) {
                    outputFailure.compareAndSet(null, failure);
                    terminate(startedProcess);
                } finally {
                    spool.complete();
                }
            });
            outputUploadFuture = outputExecutor.submit(() -> {
                try {
                    TaskOutputPump.upload(LOG, "task output upload " + task.id(), spool,
                            (offset, data) -> transport.appendOutput(identity.machineId(), identity.token(), task.id(), task.attempt(), offset, data));
                } catch (Throwable failure) {
                    outputFailure.compareAndSet(null, failure);
                    terminate(startedProcess);
                }
            });
            // Start draining before reporting RUNNING. If Center is temporarily
            // unavailable, the process cannot fill its stdout pipe while the
            // state update is retried on this virtual thread.
            sendState(new TaskUpdateRequest("running", null, null, Instant.now(), null, false));

            int exitCode;
            var timeoutSeconds = TaskLimits.timeoutSeconds(task, 0);
            if (timeoutSeconds <= 0) {
                exitCode = process.waitFor();
            } else if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                terminate(process);
                spool.complete();
                cancelOutput(outputDrainFuture);
                cancelOutput(outputUploadFuture);
                awaitOutputs(outputDrainFuture, outputUploadFuture);
                sendState(new TaskUpdateRequest("failed", null, "command timed out", null, Instant.now(), spool.truncated()));
                return;
            } else {
                exitCode = process.exitValue();
            }
            awaitOutputs(outputDrainFuture, outputUploadFuture);
            var streamFailure = outputFailure.get();
            if (streamFailure != null) {
                throw asIOException(streamFailure);
            }
            var resourceViolation = resourceSupervisor == null ? null : resourceSupervisor.violation();
            if (resourceViolation != null && !resourceViolation.isBlank()) {
                sendState(new TaskUpdateRequest("failed", exitCode, resourceViolation, null, Instant.now(), spool.truncated()));
                return;
            }
            sendState(new TaskUpdateRequest(exitCode == 0 ? "completed" : "failed", exitCode,
                    exitCode == 0 ? null : "command exited with code " + exitCode, null, Instant.now(), spool.truncated()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (process != null) {
                terminate(process);
            }
            if (outputSpool != null) outputSpool.complete();
            cancelOutput(outputDrainFuture);
            cancelOutput(outputUploadFuture);
            try {
                sendState(new TaskUpdateRequest("canceled", null, "command canceled", null, Instant.now(), false));
            } catch (Exception sendFailure) {
                LOG.log(Level.WARNING, "could not report canceled task " + task.id(), sendFailure);
            }
        } catch (Exception exception) {
            if (process != null && process.isAlive()) {
                terminate(process);
            }
            if (outputSpool != null) outputSpool.complete();
            cancelOutput(outputDrainFuture);
            cancelOutput(outputUploadFuture);
            try {
                sendState(new TaskUpdateRequest("failed", null, compactError(exception.getMessage()), null, Instant.now(), false));
            } catch (Exception sendFailure) {
                LOG.log(Level.WARNING, "could not report failed task " + task.id(), sendFailure);
            }
        } finally {
            if (resourceSupervisor != null) resourceSupervisor.close();
            if (outputSpool != null) outputSpool.close();
            if (outputExecutor != null) {
                outputExecutor.shutdownNow();
            }
        }
    }

    private static void awaitOutputs(Future<?>... futures) throws IOException, InterruptedException {
        for (var future : futures) {
            if (future == null) continue;
            try {
                future.get(15, TimeUnit.SECONDS);
            } catch (TimeoutException exception) {
                future.cancel(true);
                throw new IOException("output streaming did not finish", exception);
            } catch (ExecutionException exception) {
                var cause = exception.getCause();
                throw asIOException(cause == null ? exception : cause);
            } catch (java.util.concurrent.CancellationException ignored) {
                // Timeout/shutdown deliberately cancels an output worker.
            }
        }
    }

    private static IOException asIOException(Throwable failure) {
        if (failure instanceof IOException exception) {
            return exception;
        }
        return new IOException(failure == null ? "output streaming failed" : failure.getMessage(), failure);
    }

    private static void cancelOutput(Future<?> outputFuture) {
        if (outputFuture != null && !outputFuture.isDone()) {
            outputFuture.cancel(true);
        }
    }

    private static void terminate(Process process) {
        try {
            process.descendants().forEach(child -> {
                child.destroy();
                if (child.isAlive()) {
                    child.destroyForcibly();
                }
            });
        } catch (RuntimeException ignored) {
            // A process may disappear between descendants() and destroy().
        }
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS) && process.isAlive()) {
                process.destroyForcibly();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static void drainOutput(Process process, TaskOutputSpool spool) throws IOException, InterruptedException {
        var buffer = new byte[CHUNK_SIZE];
        try (var input = process.getInputStream()) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                spool.append(buffer, 0, read);
            }
        }
    }

    private void sendState(TaskUpdateRequest update) throws IOException, InterruptedException {
        AgentRetry.call(LOG, "task state upload " + task.id(), () -> {
            transport.updateState(identity.machineId(), identity.token(), task.id(), task.attempt(), update);
            return null;
        });
    }

    private Path resolveCwd(String requested) throws IOException {
        return AgentPaths.resolveCwd(config, identity.machineId(), task, requested);
    }

    private static List<String> shellCommand(String command) {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return List.of("cmd.exe", "/d", "/s", "/c", command == null ? "" : command);
        }
        return List.of("/bin/sh", "-lc", command == null ? "" : command);
    }

    static void cleanEnvironment(java.util.Map<String, String> environment) {
        environment.keySet().removeIf(CommandRunner::isSensitive);
    }

    static boolean isSensitive(String key) {
        var upper = key.toUpperCase(java.util.Locale.ROOT);
        return upper.contains("TOKEN") || upper.contains("PASSWORD") || upper.contains("PASSWD")
                || upper.contains("SECRET") || upper.contains("COOKIE") || upper.contains("AUTHORIZATION")
                || upper.contains("API_KEY") || upper.contains("PRIVATE_KEY") || upper.contains("CREDENTIAL");
    }

    private static String compactError(String value) {
        var error = value == null || value.isBlank() ? "command execution failed" : SensitiveValueRedactor.redact(value.trim());
        return error.length() <= 4096 ? error : error.substring(0, 4096);
    }
}
