package com.prodigalgal.remoteconnectmcp.center;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemArtifactStoreTest {
    @Test
    void writesReadsVerifiesAndDeletesAnOpaqueObject(@TempDir Path root) throws Exception {
        var store = new FileSystemArtifactStore(root);
        var data = "artifact-on-disk".getBytes(StandardCharsets.UTF_8);
        var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));

        var key = store.put("task/with-untrusted-path", digest, data);

        assertTrue(key.startsWith("fs-v1/"));
        assertArrayEquals(data, store.read(key));
        try (var paths = Files.walk(root)) {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString().endsWith(".part")));
        }
        store.delete(key);
        assertThrows(ArtifactStore.StorageException.class, () -> store.read(key));
    }

    @Test
    void rejectsUnsafeOrMismatchedObjects(@TempDir Path root) {
        var store = new FileSystemArtifactStore(root);
        var data = new byte[]{1, 2, 3};

        assertThrows(IllegalArgumentException.class, () -> store.put("task", "not-a-digest", data));
        assertThrows(ArtifactStore.StorageException.class, () -> store.read("fs-v1/../escape.blob"));
        assertThrows(ArtifactStore.StorageException.class, () -> store.read("memory-v1/task/deadbeef"));
        assertEquals("filesystem", store.backend());
    }
}
