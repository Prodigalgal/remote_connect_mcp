package com.prodigalgal.remoteconnectmcp.center;

import java.util.Objects;
import java.time.Instant;
import java.util.Set;

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
