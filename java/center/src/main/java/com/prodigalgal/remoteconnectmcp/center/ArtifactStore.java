package com.prodigalgal.remoteconnectmcp.center;

import java.util.Objects;

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
    /** Persist an immutable payload and return an opaque store-owned key. */
    String put(String taskId, String sha256, byte[] data);

    /** Read a payload by store-owned key. */
    byte[] read(String objectKey);

    /** Best-effort removal used by explicit retention/garbage-collection jobs. */
    void delete(String objectKey);

    /** Stable backend name recorded in metadata and diagnostics. */
    String backend();

    static void validateInput(String taskId, String sha256, byte[] data) {
        if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("taskId is required");
        if (sha256 == null || !sha256.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("artifact sha256 must be a 64-character hex digest");
        }
        if (data == null || data.length == 0) throw new IllegalArgumentException("artifact data is required");
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
