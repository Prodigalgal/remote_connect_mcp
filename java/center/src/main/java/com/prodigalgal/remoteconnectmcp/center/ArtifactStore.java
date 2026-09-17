package com.prodigalgal.remoteconnectmcp.center;

import java.util.Objects;
import java.time.Instant;
import java.util.Set;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Payload store for task artifacts.
 *
 * <p>PostgreSQL owns the artifact metadata and lifecycle reference; this
 * interface owns the bytes.  Production Center wiring must provide a durable
 * implementation so task projections never load a large {@code bytea} value.
 * The small in-memory implementation is intentionally available only to
 * package-private protocol/adapter tests.</p>
 */
public interface ArtifactStore {
    /** Generic file-transfer ceiling. Task artifacts keep their smaller contract limit. */
    long MAX_STREAM_BYTES = 4L * 1024 * 1024 * 1024;

    /** Persist an immutable payload and return an opaque store-owned key. */
    String put(String taskId, String sha256, byte[] data);

    /**
     * Persist a stream without materialising it in the Center heap. Backends
     * should override this method; the default is deliberately bounded and
     * exists only for the in-memory/test adapter.
     */
    default String put(String taskId, String sha256, InputStream input, long expectedBytes) {
        if (input == null || expectedBytes < 0 || expectedBytes > MAX_STREAM_BYTES) {
            throw new IllegalArgumentException("artifact stream size is outside the allowed range");
        }
        if (expectedBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("legacy artifact backend cannot materialise files larger than 2 GiB");
        }
        try (var output = new ByteArrayOutputStream((int) Math.min(expectedBytes, 1024 * 1024L))) {
            input.transferTo(output);
            var data = output.toByteArray();
            if (data.length != expectedBytes) throw new IllegalArgumentException("artifact stream size does not match metadata");
            return put(taskId, sha256, data);
        } catch (IOException exception) {
            throw new StorageException("cannot read artifact stream", exception);
        }
    }

    /** Read a payload by store-owned key. */
    byte[] read(String objectKey);

    /** Open a payload for a streaming Agent/UI response. */
    default InputStream open(String objectKey) {
        return new ByteArrayInputStream(read(objectKey));
    }

    /** Best-effort removal used by explicit retention/garbage-collection jobs. */
    void delete(String objectKey);

    /**
     * Remove a bounded number of old objects which are not referenced by the
     * supplied metadata snapshot.  Backends which cannot enumerate objects
     * may return zero; the task database remains the source of truth.  The
     * grace period prevents a concurrent upload (put-before-metadata-insert)
     * from being mistaken for an orphan.
     */
    default int sweepOrphans(Set<String> referencedKeys, Instant olderThan, int limit) {
        return 0;
    }

    /** Stable backend name recorded in metadata and diagnostics. */
    String backend();

    static void validateInput(String taskId, String sha256, byte[] data) {
        if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("taskId is required");
        if (sha256 == null || !sha256.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("artifact sha256 must be a 64-character hex digest");
        }
        if (data == null) throw new IllegalArgumentException("artifact data is required");
    }

    static String normalizeKey(String objectKey) {
        return Objects.requireNonNull(objectKey, "objectKey").trim();
    }

    /** Runtime failure raised when an artifact cannot be stored or verified. */
    final class StorageException extends RuntimeException {
        public StorageException(String message) {
            super(message);
        }

        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
