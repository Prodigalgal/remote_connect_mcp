package com.prodigalgal.remoteconnectmcp.agent;

import com.prodigalgal.remoteconnectmcp.protocol.TaskCommand;
import com.prodigalgal.remoteconnectmcp.protocol.TaskUpdateRequest;
import com.prodigalgal.remoteconnectmcp.protocol.DesktopCompanionProtocol;
import com.prodigalgal.remoteconnectmcp.protocol.SensitiveValueRedactor;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/** User-session desktop capability with bounded GUI, clipboard and screen actions. */
final class DesktopTaskRunner implements Runnable {
    private static final Logger LOG = Logger.getLogger(DesktopTaskRunner.class.getName());
    private static final int MAX_EXECUTABLE_CHARS = 4096;
    private static final int MAX_ARGS = 128;
    private static final int MAX_ARG_CHARS = 4096;
    private static final int MAX_SCREENSHOT_BYTES = 8 * 1024 * 1024;

    private final AgentConfig config;
    private final AgentIdentity identity;
    private final TaskCommand task;
    private final AgentTransport transport;

    DesktopTaskRunner(AgentConfig config, AgentIdentity identity, TaskCommand task, AgentTransport transport) {
        this.config = config;
        this.identity = identity;
        this.task = task;
        this.transport = transport;
    }

    @Override
    public void run() {
        try {
            if (!config.desktopEnabled() || !config.capabilities().contains("desktop")) {
                throw new IOException("desktop capability is not enabled for this Agent");
            }
            var action = task.desktop();
            validate(action);
            sendState(new TaskUpdateRequest("running", null, null, Instant.now(), null, false));
            // Validate the contract before entering the user-session IPC.
            // The companion is intentionally a small loopback process and
            // must not become a second scope authority.  In particular,
            // launch requests cannot smuggle an arbitrary cwd through the
            // companion JSON after the command Agent has accepted the task.
            var resolvedCwd = resolveCwd(action.cwd());
            var scopedAction = withCwd(action, resolvedCwd.toString());
            var companion = DesktopCompanionClient.discover(config.stateDir());
            if (companion == null) throw new IOException("desktop user-session companion is not available");
            completeCompanion(companion.call(scopedAction, task.contract(),
                    Duration.ofSeconds(Math.min(TaskLimits.timeoutSeconds(task, 30), 300))));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            try {
                sendState(new TaskUpdateRequest("canceled", null, "desktop task canceled", null, Instant.now(), false));
            } catch (Exception sendFailure) {
                LOG.log(Level.WARNING, "could not report canceled desktop task " + task.id(), sendFailure);
            }
        } catch (Exception exception) {
            try {
                sendState(new TaskUpdateRequest("failed", null, compactError(exception.getMessage()), null, Instant.now(), false));
            } catch (Exception sendFailure) {
                LOG.log(Level.WARNING, "could not report failed desktop task " + task.id(), sendFailure);
            }
        }
    }

    private void completeCompanion(DesktopCompanionProtocol.Response response) throws IOException, InterruptedException {
        if (response == null || !response.ok()) {
            throw new IOException(compactError(response == null ? "desktop companion returned no response" : response.error()));
        }
        // Non-visual desktop operations (screens/windows/clipboard/input)
        // legitimately return only text or JSON.  Normalize a missing data
        // field to an empty artifact instead of dereferencing null and
        // reporting a successful operation as a generic task failure.
        var data = response.data() == null ? new byte[0] : response.data();
        var maxArtifactBytes = TaskLimits.artifactBytes(task, MAX_SCREENSHOT_BYTES);
        if (data.length > maxArtifactBytes) throw new IOException("desktop companion artifact exceeds " + maxArtifactBytes + " bytes");
        if (data.length > 0) {
            if (!"image/png".equalsIgnoreCase(response.mimeType()) || data.length < 8
                    || data[0] != (byte) 0x89 || data[1] != 0x50 || data[2] != 0x4e || data[3] != 0x47) {
                throw new IOException("desktop companion returned an invalid PNG artifact");
            }
            var digest = sha256(data);
            AgentRetry.call(LOG, "desktop companion artifact upload " + task.id(), () -> {
                transport.appendArtifact(identity.machineId(), identity.token(), task.id(), task.attempt(), "image/png", digest, data);
                return null;
            });
        }
        if (response.output() != null && !response.output().isBlank()) {
            sendOutput(response.output() + System.lineSeparator());
        }
        sendState(new TaskUpdateRequest("completed", 0, null, null, Instant.now(), false));
    }

    private Path resolveCwd(String requested) throws IOException {
        return AgentPaths.resolveCwd(config, identity.machineId(), task, requested);
    }

    private static TaskCommand.DesktopAction withCwd(TaskCommand.DesktopAction action, String cwd) {
        return new TaskCommand.DesktopAction(action.operation(), action.executable(), action.args(), cwd,
                action.text(), action.x(), action.y(), action.key(), action.x2(), action.y2(),
                action.durationMs(), action.screen(), action.windowTitle());
    }

    private void sendOutput(String text) throws IOException, InterruptedException {
        var data = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        AgentRetry.call(LOG, "desktop output upload " + task.id(), () -> {
            transport.appendOutput(identity.machineId(), identity.token(), task.id(), task.attempt(), 0, data);
            return null;
        });
    }

    private void sendState(TaskUpdateRequest state) throws IOException, InterruptedException {
        AgentRetry.call(LOG, "desktop state upload " + task.id(), () -> {
            transport.updateState(identity.machineId(), identity.token(), task.id(), task.attempt(), state);
            return null;
        });
    }

    private static void validate(TaskCommand.DesktopAction action) {
        if (action == null || action.operation() == null || action.operation().isBlank()) throw new IllegalArgumentException("desktop action is required");
        var operation = action.operation().trim().toLowerCase(Locale.ROOT);
            if (!operation.equals("launch") && !operation.equals("screenshot") && !operation.equals("screens") && !operation.equals("windows")
                && !operation.equals("click") && !operation.equals("double_click")
                && !operation.equals("right_click") && !operation.equals("move")
                && !operation.equals("screenshot_region") && !operation.equals("drag") && !operation.equals("key")
                && !operation.equals("type") && !operation.equals("clipboard_read")
                && !operation.equals("clipboard_write") && !operation.equals("focus")) {
            throw new IllegalArgumentException("unsupported desktop operation: " + operation);
        }
        if (action.executable() != null && action.executable().length() > MAX_EXECUTABLE_CHARS) throw new IllegalArgumentException("desktop executable is too long");
        if (action.args().size() > MAX_ARGS) throw new IllegalArgumentException("desktop args exceed " + MAX_ARGS);
        action.args().forEach(value -> {
            if (value == null || value.indexOf('\u0000') >= 0 || value.length() > MAX_ARG_CHARS) throw new IllegalArgumentException("desktop argument is invalid");
        });
        if (operation.equals("launch") && (action.executable() == null || action.executable().isBlank())) throw new IllegalArgumentException("launch executable is required");
        if ((operation.equals("screenshot") || operation.equals("screenshot_region") || operation.equals("screens") || operation.equals("windows") || operation.equals("clipboard_read")
                || operation.equals("clipboard_write") || operation.equals("drag") || operation.equals("click")
                || operation.equals("double_click") || operation.equals("right_click") || operation.equals("move")
                || operation.equals("key") || operation.equals("type") || operation.equals("focus"))
                && (action.executable() != null || !action.args().isEmpty())) {
            throw new IllegalArgumentException("executable and args are allowed only for launch");
        }
        if ((operation.equals("click") || operation.equals("double_click") || operation.equals("right_click") || operation.equals("move"))
                && (action.x() == null || action.y() == null)) throw new IllegalArgumentException(operation + " requires x/y");
        if (operation.equals("drag") && (action.x() == null || action.y() == null || action.x2() == null || action.y2() == null)) {
            throw new IllegalArgumentException("drag requires x/y/x2/y2");
        }
        if (operation.equals("screenshot_region") && (action.x() == null || action.y() == null
                || action.x2() == null || action.y2() == null || action.x2() <= action.x() || action.y2() <= action.y())) {
            throw new IllegalArgumentException("screenshot_region requires ordered x/y/x2/y2");
        }
        if (operation.equals("key") && (action.key() == null || action.key().isBlank())) throw new IllegalArgumentException("key requires key name");
        if (operation.equals("type") && (action.text() == null || action.text().isEmpty() || action.text().length() > 16384)) throw new IllegalArgumentException("type requires text up to 16384 characters");
        if (operation.equals("clipboard_write") && (action.text() == null || action.text().length() > 65536)) throw new IllegalArgumentException("clipboard_write requires text up to 65536 characters");
        if (operation.equals("focus") && (action.windowTitle() == null || action.windowTitle().isBlank())) throw new IllegalArgumentException("focus requires window title");
        if (action.x() != null && (action.x() < -100000 || action.x() > 100000)) throw new IllegalArgumentException("x is outside the allowed range");
        if (action.y() != null && (action.y() < -100000 || action.y() > 100000)) throw new IllegalArgumentException("y is outside the allowed range");
        if (action.x2() != null && (action.x2() < -100000 || action.x2() > 100000)) throw new IllegalArgumentException("x2 is outside the allowed range");
        if (action.y2() != null && (action.y2() < -100000 || action.y2() > 100000)) throw new IllegalArgumentException("y2 is outside the allowed range");
        if (action.screen() != null && (action.screen() < 0 || action.screen() > 32)) throw new IllegalArgumentException("screen must be between 0 and 32");
        if (action.durationMs() != null && (action.durationMs() < 0 || action.durationMs() > 10000)) throw new IllegalArgumentException("duration_ms must be between 0 and 10000");
        if (action.windowTitle() != null && (action.windowTitle().length() > 512
                || action.windowTitle().indexOf('\r') >= 0 || action.windowTitle().indexOf('\n') >= 0)) throw new IllegalArgumentException("window title is invalid");
    }

    private static String sha256(byte[] data) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String compactError(String value) {
        var error = value == null || value.isBlank() ? "desktop task failed" : SensitiveValueRedactor.redact(value.trim());
        return error.length() <= 4096 ? error : error.substring(0, 4096);
    }

}
