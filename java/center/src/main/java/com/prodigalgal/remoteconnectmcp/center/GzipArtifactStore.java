package com.prodigalgal.remoteconnectmcp.center;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Opt-in gzip adapter for text-like payloads. It keeps the original SHA-256
 * and byte count in the opaque object key while storing only the compressed
 * bytes in the delegate. Streaming writes spool to the configured temporary
 * directory, never to the Java heap.
 */
public final class GzipArtifactStore implements ArtifactStore {
    private static final String PREFIX = "gzip-v1/";
    private static final long MAX = ArtifactStore.MAX_STREAM_BYTES;
    private static final long MAX_MATERIALIZED_BYTES = 64L * 1024 * 1024;
    private final ArtifactStore delegate;
    private final Path spoolRoot;

    public GzipArtifactStore(ArtifactStore delegate, Path spoolRoot) {
        this.delegate = delegate == null ? new InMemoryArtifactStore() : delegate;
        this.spoolRoot = (spoolRoot == null ? Path.of(System.getProperty("java.io.tmpdir", ".")) : spoolRoot)
                .toAbsolutePath().normalize();
    }

    @Override
    public String put(String taskId, String sha256, byte[] data) {
        ArtifactStore.validateInput(taskId, sha256, data);
        if (data.length > MAX) throw new IllegalArgumentException("artifact exceeds stream limit");
        try {
            var compressed = compress(data);
            if (compressed.length >= data.length) return delegate.put(sha256, sha256, data);
            var compressedDigest = digest(compressed);
            var delegateKey = delegate.put("gzip-" + sha256, compressedDigest, compressed);
            return envelope(delegateKey, sha256, data.length);
        } catch (IOException exception) {
            throw new ArtifactStore.StorageException("cannot gzip artifact", exception);
        }
    }

    @Override
    public String put(String taskId, String sha256, InputStream input, long expectedBytes) {
        if (taskId == null || taskId.isBlank() || sha256 == null || !sha256.matches("(?i)[0-9a-f]{64}")
                || input == null || expectedBytes < 0 || expectedBytes > MAX) {
            throw new IllegalArgumentException("artifact stream metadata is invalid");
        }
        Path temporary = null;
        try {
            Files.createDirectories(spoolRoot);
            temporary = Files.createTempFile(spoolRoot, "rcm-gzip-", ".part");
            var originalDigest = MessageDigest.getInstance("SHA-256");
            long count = 0;
            try (var output = new GZIPOutputStream(Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE))) {
                var buffer = new byte[1024 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    count += read;
                    if (count > expectedBytes || count > MAX) throw new IOException("artifact stream exceeds declared size");
                    originalDigest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
            if (count != expectedBytes || !HexFormat.of().formatHex(originalDigest.digest()).equalsIgnoreCase(sha256)) {
                throw new IOException("artifact stream hash or size mismatch");
            }
            var compressedBytes = Files.size(temporary);
            if (compressedBytes >= expectedBytes) {
                try (var plain = Files.newInputStream(temporary)) {
                    // Re-expand only when compression is not beneficial. This
                    // path remains disk-backed and bounded.
                    try (var expanded = new GZIPInputStream(plain)) {
                        return delegate.put(sha256, sha256, expanded, expectedBytes);
                    }
                }
            }
            var compressedDigest = digest(temporary);
            try (var compressed = Files.newInputStream(temporary)) {
                var delegateKey = delegate.put("gzip-" + sha256, compressedDigest, compressed, compressedBytes);
                return envelope(delegateKey, sha256, expectedBytes);
            }
        } catch (IOException | java.security.NoSuchAlgorithmException exception) {
            throw new ArtifactStore.StorageException("cannot gzip artifact stream", exception);
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }

    @Override
    public byte[] read(String objectKey) {
        var parsed = parse(objectKey);
        if (parsed == null) return delegate.read(objectKey);
        if (parsed.bytes() > MAX_MATERIALIZED_BYTES) {
            throw new ArtifactStore.StorageException("gzip artifact is too large for byte[] read; use streaming open()");
        }
        try (var compressed = delegate.open(parsed.delegateKey()); var input = new GZIPInputStream(compressed);
             var output = new ByteArrayOutputStream((int) Math.min(parsed.bytes(), 1024 * 1024L))) {
            input.transferTo(output);
            var data = output.toByteArray();
            if (data.length != parsed.bytes() || !digest(data).equalsIgnoreCase(parsed.sha256())) {
                throw new IOException("gzip artifact integrity check failed");
            }
            return data;
        } catch (IOException exception) {
            throw new ArtifactStore.StorageException("cannot read gzip artifact", exception);
        }
    }

    @Override
    public InputStream open(String objectKey) {
        var parsed = parse(objectKey);
        if (parsed == null) return delegate.open(objectKey);
        try { return new GZIPInputStream(delegate.open(parsed.delegateKey())); }
        catch (IOException exception) { throw new ArtifactStore.StorageException("cannot open gzip artifact", exception); }
    }

    @Override
    public void delete(String objectKey) {
        var parsed = parse(objectKey);
        delegate.delete(parsed == null ? objectKey : parsed.delegateKey());
    }

    @Override
    public int sweepOrphans(Set<String> referencedKeys, Instant olderThan, int limit) {
        if (limit < 1) return 0;
        var delegateReferences = new java.util.HashSet<String>();
        if (referencedKeys != null) {
            for (var key : referencedKeys) {
                if (key == null || key.isBlank()) continue;
                var parsed = parse(key);
                delegateReferences.add(parsed == null ? key : parsed.delegateKey());
            }
        }
        return delegate.sweepOrphans(delegateReferences, olderThan, limit);
    }

    @Override public String backend() { return delegate.backend() + "+gzip"; }

    private static String envelope(String delegateKey, String sha256, long bytes) {
        var encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(delegateKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return PREFIX + encoded + "/" + sha256.toLowerCase(java.util.Locale.ROOT) + "/" + bytes;
    }

    private static Parsed parse(String key) {
        if (key == null || !key.startsWith(PREFIX)) return null;
        var fields = key.substring(PREFIX.length()).split("/", -1);
        if (fields.length != 3 || !fields[1].matches("(?i)[0-9a-f]{64}")) throw new ArtifactStore.StorageException("invalid gzip artifact key");
        try {
            return new Parsed(new String(Base64.getUrlDecoder().decode(fields[0]), java.nio.charset.StandardCharsets.UTF_8), fields[1], Long.parseLong(fields[2]));
        } catch (RuntimeException exception) { throw new ArtifactStore.StorageException("invalid gzip artifact key", exception); }
    }

    private static byte[] compress(byte[] data) throws IOException {
        var output = new ByteArrayOutputStream(Math.max(64, data.length / 2));
        try (var gzip = new GZIPOutputStream(output)) { gzip.write(data); }
        return output.toByteArray();
    }

    private static String digest(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new ArtifactStore.StorageException("SHA-256 is unavailable", exception); }
    }

    private static String digest(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            var digest = MessageDigest.getInstance("SHA-256");
            var buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) { if (read > 0) digest.update(buffer, 0, read); }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) { throw new IOException("SHA-256 is unavailable", exception); }
    }

    private record Parsed(String delegateKey, String sha256, long bytes) { }
}
