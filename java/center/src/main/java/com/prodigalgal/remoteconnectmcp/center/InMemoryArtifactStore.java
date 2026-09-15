package com.prodigalgal.remoteconnectmcp.center;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test-only payload store used by the package-private JDBC constructor.
 * Production PostgreSQL wiring never selects this implementation.
 */
final class InMemoryArtifactStore implements ArtifactStore {
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @Override
    public String put(String taskId, String sha256, byte[] data) {
        ArtifactStore.validateInput(taskId, sha256, data);
        var key = "memory-v1/" + taskId + "/" + sha256.toLowerCase();
        objects.putIfAbsent(key, data.clone());
        return key;
    }

    @Override
    public byte[] read(String objectKey) {
        var data = objects.get(ArtifactStore.normalizeKey(objectKey));
        if (data == null) throw new ArtifactStore.StorageException("artifact object is missing");
        return data.clone();
    }

    @Override
    public void delete(String objectKey) {
        objects.remove(ArtifactStore.normalizeKey(objectKey));
    }

    @Override
    public String backend() {
        // Keep the database value schema-compatible with the durable backend
        // while this implementation remains reachable only from package tests.
        return "filesystem";
    }
}
