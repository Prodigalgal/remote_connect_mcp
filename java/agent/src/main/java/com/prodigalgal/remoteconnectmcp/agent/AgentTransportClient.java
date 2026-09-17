package com.prodigalgal.remoteconnectmcp.agent;

import com.prodigalgal.remoteconnectmcp.protocol.JsonCodec;
import com.prodigalgal.remoteconnectmcp.protocol.ArtifactRequest;
import com.prodigalgal.remoteconnectmcp.protocol.ArtifactResponse;
import com.prodigalgal.remoteconnectmcp.protocol.PollRequest;
import com.prodigalgal.remoteconnectmcp.protocol.PollResponse;
import com.prodigalgal.remoteconnectmcp.protocol.OutputResponse;
import com.prodigalgal.remoteconnectmcp.protocol.RegisterResponse;
import com.prodigalgal.remoteconnectmcp.protocol.TaskUpdateRequest;
import com.prodigalgal.remoteconnectmcp.protocol.TransportNegotiation;
import com.prodigalgal.remoteconnectmcp.protocol.UpgradeStatusRequest;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.Base64;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Asynchronous v1 HTTPS client used by the Java Agent runtime. */
public final class AgentTransportClient implements AgentTransport {
    private final HttpClient http;
    private final URI centerUrl;
    private final Duration requestTimeout;
    private final Duration transferTimeout;
    private final long longPollSeconds;
    private final AtomicBoolean longPollHonored = new AtomicBoolean();
    private volatile String selectedTransport = TransportNegotiation.HTTPS;

    public AgentTransportClient(URI centerUrl) {
        this(centerUrl, defaultHttpClient(), Duration.ofSeconds(30), 0L, Duration.ofMinutes(30));
    }

    public AgentTransportClient(URI centerUrl, long longPollSeconds) {
        this(centerUrl, defaultHttpClient(), Duration.ofSeconds(30), longPollSeconds, Duration.ofMinutes(30));
    }

    public AgentTransportClient(URI centerUrl, long longPollSeconds, Duration transferTimeout) {
        this(centerUrl, defaultHttpClient(), Duration.ofSeconds(30), longPollSeconds, transferTimeout);
    }

    AgentTransportClient(URI centerUrl, HttpClient http, Duration requestTimeout) {
        this(centerUrl, http, requestTimeout, 0L, Duration.ofMinutes(30));
    }

    AgentTransportClient(URI centerUrl, HttpClient http, Duration requestTimeout, long longPollSeconds) {
        this(centerUrl, http, requestTimeout, longPollSeconds, Duration.ofMinutes(30));
    }

    AgentTransportClient(URI centerUrl, HttpClient http, Duration requestTimeout, long longPollSeconds,
                         Duration transferTimeout) {
        this.centerUrl = stripTrailingSlash(centerUrl);
        this.http = http;
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        this.requestTimeout = requestTimeout;
        if (transferTimeout == null || transferTimeout.isZero() || transferTimeout.isNegative()
                || transferTimeout.compareTo(Duration.ofSeconds(30)) < 0
                || transferTimeout.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("transferTimeout must be between 30 seconds and 24 hours");
        }
        this.transferTimeout = transferTimeout;
        if (longPollSeconds < 0 || longPollSeconds > 25) {
            throw new IllegalArgumentException("longPollSeconds must be between 0 and 25");
        }
        this.longPollSeconds = longPollSeconds;
    }

    public RegisterResponse register(AgentConfig config) throws IOException, InterruptedException {
        var request = newRequest(centerUrl.resolve("/agent/v1/register"))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + config.enrollmentToken())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(JsonCodec.write(config.registerRequest())))
                .build();
        var response = send(request);
        if (response.statusCode() != 201) {
            throw new CenterTransportException("center registration failed", response.statusCode());
        }
        return JsonCodec.read(response.body(), RegisterResponse.class);
    }

    public PollResponse poll(String machineId, String token, PollRequest poll) throws IOException, InterruptedException {
        var endpoint = centerUrl.resolve("/agent/v1/poll");
        if (longPollSeconds > 0) endpoint = URI.create(endpoint + "?wait_ms=" + (longPollSeconds * 1000L));
        var request = newRequest(endpoint)
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + token)
                .header("X-Machine-ID", machineId)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(JsonCodec.write(poll)))
                .build();
        var response = send(request);
        if (response.statusCode() != 200) {
            throw new CenterTransportException("center poll failed", response.statusCode());
        }
        longPollHonored.set(response.headers().firstValue("X-RCM-Long-Poll")
                .map(value -> "accepted".equalsIgnoreCase(value.trim())).orElse(false));
        return JsonCodec.read(response.body(), PollResponse.class);
    }

    @Override
    public boolean longPollHonored() {
        return longPollHonored.get();
    }

    @Override
    public String selectedTransport() {
        return selectedTransport;
    }

    @Override
    public void updateState(String machineId, String token, String taskId, TaskUpdateRequest update) throws IOException, InterruptedException {
        updateState(machineId, token, taskId, 0, update);
    }

    @Override
    public void updateState(String machineId, String token, String taskId, int attempt,
                            TaskUpdateRequest update) throws IOException, InterruptedException {
        var builder = newRequest(centerUrl.resolve("/agent/v1/tasks/" + encodePath(taskId) + "/state"))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + token)
                .header("X-Machine-ID", machineId)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
        addAttemptHeader(builder, attempt);
        var payload = update;
        if (update != null && Boolean.TRUE.equals(update.outputTruncated())) {
            // Older Centers reject fields they do not know. Carry this advisory
            // bit in a header and keep the JSON body compatible with them.
            builder.header("X-Task-Output-Truncated", "1");
            payload = new TaskUpdateRequest(update.status(), update.exitCode(), update.error(),
                    update.startedAt(), update.finishedAt(), false);
        }
        var request = builder.POST(HttpRequest.BodyPublishers.ofByteArray(JsonCodec.write(payload))).build();
        var response = send(request);
        if (response.statusCode() != 200) {
            throw new CenterTransportException("center task state update failed", response.statusCode());
        }
    }

    @Override
    public OutputResponse appendOutput(String machineId, String token, String taskId, long offset, byte[] data) throws IOException, InterruptedException {
        return appendOutput(machineId, token, taskId, 0, offset, data);
    }

    @Override
    public OutputResponse appendOutput(String machineId, String token, String taskId, int attempt,
                                       long offset, byte[] data) throws IOException, InterruptedException {
        var payload = new com.prodigalgal.remoteconnectmcp.protocol.OutputRequest(offset, Base64.getEncoder().encodeToString(data));
        var builder = newRequest(centerUrl.resolve("/agent/v1/tasks/" + encodePath(taskId) + "/output"))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + token)
                .header("X-Machine-ID", machineId)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
        addAttemptHeader(builder, attempt);
        var request = builder.POST(HttpRequest.BodyPublishers.ofByteArray(JsonCodec.write(payload))).build();
        var response = send(request);
        if (response.statusCode() != 200) {
            throw new CenterTransportException("center task output update failed", response.statusCode());
        }
        return JsonCodec.read(response.body(), OutputResponse.class);
    }

    @Override
    public ArtifactResponse appendArtifact(String machineId, String token, String taskId, String mimeType, String sha256, byte[] data) throws IOException, InterruptedException {
        return appendArtifact(machineId, token, taskId, 0, mimeType, sha256, data);
    }

    @Override
    public ArtifactResponse appendArtifact(String machineId, String token, String taskId, int attempt,
                                           String mimeType, String sha256, byte[] data) throws IOException, InterruptedException {
        var payload = new ArtifactRequest(mimeType, sha256, Base64.getEncoder().encodeToString(data));
        var builder = newRequest(centerUrl.resolve("/agent/v1/tasks/" + encodePath(taskId) + "/artifact"))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + token)
                .header("X-Machine-ID", machineId)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
        addAttemptHeader(builder, attempt);
        var request = builder.POST(HttpRequest.BodyPublishers.ofByteArray(JsonCodec.write(payload))).build();
        var response = send(request);
        if (response.statusCode() != 200) {
            throw new CenterTransportException("center task artifact update failed", response.statusCode());
        }
        return JsonCodec.read(response.body(), ArtifactResponse.class);
    }

    @Override
    public void downloadTransfer(String machineId, String token, String transferId, Path destination,
                                 long expectedBytes, String expectedSha256, int attempt)
            throws IOException, InterruptedException {
        downloadTransfer(machineId, token, transferId, destination, expectedBytes, expectedSha256, attempt, true);
    }

    @Override
    public void downloadTransfer(String machineId, String token, String transferId, Path destination,
                                 long expectedBytes, String expectedSha256, int attempt, boolean overwrite)
            throws IOException, InterruptedException {
        if (transferId == null || transferId.isBlank() || destination == null
                || expectedBytes < 0 || expectedBytes > 4L * 1024 * 1024 * 1024
                || expectedSha256 == null || !expectedSha256.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid file transfer download metadata");
        }
        var endpoint = centerUrl.resolve("/agent/v1/transfers/" + encodePath(transferId) + "/content");
        var builder = newRequest(endpoint).timeout(transferTimeout)
                .header("Authorization", "Bearer " + token)
                .header("X-Machine-ID", machineId)
                .header("Accept", "application/octet-stream")
                .header("X-RCM-Expected-Bytes", Long.toString(expectedBytes))
                .header("X-RCM-Expected-SHA256", expectedSha256.toLowerCase());
        addAttemptHeader(builder, attempt);
        var responseFuture = http.sendAsync(builder.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        HttpResponse<InputStream> response;
        try {
            response = responseFuture.get(transferTimeout.toMillis(), TimeUnit.MILLISECONDS);
            observeTransport(response);
        } catch (TimeoutException exception) {
            responseFuture.cancel(true);
            throw new IOException("file transfer download timed out", exception);
        } catch (ExecutionException exception) {
            var cause = exception.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException("file transfer download failed", cause == null ? exception : cause);
        }
        try (var input = response.body()) {
            if (response.statusCode() != 200) throw new CenterTransportException("center file transfer download failed", response.statusCode());
            var target = destination.toAbsolutePath().normalize();
            var parent = target.getParent();
            if (parent == null) throw new IOException("destination has no parent");
            Files.createDirectories(parent);
            var temporary = parent.resolve(".rcm-part-" + target.getFileName() + "." + UUID.randomUUID());
            try {
                var digest = MessageDigest.getInstance("SHA-256");
                var buffer = new byte[1024 * 1024];
                long count = 0;
                try (var output = Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        if (read == 0) continue;
                        count += read;
                        if (count > expectedBytes) throw new IOException("download exceeds declared size");
                        output.write(buffer, 0, read);
                        digest.update(buffer, 0, read);
                    }
                }
                var actual = HexFormat.of().formatHex(digest.digest());
                if (count != expectedBytes || !actual.equalsIgnoreCase(expectedSha256)) {
                    throw new IOException("download size or SHA-256 does not match transfer metadata");
                }
                moveIntoPlace(temporary, target, overwrite);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
    }

    private static void moveIntoPlace(Path temporary, Path target, boolean overwrite) throws IOException {
        if (overwrite) {
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return;
        }
        // Never pass REPLACE_EXISTING for overwrite=false.  The move itself
        // is the final existence check, so a file created during a long
        // download cannot be clobbered by a stale pre-flight Files.exists().
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target);
        } catch (java.nio.file.FileAlreadyExistsException exists) {
            throw new IOException("destination appeared during transfer and overwrite is false", exists);
        }
    }

    @Override
    public void acknowledgeTransfer(String machineId, String token, String transferId,
                                    com.prodigalgal.remoteconnectmcp.protocol.FileTransferResponse acknowledgement,
                                    int attempt) throws IOException, InterruptedException {
        var builder = newRequest(centerUrl.resolve("/agent/v1/transfers/" + encodePath(transferId) + "/ack"))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + token)
                .header("X-Machine-ID", machineId)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
        addAttemptHeader(builder, attempt);
        var request = builder.POST(HttpRequest.BodyPublishers.ofByteArray(JsonCodec.write(acknowledgement))).build();
        var response = send(request);
        if (response.statusCode() != 200) {
            throw new CenterTransportException("center file transfer acknowledgement failed", response.statusCode());
        }
    }

    @Override
    public com.prodigalgal.remoteconnectmcp.protocol.FileTransferResponse uploadTransfer(
            String machineId, String token, String transferId, Path source, String fileName,
            String mimeType, long expectedBytes, String expectedSha256, int attempt)
            throws IOException, InterruptedException {
        if (transferId == null || transferId.isBlank() || source == null || !Files.isRegularFile(source)
                || expectedBytes < 0 || expectedBytes > 4L * 1024 * 1024 * 1024) {
            throw new IllegalArgumentException("invalid file transfer upload metadata");
        }
        var actualBytes = Files.size(source);
        if (actualBytes != expectedBytes) throw new IOException("source file size changed before upload");
        var endpoint = centerUrl.resolve("/agent/v1/transfers/" + encodePath(transferId) + "/content");
        var builder = newRequest(endpoint).timeout(transferTimeout)
                .header("Authorization", "Bearer " + token)
                .header("X-Machine-ID", machineId)
                .header("Content-Type", mimeType == null || mimeType.isBlank() ? "application/octet-stream" : mimeType)
                .header("X-RCM-File-Name", fileName == null ? source.getFileName().toString() : fileName)
                // BodyPublishers.ofFile supplies the real Content-Length. The
                // JDK forbids callers from setting that restricted header.
                .header("X-RCM-Expected-Bytes", Long.toString(expectedBytes));
        if (expectedSha256 != null && expectedSha256.matches("(?i)[0-9a-f]{64}")) {
            builder.header("X-RCM-Expected-SHA256", expectedSha256.toLowerCase());
        }
        addAttemptHeader(builder, attempt);
        // A file body can legitimately take minutes or hours.  Do not send it
        // through the ordinary control-plane helper: that helper deliberately
        // uses the short request timeout used by poll/state/output calls.
        // The transfer helper waits on the same asynchronous HTTP pipeline
        // with the separately configured transfer timeout.
        var response = sendTransfer(builder.PUT(HttpRequest.BodyPublishers.ofFile(source)).build());
        if (response.statusCode() != 200) throw new CenterTransportException("center file transfer upload failed", response.statusCode());
        return JsonCodec.read(response.body(), com.prodigalgal.remoteconnectmcp.protocol.FileTransferResponse.class);
    }

    @Override
    public void reportUpgrade(String machineId, String token, UpgradeStatusRequest update)
            throws IOException, InterruptedException {
        var request = newRequest(centerUrl.resolve("/agent/v1/upgrade/status"))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + token)
                .header("X-Machine-ID", machineId)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(JsonCodec.write(update)))
                .build();
        var response = send(request);
        if (response.statusCode() != 200) {
            throw new CenterTransportException("center upgrade status update failed", response.statusCode());
        }
    }

    private static String encodePath(String value) {
        if (value == null || value.isBlank() || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0 || value.indexOf('?') >= 0 || value.indexOf('#') >= 0) {
            throw new IllegalArgumentException("invalid task id");
        }
        return value;
    }

    private static void addAttemptHeader(HttpRequest.Builder builder, int attempt) {
        if (attempt < 0) throw new IllegalArgumentException("attempt must be non-negative");
        if (attempt > 0) builder.header("X-Task-Attempt", Integer.toString(attempt));
    }

    /**
     * Use HttpClient's asynchronous pipeline for every Agent request. The
     * caller may wait on the returned stage, but it is always a virtual thread
     * (or a short-lived one-shot registration thread), so a slow Center never
     * pins a platform thread or the Agent poll loop's carrier.
     */
    private HttpResponse<byte[]> send(HttpRequest request) throws IOException, InterruptedException {
        var future = http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
        try {
            var response = future.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
            observeTransport(response);
            return response;
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new IOException("center request timed out after " + requestTimeout, exception);
        } catch (ExecutionException exception) {
            var cause = exception.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException("center request failed", cause == null ? exception : cause);
        }
    }

    /**
     * Wait for a streaming file upload/download using the transfer deadline,
     * never the short control-plane request deadline.
     */
    private HttpResponse<byte[]> sendTransfer(HttpRequest request) throws IOException, InterruptedException {
        var future = http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
        try {
            var response = future.get(transferTimeout.toMillis(), TimeUnit.MILLISECONDS);
            observeTransport(response);
            return response;
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new IOException("file transfer request timed out after " + transferTimeout, exception);
        } catch (ExecutionException exception) {
            var cause = exception.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException("file transfer request failed", cause == null ? exception : cause);
        }
    }

    private static URI stripTrailingSlash(URI uri) {
        var value = uri.toString().replaceAll("/+$", "");
        return URI.create(value + "/");
    }

    private static HttpRequest.Builder newRequest(URI endpoint) {
        return HttpRequest.newBuilder(endpoint)
                .header(TransportNegotiation.HEADER_CAPABILITIES, TransportNegotiation.AGENT_CAPABILITIES)
                .header(TransportNegotiation.HEADER_PREFERRED, TransportNegotiation.HTTPS);
    }

    private void observeTransport(HttpResponse<?> response) {
        var selected = response.headers().firstValue(TransportNegotiation.HEADER_SELECTED)
                .map(value -> value.trim().toLowerCase(java.util.Locale.ROOT))
                .filter(value -> !value.isBlank() && value.length() <= 32)
                .orElse(response.version() == HttpClient.Version.HTTP_2 ? "https+h2" : "https+h1");
        selectedTransport = selected;
    }

    /**
     * Prefer HTTP/2 for the long-lived HTTPS path while retaining the JDK's
     * negotiated HTTP/1.1 fallback.  This is the safe baseline measured by
     * the transport probe; QUIC/HTTP3 remains an explicit future provider and
     * never becomes a hidden hard dependency of the Agent.
     */
    private static HttpClient defaultHttpClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }
}
