package com.prodigalgal.remoteconnectmcp.agent;

import com.prodigalgal.remoteconnectmcp.protocol.TaskCommand;
import com.prodigalgal.remoteconnectmcp.protocol.TaskUpdateRequest;
import com.prodigalgal.remoteconnectmcp.protocol.SensitiveValueRedactor;
import com.prodigalgal.remoteconnectmcp.protocol.JsonCodec;
import java.io.IOException;
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Browser capability bridge. Playwright/Patchright/Comoufox stay in a local
 * adapter process; the Center sees only bounded task output and never a
 * browser profile or debugging credential.
 */
final class BrowserTaskRunner implements Runnable {
    private static final Logger LOG = Logger.getLogger(BrowserTaskRunner.class.getName());
    private static final int CHUNK_SIZE = 16 * 1024;
    private static final ConcurrentHashMap<Path, Semaphore> PROFILE_LOCKS = new ConcurrentHashMap<>();

    private final AgentConfig config;
    private final AgentIdentity identity;
    private final TaskCommand task;
    private final AgentTransport transport;
    private final AgentResourceBudget resourceBudget;
    private final AgentProcessBudget processBudget;

    BrowserTaskRunner(AgentConfig config, AgentIdentity identity, TaskCommand task, AgentTransport transport) {
        this(config, identity, task, transport, new AgentResourceBudget(config.maxAggregateOutputBytes()),
                new AgentProcessBudget(config.maxTotalChildProcesses()));
    }

    BrowserTaskRunner(AgentConfig config, AgentIdentity identity, TaskCommand task,
                      AgentTransport transport, AgentResourceBudget resourceBudget) {
        this(config, identity, task, transport, resourceBudget,
                new AgentProcessBudget(config.maxTotalChildProcesses()));
    }

    BrowserTaskRunner(AgentConfig config, AgentIdentity identity, TaskCommand task,
                      AgentTransport transport, AgentResourceBudget resourceBudget,
                      AgentProcessBudget processBudget) {
        this.config = config;
        this.identity = identity;
        this.task = task;
        this.transport = transport;
        this.resourceBudget = resourceBudget;
        this.processBudget = processBudget;
    }

    @Override
    public void run() {
        Process process = null;
        Path requestFile = null;
        Path resultFile = null;
        Path artifactDir = null;
        ExecutorService outputExecutor = null;
        Future<?> outputDrainFuture = null;
        Future<?> outputUploadFuture = null;
        TaskOutputSpool outputSpool = null;
        ProcessResourceSupervisor resourceSupervisor = null;
        var outputFailure = new AtomicReference<Throwable>();
        var outputCursor = new AtomicLong();
        Semaphore profileLock = null;
        boolean profileAcquired = false;
        try {
            if (!config.capabilities().contains("browser")) {
                throw new IOException("browser capability is not enabled for this Agent");
            }
            var adapter = config.browserAdapter();
            if (adapter == null || adapter.isBlank()) {
                throw new IOException("browser adapter is not configured; set REMOTE_CONNECT_MCP_AGENT_BROWSER_ADAPTER");
            }
            var cwd = AgentPaths.resolveCwd(config, identity.machineId(), task, task.cwd());
            var configuredProfile = config.browserProfileDir();
            if (!configuredProfile.isBlank()) {
                profileLock = PROFILE_LOCKS.computeIfAbsent(Path.of(configuredProfile).toAbsolutePath().normalize(),
                        ignored -> new Semaphore(1));
                profileAcquired = profileLock.tryAcquire(Math.min(TaskLimits.timeoutSeconds(task, 30), 30), TimeUnit.SECONDS);
                if (!profileAcquired) throw new IOException("browser profile is busy; retry after the active session completes");
            }
            Files.createDirectories(config.stateDir());
            requestFile = Files.createTempFile(config.stateDir(), "browser-request-", ".json");
            Files.write(requestFile, JsonCodec.write(task));
            resultFile = Files.createTempFile(config.stateDir(), "browser-result-", ".json");
            Files.deleteIfExists(resultFile);
            artifactDir = Files.createTempDirectory(config.stateDir(), "browser-artifacts-");
            var browserBinary = resolveBrowserAgent();
            var browserCommand = browserBinary == null
                    ? shell(adapter)
                    : List.of(browserBinary.toString(), "--run-worker");
            var builder = new ProcessBuilder(browserCommand).directory(cwd.toFile()).redirectErrorStream(true);
            cleanSensitiveEnvironment(builder.environment());
            // The standalone browser-agent consumes this local adapter command
            // and never receives the Center identity/token.
            builder.environment().put("REMOTE_CONNECT_MCP_AGENT_BROWSER_ADAPTER", adapter);
            builder.environment().put("RCM_BROWSER_TASK_ID", task.id());
            builder.environment().put("RCM_BROWSER_TASK_COMMAND", task.command() == null ? "" : task.command());
            builder.environment().put("RCM_BROWSER_TASK_REQUEST_FILE", requestFile.toString());
            builder.environment().put("RCM_BROWSER_RESULT_FILE", resultFile.toString());
            builder.environment().put("RCM_BROWSER_ARTIFACT_DIR", artifactDir.toString());
            // Keep only a small, per-Agent browser session marker outside the
            // short-lived artifact directory.  The Worker stores a sanitized
            // origin/path here so a new task can restore the last page when a
            // persistent profile is configured, without ever persisting query
            // strings, fragments, cookies, or CDP credentials.
            builder.environment().put("RCM_BROWSER_SESSION_FILE",
                    config.stateDir().toAbsolutePath().normalize().resolve("browser-session.json").toString());
            if (!configuredProfile.isBlank()) builder.environment().put("RCM_BROWSER_PROFILE_DIR", configuredProfile);
            builder.environment().put("RCM_BROWSER_ENGINE", config.browserEngine());
            builder.environment().put("RCM_BROWSER_BROWSER", config.browserName());
            builder.environment().put("RCM_BROWSER_HEADLESS", config.browserHeadless() ? "1" : "0");
            var playwrightBrowsersPath = System.getenv("REMOTE_CONNECT_MCP_AGENT_PLAYWRIGHT_BROWSERS_PATH");
            if (playwrightBrowsersPath != null && !playwrightBrowsersPath.isBlank()) {
                builder.environment().put("PLAYWRIGHT_BROWSERS_PATH", playwrightBrowsersPath);
            }
            var timeout = Math.min(TaskLimits.timeoutSeconds(task, 300), 24 * 60 * 60);
            builder.environment().put("RCM_BROWSER_TASK_TIMEOUT_SECONDS", Integer.toString(timeout));
            outputSpool = new TaskOutputSpool(config.stateDir(), task.id(), TaskLimits.outputBytes(config, task), resourceBudget);
            process = builder.start();
            var processForSupervisor = process;
            resourceSupervisor = ProcessResourceSupervisor.start(processForSupervisor, config, task,
                    () -> terminate(processForSupervisor), processBudget);
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
                    outputCursor.set(TaskOutputPump.upload(LOG, "browser output upload " + task.id(), spool,
                            (offset, data) -> transport.appendOutput(identity.machineId(), identity.token(), task.id(), task.attempt(), offset, data)));
                } catch (Throwable failure) {
                    outputFailure.compareAndSet(null, failure);
                    terminate(startedProcess);
                }
            });
            sendState(new TaskUpdateRequest("running", null, null, Instant.now(), null, false));
            if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                terminate(process);
                spool.complete();
                cancel(outputDrainFuture);
                cancel(outputUploadFuture);
                awaitOutputs(outputDrainFuture, outputUploadFuture);
                sendState(new TaskUpdateRequest("failed", null, "browser task timed out", null, Instant.now(), spool.truncated()));
                return;
            }
            awaitOutputs(outputDrainFuture, outputUploadFuture);
            var resourceViolation = resourceSupervisor == null ? null : resourceSupervisor.violation();
            if (resourceViolation != null && !resourceViolation.isBlank()) {
                sendState(new TaskUpdateRequest("failed", process.exitValue(), resourceViolation, null, Instant.now(), spool.truncated()));
                return;
            }
            // Resource enforcement terminates the adapter process tree. That
            // forced close can race with the output pumps and look like a
            // generic stream failure; preserve the actionable limit reason.
            if (outputFailure.get() != null) throw asIOException(outputFailure.get());
            publishResult(resultFile, artifactDir, outputCursor.get());
            var exitCode = process.exitValue();
            sendState(new TaskUpdateRequest(exitCode == 0 ? "completed" : "failed", exitCode,
                    exitCode == 0 ? null : "browser adapter exited with code " + exitCode, null, Instant.now(), spool.truncated()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (process != null) terminate(process);
            if (outputSpool != null) outputSpool.complete();
            cancel(outputDrainFuture);
            cancel(outputUploadFuture);
            try {
                sendState(new TaskUpdateRequest("canceled", null, "browser task canceled", null, Instant.now(), false));
            } catch (Exception sendFailure) {
                LOG.log(Level.WARNING, "could not report canceled browser task " + task.id(), sendFailure);
            }
        } catch (Exception exception) {
            if (process != null && process.isAlive()) terminate(process);
            if (outputSpool != null) outputSpool.complete();
            cancel(outputDrainFuture);
            cancel(outputUploadFuture);
            try {
                sendState(new TaskUpdateRequest("failed", null, compactError(exception.getMessage()), null, Instant.now(), false));
            } catch (Exception sendFailure) {
                LOG.log(Level.WARNING, "could not report failed browser task " + task.id(), sendFailure);
            }
        } finally {
            if (resourceSupervisor != null) resourceSupervisor.close();
            if (requestFile != null) {
                try { Files.deleteIfExists(requestFile); } catch (IOException ignored) { }
            }
            if (resultFile != null) {
                try { Files.deleteIfExists(resultFile); } catch (IOException ignored) { }
            }
            deleteTree(artifactDir);
            if (outputSpool != null) outputSpool.close();
            if (outputExecutor != null) outputExecutor.shutdownNow();
            if (profileAcquired && profileLock != null) profileLock.release();
        }
    }

    private static void drainOutput(Process process, TaskOutputSpool spool) throws IOException, InterruptedException {
        var buffer = new byte[CHUNK_SIZE];
        try (var input = process.getInputStream()) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                spool.append(buffer, 0, read);
            }
        }
    }

    private void sendState(TaskUpdateRequest state) throws IOException, InterruptedException {
        AgentRetry.call(LOG, "browser state upload " + task.id(), () -> {
            transport.updateState(identity.machineId(), identity.token(), task.id(), task.attempt(), state);
            return null;
        });
    }

    private void sendOutput(String text, long offset) throws IOException, InterruptedException {
        var data = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        AgentRetry.call(LOG, "browser output upload " + task.id(), () -> {
            transport.appendOutput(identity.machineId(), identity.token(), task.id(), task.attempt(), offset, data);
            return null;
        });
    }

    /**
     * Read the optional adapter result manifest after the process exits.  A
     * worker can keep screenshots/downloads out of stdout by writing one
     * bounded artifact file under RCM_BROWSER_ARTIFACT_DIR and atomically
     * publishing a small JSON manifest to RCM_BROWSER_RESULT_FILE:
     * {"status":"completed","output":"...","artifact":{"path":"...","mime_type":"image/png"}}.
     */
    @SuppressWarnings("unchecked")
    private void publishResult(Path resultFile, Path artifactDir, long outputCursor) throws IOException, InterruptedException {
        if (resultFile == null || !Files.isRegularFile(resultFile)) return;
        var bytes = Files.readAllBytes(resultFile);
        if (bytes.length > 64 * 1024) throw new IOException("browser result manifest exceeds 64 KiB");
        var raw = JsonCodec.read(bytes, Map.class);
        var status = text(raw.get("status"));
        var failed = !status.isBlank() && !status.equalsIgnoreCase("completed") && !status.equalsIgnoreCase("success");
        // The reference worker already redacts its manifest, but adapters are
        // user-supplied and may return diagnostic text directly.  Apply the
        // same boundary redaction here before bytes enter the durable Center
        // output stream; this keeps a custom Playwright/Patchright/Comoufox
        // adapter from bypassing the Agent's error/log hygiene.
        var output = SensitiveValueRedactor.redact(text(raw.get("output")));
        if (!output.isBlank()) {
            var outputData = (output + System.lineSeparator()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (outputData.length > 64 * 1024) {
                throw new IOException("browser result output exceeds 64 KiB");
            }
            sendOutput(output + System.lineSeparator(), outputCursor);
            outputCursor += outputData.length;
        }
        if (failed) {
            throw new IOException(compactError(text(raw.get("error"))));
        }
        var artifact = raw.get("artifact");
        if (!(artifact instanceof Map<?, ?> values)) return;
        var pathText = text(values.get("path"));
        var mimeType = text(values.get("mime_type"));
        if (pathText.isBlank() || mimeType.isBlank()) throw new IOException("browser artifact path and mime_type are required");
        if (mimeType.length() > 256 || mimeType.indexOf('\r') >= 0 || mimeType.indexOf('\n') >= 0) {
            throw new IOException("browser artifact mime_type is invalid");
        }
        var root = artifactDir.toAbsolutePath().normalize().toRealPath();
        var candidate = Path.of(pathText);
        if (!candidate.isAbsolute()) candidate = root.resolve(candidate);
        candidate = candidate.toAbsolutePath().normalize().toRealPath();
        if (!candidate.startsWith(root) || !Files.isRegularFile(candidate)) {
            throw new IOException("browser artifact is outside the adapter artifact directory");
        }
        var data = Files.readAllBytes(candidate);
        var maxArtifactBytes = TaskLimits.artifactBytes(task, 8L * 1024 * 1024);
        if (data.length == 0 || data.length > maxArtifactBytes) {
            throw new IOException("browser artifact is empty or exceeds " + maxArtifactBytes + " bytes");
        }
        final String sha256;
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            sha256 = java.util.HexFormat.of().formatHex(digest.digest(data));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
        AgentRetry.call(LOG, "browser artifact upload " + task.id(), () -> {
            transport.appendArtifact(identity.machineId(), identity.token(), task.id(), task.attempt(), mimeType, sha256, data);
            return null;
        });
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static void deleteTree(Path root) {
        if (root == null) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) {
        }
    }

    private static List<String> shell(String command) {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return List.of("cmd.exe", "/d", "/s", "/c", command);
        }
        return List.of("/bin/sh", "-lc", command);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static Path resolveBrowserAgent() throws IOException {
        var current = ProcessHandle.current().info().command()
                .map(value -> Path.of(value).toAbsolutePath().normalize()).orElse(null);
        var configured = System.getenv("REMOTE_CONNECT_MCP_AGENT_BROWSER_BINARY");
        if (configured != null && !configured.isBlank()) {
            var path = Path.of(configured.trim()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path) || (!isWindows() && !Files.isExecutable(path))) {
                throw new IOException("browser-agent binary is missing or not executable: " + path);
            }
            if (current != null && path.equals(current)) {
                throw new IOException("REMOTE_CONNECT_MCP_AGENT_BROWSER_BINARY points to the command Agent itself");
            }
            return path;
        }
        if (current == null || current.getParent() == null) return null;
        var executable = current.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".exe")
                ? "rcm-browser-agent.exe" : "rcm-browser-agent";
        var sibling = current.resolveSibling(executable);
        if (Files.isRegularFile(sibling)) return sibling;
        var isolated = current.resolveSibling("browser").resolve(executable).normalize();
        return Files.isRegularFile(isolated) ? isolated : null;
    }

    private static void awaitOutputs(Future<?>... futures) throws IOException, InterruptedException {
        for (var future : futures) {
            if (future == null) continue;
            try {
                future.get(15, TimeUnit.SECONDS);
            } catch (TimeoutException exception) {
                future.cancel(true);
                throw new IOException("browser output streaming did not finish", exception);
            } catch (ExecutionException exception) {
                throw asIOException(exception.getCause() == null ? exception : exception.getCause());
            } catch (java.util.concurrent.CancellationException ignored) {
                // Timeout/shutdown cancels an output worker.
            }
        }
    }

    private static IOException asIOException(Throwable failure) {
        return failure instanceof IOException io ? io : new IOException(failure == null ? "browser output failed" : failure.getMessage(), failure);
    }

    private static void cancel(Future<?> future) {
        if (future != null && !future.isDone()) future.cancel(true);
    }

    private static void terminate(Process process) {
        try {
            process.descendants().forEach(child -> {
                child.destroy();
                if (child.isAlive()) child.destroyForcibly();
            });
        } catch (RuntimeException ignored) {
        }
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS) && process.isAlive()) process.destroyForcibly();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static void cleanSensitiveEnvironment(java.util.Map<String, String> environment) {
        environment.keySet().removeIf(CommandRunner::isSensitive);
    }

    private static String compactError(String value) {
        var error = value == null || value.isBlank() ? "browser task failed" : SensitiveValueRedactor.redact(value.trim());
        return error.length() <= 4096 ? error : error.substring(0, 4096);
    }
}
