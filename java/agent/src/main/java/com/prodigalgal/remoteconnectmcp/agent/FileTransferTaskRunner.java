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
import java.time.Duration;
import java.util.HexFormat;
import java.nio.file.LinkOption;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Streams a Center-owned artifact to or from the Agent filesystem. */
final class FileTransferTaskRunner implements Runnable {
    private static final Logger LOG = Logger.getLogger(FileTransferTaskRunner.class.getName());
    private static final long MAX_BYTES = 4L * 1024 * 1024 * 1024;
    private static final Duration SNAPSHOT_RETENTION = Duration.ofHours(24);
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
        AgentRetry.call(LOG, "file transfer download " + task.id(), () -> {
            transport.downloadTransfer(identity.machineId(), identity.token(), action.transferId(), destination,
                    action.expectedBytes(), action.expectedSha256(), task.attempt(), action.overwrite());
            return null;
        });
        acknowledge(action, "delivered", action.expectedBytes(), action.expectedSha256(), null, true);
        sendOutput("received " + action.fileName() + " (" + action.expectedBytes() + " bytes, sha256=" + action.expectedSha256() + ")");
        sendState(new TaskUpdateRequest("completed", 0, null, null, Instant.now(), false));
    }

    private void publish(FileTransferAction action) throws IOException, InterruptedException {
        var source = AgentPaths.resolveFilePath(config, identity.machineId(), task, action.sourcePath(), true);
        var parent = source.getParent();
        if (parent == null) throw new IOException("source file has no parent");
        // Never stream directories, FIFOs, sockets, or symlink targets as a
        // file-transfer body.  A regular-file snapshot is finite and can be
        // resumed safely; special files could otherwise block a worker
        // indefinitely or expose a moving device stream.
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("source file is not a regular non-symlink file");
        }
        cleanupStaleSnapshots(parent);
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
        var safeTransferId = action.transferId().replaceAll("[^A-Za-z0-9._-]", "_");
        var snapshot = parent.resolve(".rcm-snapshot-" + safeTransferId + ".part");
        var uploaded = false;
        try {
            // Hash and upload the same immutable snapshot.  Keep a stable
            // snapshot across task retries so the Center can resume from its
            // last acknowledged chunk without recopying a multi-GB source.
            if (Files.exists(snapshot)) {
                if (!Files.isRegularFile(snapshot) || Files.size(snapshot) > MAX_BYTES) {
                    Files.deleteIfExists(snapshot);
                }
            }
            if (!Files.exists(snapshot)) {
                var snapshotTemp = parent.resolve(snapshot.getFileName() + ".new");
                try {
                    Files.copy(source, snapshotTemp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    try {
                        Files.move(snapshotTemp, snapshot, StandardCopyOption.ATOMIC_MOVE);
                    } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                        Files.move(snapshotTemp, snapshot, StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally {
                    Files.deleteIfExists(snapshotTemp);
                }
            }
            var bytes = Files.size(snapshot);
            if (bytes < 0 || bytes > MAX_BYTES) throw new IOException("source file size is outside the allowed range");
            var digest = action.expectedSha256();
            if (digest == null || !digest.matches("(?i)[0-9a-f]{64}")) digest = sha256(snapshot);
            var response = AgentRetry.call(LOG, "file transfer upload " + task.id(), () ->
                    transport.uploadTransfer(identity.machineId(), identity.token(), action.transferId(), snapshot,
                            action.fileName(), action.mimeType(), bytes, digest, task.attempt()));
            var sha = response == null || response.sha256() == null || response.sha256().isBlank() ? digest : response.sha256();
            acknowledge(action, "delivered", bytes, sha, null, true);
            sendOutput("published " + action.fileName() + " (" + bytes + " bytes, sha256=" + sha + ")");
            sendState(new TaskUpdateRequest("completed", 0, null, null, Instant.now(), false));
            uploaded = true;
        } finally {
            // A successful upload is the only point at which the stable
            // snapshot can be removed.  On a transient network failure it is
            // intentionally retained for the next attempt; the normal Agent
            // transfer GC removes stale snapshots after their task expires.
            if (uploaded) {
                Files.deleteIfExists(snapshot);
            }
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

    private static void cleanupStaleSnapshots(Path parent) {
        var cutoff = Instant.now().minus(SNAPSHOT_RETENTION);
        try (var entries = Files.newDirectoryStream(parent, ".rcm-snapshot-*.part*")) {
            var checked = 0;
            for (var entry : entries) {
                if (checked++ >= 64) break;
                try {
                    if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
                            && Files.getLastModifiedTime(entry, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff)) {
                        Files.deleteIfExists(entry);
                    }
                } catch (IOException ignored) {
                    // A concurrent task may own the snapshot; leave it for
                    // that task rather than turning cleanup into a failure.
                }
            }
        } catch (IOException ignored) {
            // Cleanup is a bounded best-effort hygiene step, never a reason
            // to skip a valid transfer.
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
