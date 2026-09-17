package com.prodigalgal.remoteconnectmcp.center;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;

/**
 * Small HTTP object-store adapter for an internal S3/WebDAV-style gateway.
 * The gateway owns durable bytes and lifecycle policy; Center keeps only
 * metadata in PostgreSQL.  A bounded JDK client keeps the adapter usable in a
 * Native Image without pulling an SDK and never logs the optional bearer.
 */
public final class HttpArtifactStore implements ArtifactStore {
    private static final String PREFIX = "http-v1/";
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final long MAX_STREAM_BYTES = ArtifactStore.MAX_STREAM_BYTES;

    private final URI baseUrl;
    private final String token;
    private final Duration timeout;
    private final Duration streamTimeout;
    private final HttpClient client;

    public HttpArtifactStore(URI baseUrl, String token, Duration timeout) {
        if (baseUrl == null || baseUrl.getHost() == null
                || (!"https".equalsIgnoreCase(baseUrl.getScheme()) && !isLoopback(baseUrl.getHost()))) {
            throw new IllegalArgumentException("artifact HTTP base URL must use HTTPS (HTTP is allowed only for loopback development)");
        }
        var normalized = baseUrl.toString().replaceAll("/+$", "");
        this.baseUrl = URI.create(normalized + "/");
        this.token = token == null ? "" : token.trim();
        if (this.token.indexOf('\r') >= 0 || this.token.indexOf('\n') >= 0 || this.token.length() > 4096) {
            throw new IllegalArgumentException("artifact HTTP token must be a single line");
        }
        this.timeout = timeout == null || timeout.isZero() || timeout.isNegative() ? Duration.ofSeconds(30) : timeout;
        if (this.timeout.compareTo(Duration.ofSeconds(1)) < 0 || this.timeout.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException("artifact HTTP timeout must be between 1 and 120 seconds");
        }
        // Control-plane requests stay short, while the data plane may carry
        // multi-gigabyte files. Keep a finite upper bound so a dead gateway
        // still releases its virtual thread and socket eventually.
        this.streamTimeout = Duration.ofMinutes(30);
        this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2)
                .connectTimeout(this.timeout).build();
    }

    @Override
    public String put(String taskId, String sha256, byte[] data) {
        ArtifactStore.validateInput(taskId, sha256, data);
        if (data.length > MAX_BYTES) throw new IllegalArgumentException("artifact exceeds 64 MiB store limit");
        var digest = sha256.trim().toLowerCase();
        if (!sha256(data).equalsIgnoreCase(digest)) {
            throw new IllegalArgumentException("artifact sha256 does not match data");
        }
        var key = PREFIX + digest(taskId) + "/" + digest + ".blob";
        var request = request("PUT", key).header("Content-Type", "application/octet-stream")
                .header("X-RCM-SHA256", digest).PUT(HttpRequest.BodyPublishers.ofByteArray(data)).build();
        var response = send(request);
        try {
            if (response.statusCode() != 200 && response.statusCode() != 201 && response.statusCode() != 204) {
                throw failure("put", response.statusCode());
            }
        } finally {
            closeQuietly(response.body());
        }
        return key;
    }

    @Override
    public String put(String taskId, String sha256, InputStream input, long expectedBytes) {
        if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("artifact task/transfer id is required");
        if (sha256 == null || !sha256.matches("(?i)[0-9a-f]{64}")) throw new IllegalArgumentException("artifact sha256 must be a 64-character hex digest");
        if (input == null || expectedBytes < 0 || expectedBytes > MAX_STREAM_BYTES) throw new IllegalArgumentException("artifact stream size is outside the allowed range");
        var digest = sha256.trim().toLowerCase();
        var key = PREFIX + digest(taskId) + "/" + digest + ".blob";
        var request = request("PUT", key).header("Content-Type", "application/octet-stream")
                // BodyPublishers.ofInputStream is intentionally chunked and
                // Content-Length is a restricted JDK header. The gateway
                // receives the expected size through an application header.
                .header("X-RCM-Expected-Bytes", Long.toString(expectedBytes)).header("X-RCM-SHA256", digest)
                .timeout(streamTimeout)
                .PUT(HttpRequest.BodyPublishers.ofInputStream(() -> input)).build();
        var response = send(request, streamTimeout);
        try {
            if (response.statusCode() != 200 && response.statusCode() != 201 && response.statusCode() != 204) {
                throw failure("put", response.statusCode());
            }
        } finally {
            closeQuietly(response.body());
            closeQuietly(input);
        }
        return key;
    }

    @Override
    public byte[] read(String objectKey) {
        var key = normalizeKey(objectKey);
        var response = send(request("GET", key).GET().build());
        final byte[] data;
        try (var body = response.body()) {
            if (response.statusCode() != 200) throw failure("read", response.statusCode());
            data = body.readNBytes((int) MAX_BYTES + 1);
        } catch (IOException exception) {
            throw new ArtifactStore.StorageException("could not read artifact HTTP response", exception);
        }
        if (data.length > MAX_BYTES) {
            throw new ArtifactStore.StorageException("artifact HTTP response exceeds store limit");
        }
        var expected = digestFromKey(key);
        if (!sha256(data).equalsIgnoreCase(expected)) {
            throw new ArtifactStore.StorageException("artifact HTTP response SHA-256 mismatch");
        }
        return data;
    }

    @Override
    public InputStream open(String objectKey) {
        var key = normalizeKey(objectKey);
        var response = send(request("GET", key).timeout(streamTimeout).GET().build(), streamTimeout);
        if (response.statusCode() != 200) {
            closeQuietly(response.body());
            throw failure("read", response.statusCode());
        }
        return response.body();
    }

    @Override
    public void delete(String objectKey) {
        var key = normalizeKey(objectKey);
        var response = send(request("DELETE", key).DELETE().build());
        try {
            if (response.statusCode() != 200 && response.statusCode() != 202
                    && response.statusCode() != 204 && response.statusCode() != 404) {
                throw failure("delete", response.statusCode());
            }
        } finally {
            closeQuietly(response.body());
        }
    }

    /** Remote gateways own enumeration and lifecycle; no unbounded scan here. */
    @Override
    public int sweepOrphans(Set<String> referencedKeys, Instant olderThan, int limit) {
        return 0;
    }

    @Override
    public String backend() {
        return "http";
    }

    private HttpRequest.Builder request(String method, String key) {
        var builder = HttpRequest.newBuilder(objectUriFor(key)).timeout(timeout)
                .header("Accept", "application/octet-stream")
                .header("X-RCM-Object-Key", key);
        if (!token.isBlank()) builder.header("Authorization", "Bearer " + token);
        return builder;
    }

    private HttpResponse<InputStream> send(HttpRequest request) {
        return send(request, timeout);
    }

    private HttpResponse<InputStream> send(HttpRequest request, Duration waitTimeout) {
        try {
            return client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                    .get(waitTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception exception) {
            throw new ArtifactStore.StorageException("artifact HTTP request failed", exception);
        }
    }

    private URI objectUriFor(String key) {
        return baseUrl.resolve("v1/objects/" + URLEncoder.encode(normalizeKey(key), StandardCharsets.UTF_8)
                .replace("+", "%20"));
    }

    private static String normalizeKey(String value) {
        var key = ArtifactStore.normalizeKey(value);
        if (!key.startsWith(PREFIX) || key.length() > 512 || key.contains("..")
                || key.indexOf('\\') >= 0 || key.indexOf('\r') >= 0 || key.indexOf('\n') >= 0) {
            throw new ArtifactStore.StorageException("unsafe artifact HTTP object key");
        }
        return key;
    }

    private static String digestFromKey(String key) {
        var name = key.substring(key.lastIndexOf('/') + 1);
        if (!name.endsWith(".blob")) throw new ArtifactStore.StorageException("invalid artifact HTTP object key");
        var digest = name.substring(0, name.length() - ".blob".length());
        if (!digest.matches("(?i)[0-9a-f]{64}")) throw new ArtifactStore.StorageException("invalid artifact HTTP object digest");
        return digest.toLowerCase();
    }

    private static String digest(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new ArtifactStore.StorageException("SHA-256 is unavailable", exception);
        }
    }

    private static ArtifactStore.StorageException failure(String operation, int status) {
        return new ArtifactStore.StorageException("artifact HTTP " + operation + " returned status " + status);
    }

    private static void closeQuietly(InputStream body) {
        if (body == null) return;
        try {
            body.close();
        } catch (IOException ignored) {
            // The response status already determines PUT/DELETE success. A
            // close failure must not turn a completed object operation into a
            // retry that could duplicate a write.
        }
    }

    private static boolean isLoopback(String host) {
        var value = host == null ? "" : host.toLowerCase();
        return "localhost".equals(value) || "127.0.0.1".equals(value) || "::1".equals(value) || "[::1]".equals(value);
    }
}
