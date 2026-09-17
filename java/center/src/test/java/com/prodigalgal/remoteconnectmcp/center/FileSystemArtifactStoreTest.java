package com.prodigalgal.remoteconnectmcp.center;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;
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

    @Test
    void sweepsOnlyOldUnreferencedObjects(@TempDir Path root) throws Exception {
        var store = new FileSystemArtifactStore(root);
        var unrelated = root.resolve("backup-marker.txt");
        Files.writeString(unrelated, "keep me");
        Files.setLastModifiedTime(unrelated, FileTime.from(Instant.now().minus(Duration.ofDays(2))));
        var data = "orphan".getBytes(StandardCharsets.UTF_8);
        var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        var key = store.put("task-orphan", digest, data);
        var object = root.resolve(key.replace('/', java.io.File.separatorChar));
        Files.setLastModifiedTime(object, FileTime.from(Instant.now().minus(Duration.ofDays(2))));
        assertEquals(0, store.sweepOrphans(Set.of(key), Instant.now().minus(Duration.ofHours(1)), 10));
        assertTrue(Files.exists(object));
        assertEquals(1, store.sweepOrphans(Set.of(), Instant.now().minus(Duration.ofHours(1)), 10));
        assertTrue(Files.notExists(object));
        assertTrue(Files.exists(unrelated));
    }

    @Test
    void readsAndDeletesObjectsFromThePreNamespaceLayout(@TempDir Path root) throws Exception {
        var store = new FileSystemArtifactStore(root);
        var taskId = "legacy-task";
        var data = "legacy-artifact".getBytes(StandardCharsets.UTF_8);
        var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        var taskDigest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(taskId.getBytes(StandardCharsets.UTF_8)));
        var key = "fs-v1/" + taskDigest + "/" + digest + ".blob";
        var legacy = root.resolve(taskDigest).resolve(digest + ".blob");
        Files.createDirectories(legacy.getParent());
        Files.write(legacy, data);

        assertArrayEquals(data, store.read(key));
        store.delete(key);
        assertTrue(Files.notExists(legacy));
    }

    @Test
    void preservesZeroByteArtifacts(@TempDir Path root) throws Exception {
        var store = new FileSystemArtifactStore(root);
        var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new byte[0]));
        var key = store.put("empty-task", digest, new byte[0]);
        assertArrayEquals(new byte[0], store.read(key));
        try (var input = store.open(key)) {
            assertEquals(-1, input.read());
        }
    }
}
