package com.prodigalgal.remoteconnectmcp.center;

import java.io.InputStream;
import java.time.Instant;
import java.util.Set;

/**
 * Optional content-addressed adapter. Every payload uses the digest as the
 * logical task key, so
 * equal SHA-256 content is written once and referenced by many metadata rows.
 */
public final class ContentAddressedArtifactStore implements ArtifactStore {
    private final ArtifactStore delegate;

    public ContentAddressedArtifactStore(ArtifactStore delegate) {
        this.delegate = delegate == null ? new InMemoryArtifactStore() : delegate;
    }

    @Override
    public String put(String taskId, String sha256, byte[] data) {
        ArtifactStore.validateInput(taskId, sha256, data);
        return delegate.put(sha256, sha256, data);
    }

    @Override
    public String put(String taskId, String sha256, InputStream input, long expectedBytes) {
        if (sha256 == null || !sha256.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("artifact sha256 must be a 64-character hex digest");
        }
        return delegate.put(sha256, sha256, input, expectedBytes);
    }

    @Override public byte[] read(String objectKey) { return delegate.read(objectKey); }
    @Override public InputStream open(String objectKey) { return delegate.open(objectKey); }
    @Override public void delete(String objectKey) { delegate.delete(objectKey); }
    @Override public int sweepOrphans(Set<String> referencedKeys, Instant olderThan, int limit) {
        return delegate.sweepOrphans(referencedKeys, olderThan, limit);
    }
    @Override public String backend() { return delegate.backend() + "+dedup"; }
}
