package com.prodigalgal.remoteconnectmcp.center;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Durable filesystem object store for a Center PVC or dedicated data volume.
 *
 * <p>The database stores only metadata and the returned {@code fs-v1/...}
 * reference.  Writes use a same-directory temporary file followed by an
 * atomic move (with a safe copy fallback on filesystems without
 * {@code ATOMIC_MOVE}); readers verify both the bounded size and SHA-256.
 * Object keys are derived from digests rather than caller-controlled path
 * fragments, so a malformed task ID cannot escape the configured root.</p>
 */
public final class FileSystemArtifactStore implements ArtifactStore {
    private static final String PREFIX = "fs-v1/";
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private final Path root;

    public FileSystemArtifactStore(Path root) {
        if (root == null) throw new IllegalArgumentException("artifact root is required");
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
            if (!Files.isDirectory(this.root) || !Files.isWritable(this.root)) {
                throw new IOException("artifact root is not a writable directory");
            }
        } catch (IOException exception) {
            throw new ArtifactStore.StorageException("cannot initialize artifact root", exception);
        }
    }

    @Override
    public String put(String taskId, String sha256, byte[] data) {
        ArtifactStore.validateInput(taskId, sha256, data);
        if (data.length > MAX_BYTES) throw new IllegalArgumentException("artifact exceeds 64 MiB store limit");
        var normalizedDigest = sha256.trim().toLowerCase();
        var objectKey = PREFIX + digest(taskId) + "/" + normalizedDigest + ".blob";
        var target = pathFor(objectKey);
        try {
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) {
                verify(target, normalizedDigest, data.length);
                return objectKey;
            }
            var temporary = target.resolveSibling("." + target.getFileName() + "." + UUID.randomUUID() + ".part");
            try {
                Files.write(temporary, data, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                moveAtomically(temporary, target);
            } finally {
                Files.deleteIfExists(temporary);
            }
            verify(target, normalizedDigest, data.length);
            return objectKey;
        } catch (IOException exception) {
            throw new ArtifactStore.StorageException("cannot write artifact object", exception);
        }
    }

    @Override
    public byte[] read(String objectKey) {
        var target = pathFor(ArtifactStore.normalizeKey(objectKey));
        try {
            var size = Files.size(target);
            if (size <= 0 || size > MAX_BYTES) throw new IOException("artifact object exceeds store limit");
            var data = Files.readAllBytes(target);
            verify(target, digestFromKey(objectKey), data.length);
            return data;
        } catch (IOException exception) {
            throw new ArtifactStore.StorageException("cannot read artifact object", exception);
        }
    }

    @Override
    public void delete(String objectKey) {
        var target = pathFor(ArtifactStore.normalizeKey(objectKey));
        try {
            Files.deleteIfExists(target);
            pruneEmptyParents(target.getParent());
        } catch (IOException exception) {
            throw new ArtifactStore.StorageException("cannot delete artifact object", exception);
        }
    }

    @Override
    public String backend() {
        return "filesystem";
    }

    private Path pathFor(String objectKey) {
        if (!objectKey.startsWith(PREFIX)) throw new ArtifactStore.StorageException("unsupported artifact object key");
        var relative = objectKey.substring(PREFIX.length());
        var candidate = root.resolve(relative).normalize();
        if (!candidate.startsWith(root) || relative.isBlank() || relative.contains("\\") || relative.contains("..")) {
            throw new ArtifactStore.StorageException("unsafe artifact object key");
        }
        return candidate;
    }

    private static String digestFromKey(String objectKey) {
        var normalized = ArtifactStore.normalizeKey(objectKey);
        var name = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (!name.endsWith(".blob")) throw new ArtifactStore.StorageException("invalid artifact object key");
        var digest = name.substring(0, name.length() - ".blob".length());
        if (!digest.matches("(?i)[0-9a-f]{64}")) throw new ArtifactStore.StorageException("invalid artifact object digest");
        return digest.toLowerCase();
    }

    private static void verify(Path target, String expectedDigest, long expectedBytes) throws IOException {
        if (Files.size(target) != expectedBytes) throw new IOException("artifact object size mismatch");
        var actual = sha256(Files.readAllBytes(target));
        if (!actual.equalsIgnoreCase(expectedDigest)) throw new IOException("artifact object SHA-256 mismatch");
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void pruneEmptyParents(Path directory) throws IOException {
        var current = directory;
        while (current != null && !current.equals(root)) {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(current)) {
                if (entries.iterator().hasNext()) return;
            }
            Files.deleteIfExists(current);
            current = current.getParent();
        }
    }

    private static String digest(String value) {
        return sha256(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new ArtifactStore.StorageException("SHA-256 is unavailable", exception);
        }
    }
}
