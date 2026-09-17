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
import java.nio.file.StandardCopyOption;
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
        FileTransferAction action = null;
        try {
            action = task.fileTransfer();
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
            try { acknowledge(action, "canceled", 0, "", "file transfer canceled", false); }
            catch (IOException ignored) { }
            try {
                sendState(new TaskUpdateRequest("canceled", null, "file transfer canceled", null, Instant.now(), false));
            } catch (Exception reportFailure) {
                LOG.log(Level.FINE, "could not report canceled file transfer " + task.id(), reportFailure);
            }
        } catch (Exception exception) {
            try { acknowledge(action, "failed", 0, "", compactError(exception.getMessage()), false); }
            catch (IOException ignored) { }
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
                action.expectedBytes(), action.expectedSha256(), task.attempt(), action.overwrite());
        acknowledge(action, "delivered", action.expectedBytes(), action.expectedSha256(), null, true);
        sendOutput("received " + action.fileName() + " (" + action.expectedBytes() + " bytes, sha256=" + action.expectedSha256() + ")");
        sendState(new TaskUpdateRequest("completed", 0, null, null, Instant.now(), false));
    }

    private void publish(FileTransferAction action) throws IOException, InterruptedException {
        var source = AgentPaths.resolveFilePath(config, identity.machineId(), task, action.sourcePath(), true);
        var parent = source.getParent();
        if (parent == null) throw new IOException("source file has no parent");
        var sourceBytes = Files.size(source);
        if (sourceBytes < 0 || sourceBytes > MAX_BYTES) throw new IOException("source file size is outside the allowed range");
        try {
            var free = Files.getFileStore(parent).getUsableSpace();
            if (free < sourceBytes + 64L * 1024 * 1024) {
                throw new IOException("insufficient local disk space for transfer snapshot");
            }
        } catch (IOException exception) {
            throw new IOException("cannot inspect local disk space for transfer snapshot", exception);
        }
        var snapshot = Files.createTempFile(parent, ".rcm-snapshot-", ".part");
        try {
            // Hash and upload the same immutable snapshot.  Reading the source
            // once for the digest and opening it again for HTTP would permit a
            // same-size replacement to pass the local check but fail remotely
            // with an opaque SHA mismatch.
            Files.copy(source, snapshot, StandardCopyOption.REPLACE_EXISTING);
            var bytes = Files.size(snapshot);
            if (bytes < 0 || bytes > MAX_BYTES) throw new IOException("source file size is outside the allowed range");
            var digest = action.expectedSha256();
            if (digest == null || !digest.matches("(?i)[0-9a-f]{64}")) digest = sha256(snapshot);
            var response = transport.uploadTransfer(identity.machineId(), identity.token(), action.transferId(), snapshot,
                    action.fileName(), action.mimeType(), bytes, digest, task.attempt());
            var sha = response == null || response.sha256() == null || response.sha256().isBlank() ? digest : response.sha256();
            acknowledge(action, "delivered", bytes, sha, null, true);
            sendOutput("published " + action.fileName() + " (" + bytes + " bytes, sha256=" + sha + ")");
            sendState(new TaskUpdateRequest("completed", 0, null, null, Instant.now(), false));
        } finally {
            Files.deleteIfExists(snapshot);
        }
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

    private void acknowledge(FileTransferAction action, String status, long bytes, String sha256, String error,
                             boolean required) throws IOException {
        if (action == null || action.transferId() == null || action.transferId().isBlank()) return;
        try {
            AgentRetry.call(LOG, "file transfer acknowledgement " + task.id(), () -> {
                transport.acknowledgeTransfer(identity.machineId(), identity.token(), action.transferId(),
                        new FileTransferResponse(action.transferId(), action.artifactId(), status, bytes,
                                sha256 == null ? "" : sha256, error), task.attempt());
                return null;
            });
        } catch (Exception acknowledgementFailure) {
            LOG.log(Level.FINE, "could not report file transfer acknowledgement " + task.id(), acknowledgementFailure);
            if (required) throw new IOException("file transfer acknowledgement was not accepted", acknowledgementFailure);
        }
    }

    private static String compactError(String value) {
        var error = value == null || value.isBlank() ? "file transfer failed" : SensitiveValueRedactor.redact(value.trim());
        return error.length() <= 4096 ? error : error.substring(0, 4096);
    }
}
