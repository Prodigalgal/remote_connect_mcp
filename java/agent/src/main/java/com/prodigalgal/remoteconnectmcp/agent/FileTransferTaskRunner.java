package com.prodigalgal.remoteconnectmcp.agent;

import com.prodigalgal.remoteconnectmcp.protocol.FileTransferAction;
import com.prodigalgal.remoteconnectmcp.protocol.FileTransferResponse;
import com.prodigalgal.remoteconnectmcp.protocol.SensitiveValueRedactor;
import com.prodigalgal.remoteconnectmcp.protocol.TaskCommand;
import com.prodigalgal.remoteconnectmcp.protocol.TaskUpdateRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Streams a Center-owned artifact to or from the Agent filesystem. */
final class FileTransferTaskRunner implements Runnable {
    private static final Logger LOG = Logger.getLogger(FileTransferTaskRunner.class.getName());
    private static final long MAX_BYTES = 4L * 1024 * 1024 * 1024;
    private final AgentConfig config;
    private final AgentIdentity identity;
    private final TaskCommand task;
    private final AgentTransport transport;

    FileTransferTaskRunner(AgentConfig config, AgentIdentity identity, TaskCommand task, AgentTransport transport) {
        this.config = config;
        this.identity = identity;
        this.task = task;
        this.transport = transport;
    }

    @Override
    public void run() {
        try {
            var action = task.fileTransfer();
            if (action == null) throw new IOException("file transfer action is missing");
            sendState(new TaskUpdateRequest("running", null, null, Instant.now(), null, false));
            if (action.webToAgent()) {
                receive(action);
            } else if (action.agentToWeb()) {
                publish(action);
            } else {
                throw new IOException("unsupported file transfer direction");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            try {
                sendState(new TaskUpdateRequest("canceled", null, "file transfer canceled", null, Instant.now(), false));
            } catch (Exception reportFailure) {
                LOG.log(Level.FINE, "could not report canceled file transfer " + task.id(), reportFailure);
            }
        } catch (Exception exception) {
            try {
                sendState(new TaskUpdateRequest("failed", null, compactError(exception.getMessage()), null, Instant.now(), false));
            } catch (Exception reportFailure) {
                LOG.log(Level.WARNING, "could not report failed file transfer " + task.id(), reportFailure);
            }
        }
    }

    private void receive(FileTransferAction action) throws IOException, InterruptedException {
        var destination = AgentPaths.resolveFilePath(config, identity.machineId(), task, action.destinationPath(), false);
        if (Files.exists(destination) && !action.overwrite()) {
            throw new IOException("destination already exists and overwrite is false");
        }
        transport.downloadTransfer(identity.machineId(), identity.token(), action.transferId(), destination,
                action.expectedBytes(), action.expectedSha256(), task.attempt());
        sendOutput("received " + action.fileName() + " (" + action.expectedBytes() + " bytes, sha256=" + action.expectedSha256() + ")");
        sendState(new TaskUpdateRequest("completed", 0, null, null, Instant.now(), false));
    }

    private void publish(FileTransferAction action) throws IOException, InterruptedException {
        var source = AgentPaths.resolveFilePath(config, identity.machineId(), task, action.sourcePath(), true);
        var bytes = Files.size(source);
        if (bytes <= 0 || bytes > MAX_BYTES) throw new IOException("source file size is outside the allowed range");
        var digest = action.expectedSha256();
        if (digest == null || !digest.matches("(?i)[0-9a-f]{64}")) digest = sha256(source);
        var response = transport.uploadTransfer(identity.machineId(), identity.token(), action.transferId(), source,
                action.fileName(), action.mimeType(), bytes, digest, task.attempt());
        var sha = response == null || response.sha256() == null || response.sha256().isBlank() ? digest : response.sha256();
        sendOutput("published " + action.fileName() + " (" + bytes + " bytes, sha256=" + sha + ")");
        sendState(new TaskUpdateRequest("completed", 0, null, null, Instant.now(), false));
    }

    private String sha256(Path source) throws IOException {
        try (InputStream input = Files.newInputStream(source)) {
            var digest = MessageDigest.getInstance("SHA-256");
            var buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
    }

    private void sendOutput(String text) throws IOException, InterruptedException {
        var data = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        AgentRetry.call(LOG, "file transfer output upload " + task.id(), () -> {
            transport.appendOutput(identity.machineId(), identity.token(), task.id(), task.attempt(), 0, data);
            return null;
        });
    }

    private void sendState(TaskUpdateRequest state) throws IOException, InterruptedException {
        AgentRetry.call(LOG, "file transfer state upload " + task.id(), () -> {
            transport.updateState(identity.machineId(), identity.token(), task.id(), task.attempt(), state);
            return null;
        });
    }

    private static String compactError(String value) {
        var error = value == null || value.isBlank() ? "file transfer failed" : SensitiveValueRedactor.redact(value.trim());
        return error.length() <= 4096 ? error : error.substring(0, 4096);
    }
}
