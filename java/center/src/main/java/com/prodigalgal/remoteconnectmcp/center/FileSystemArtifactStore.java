package com.prodigalgal.remoteconnectmcp.center;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.LinkOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
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
 * <p>New objects live below the physical {@code fs-v1/} namespace. Reads and
 * deletes still recognize the pre-namespace layout so a rolling deployment
 * does not strand objects written by an older Center.</p>
 */
public final class FileSystemArtifactStore implements ArtifactStore {
    private static final String PREFIX = "fs-v1/";
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final long MAX_STREAM_BYTES = ArtifactStore.MAX_STREAM_BYTES;
    private final Path root;

    public FileSystemArtifactStore(Path root) {
        if (root == null) throw new IllegalArgumentException("artifact root is required");
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
            if (!Files.isDirectory(this.root) || !Files.isWritable(this.root)) {
                throw new IOException("artifact root is not a writable directory");
            }
            restrictOwner(this.root, true);
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
                restrictOwner(temporary, false);
                moveAtomically(temporary, target);
                restrictOwner(target, false);
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
    public String put(String taskId, String sha256, InputStream input, long expectedBytes) {
        if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("artifact task/transfer id is required");
        if (sha256 == null || !sha256.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("artifact sha256 must be a 64-character hex digest");
        }
        if (input == null || expectedBytes < 0 || expectedBytes > MAX_STREAM_BYTES) {
            throw new IllegalArgumentException("artifact stream size is outside the allowed range");
        }
        var normalizedDigest = sha256.trim().toLowerCase();
        var objectKey = PREFIX + digest(taskId) + "/" + normalizedDigest + ".blob";
        var target = pathFor(objectKey);
        try {
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) {
                verify(target, normalizedDigest, expectedBytes);
                return objectKey;
            }
            var temporary = target.resolveSibling("." + target.getFileName() + "." + UUID.randomUUID() + ".part");
            try (var output = Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                var digest = MessageDigest.getInstance("SHA-256");
                var buffer = new byte[1024 * 1024];
                long count = 0;
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    count += read;
                    if (count > expectedBytes || count > MAX_STREAM_BYTES) {
                        throw new IOException("artifact stream exceeds declared size");
                    }
                    output.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                }
                var actual = HexFormat.of().formatHex(digest.digest());
                if (count != expectedBytes) throw new IOException("artifact stream size does not match metadata");
                if (!actual.equalsIgnoreCase(normalizedDigest)) throw new IOException("artifact stream SHA-256 mismatch");
                restrictOwner(temporary, false);
                moveAtomically(temporary, target);
                restrictOwner(target, false);
            } finally {
                Files.deleteIfExists(temporary);
            }
            return objectKey;
        } catch (NoSuchAlgorithmException | IOException exception) {
            throw new ArtifactStore.StorageException("cannot write streamed artifact object", exception);
        }
    }

    @Override
    public byte[] read(String objectKey) {
        var normalizedKey = ArtifactStore.normalizeKey(objectKey);
        var target = existingPathFor(normalizedKey);
        try {
            var size = Files.size(target);
            if (size < 0 || size > MAX_BYTES) throw new IOException("artifact object exceeds store limit");
            var data = Files.readAllBytes(target);
            verify(target, digestFromKey(objectKey), data.length);
            return data;
        } catch (IOException exception) {
            throw new ArtifactStore.StorageException("cannot read artifact object", exception);
        }
    }

    @Override
    public InputStream open(String objectKey) {
        var target = existingPathFor(ArtifactStore.normalizeKey(objectKey));
        try {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.size(target) < 0) {
                throw new IOException("artifact object is missing");
            }
            return Files.newInputStream(target, StandardOpenOption.READ);
        } catch (IOException exception) {
            throw new ArtifactStore.StorageException("cannot open artifact object", exception);
        }
    }

    @Override
    public void delete(String objectKey) {
        var normalizedKey = ArtifactStore.normalizeKey(objectKey);
        var target = pathFor(normalizedKey);
        var legacyTarget = legacyPathFor(normalizedKey);
        try {
            Files.deleteIfExists(target);
            pruneEmptyParents(target.getParent());
            if (!legacyTarget.equals(target)) {
                Files.deleteIfExists(legacyTarget);
                pruneEmptyParents(legacyTarget.getParent());
            }
        } catch (IOException exception) {
            throw new ArtifactStore.StorageException("cannot delete artifact object", exception);
        }
    }

    @Override
    public int sweepOrphans(Set<String> referencedKeys, Instant olderThan, int limit) {
        if (limit < 1) return 0;
        var references = referencedKeys == null ? Set.<String>of() : Set.copyOf(referencedKeys);
        var cutoff = olderThan == null ? Instant.now() : olderThan;
        var deleted = 0;
        // Only enumerate the store-owned namespace.  The configured root may
        // be shared with a marker/backup directory; an orphan sweep must
        // never delete files which do not belong to an fs-v1 object key.
        var objectRoot = root.resolve(PREFIX.substring(0, PREFIX.length() - 1)).normalize();
        if (!Files.isDirectory(objectRoot, LinkOption.NOFOLLOW_LINKS)) return 0;
        try (var paths = Files.walk(objectRoot)) {
            var candidates = paths
                    .filter(path -> !path.equals(objectRoot))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !path.getFileName().toString().contains(".part"))
                    .sorted()
                    .toList();
            for (var path : candidates) {
                if (deleted >= limit) break;
                var relative = objectRoot.relativize(path).toString().replace('\\', '/');
                var objectKey = PREFIX + relative;
                if (references.contains(objectKey)) continue;
                var modified = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant();
                if (!modified.isBefore(cutoff)) continue;
                Files.deleteIfExists(path);
                deleted++;
            }
            return deleted;
        } catch (IOException exception) {
            throw new ArtifactStore.StorageException("cannot sweep orphan artifact objects", exception);
        }
    }

    @Override
    public String backend() {
        return "filesystem";
    }

    private Path pathFor(String objectKey) {
        if (!objectKey.startsWith(PREFIX)) throw new ArtifactStore.StorageException("unsupported artifact object key");
        var relative = objectKey.substring(PREFIX.length());
        var candidate = root.resolve(PREFIX.substring(0, PREFIX.length() - 1)).resolve(relative).normalize();
        if (!candidate.startsWith(root) || relative.isBlank() || relative.contains("\\") || relative.contains("..")) {
            throw new ArtifactStore.StorageException("unsafe artifact object key");
        }
        return candidate;
    }

    /** Resolve an object written before the physical fs-v1 namespace existed. */
    private Path legacyPathFor(String objectKey) {
        if (!objectKey.startsWith(PREFIX)) throw new ArtifactStore.StorageException("unsupported artifact object key");
        var relative = objectKey.substring(PREFIX.length());
        var candidate = root.resolve(relative).normalize();
        if (!candidate.startsWith(root) || relative.isBlank() || relative.contains("\\") || relative.contains("..")) {
            throw new ArtifactStore.StorageException("unsafe artifact object key");
        }
        return candidate;
    }

    private Path existingPathFor(String objectKey) {
        var canonical = pathFor(objectKey);
        if (Files.exists(canonical, LinkOption.NOFOLLOW_LINKS)) return canonical;
        var legacy = legacyPathFor(objectKey);
        return Files.exists(legacy, LinkOption.NOFOLLOW_LINKS) ? legacy : canonical;
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
        try (var input = Files.newInputStream(target, StandardOpenOption.READ)) {
            var digest = MessageDigest.getInstance("SHA-256");
            var buffer = new byte[1024 * 1024];
            int read;
            long count = 0;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                count += read;
                digest.update(buffer, 0, read);
            }
            if (count != expectedBytes || !HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(expectedDigest)) {
                throw new IOException("artifact object SHA-256 mismatch");
            }
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
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
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                current = current.getParent();
                continue;
            }
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(current)) {
                if (entries.iterator().hasNext()) return;
            }
            Files.deleteIfExists(current);
            current = current.getParent();
        }
    }

    private static void restrictOwner(Path path, boolean directory) {
        try {
            var permissions = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            if (directory) permissions.add(PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows ACLs and some mounted filesystems do not expose POSIX
            // modes; the deployment volume/ACL remains the outer boundary.
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
