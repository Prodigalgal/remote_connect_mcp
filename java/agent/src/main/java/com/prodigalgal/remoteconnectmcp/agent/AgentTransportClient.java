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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Base64;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Asynchronous v1 HTTPS client used by the Java Agent runtime. */
public final class AgentTransportClient implements AgentTransport {
    private static final Duration DEFAULT_TRANSFER_STALL_TIMEOUT = Duration.ofMinutes(2);
    private static final int TRANSFER_CHUNK_BYTES = 8 * 1024 * 1024;
    private static final java.util.concurrent.ExecutorService TRANSFER_READ_EXECUTOR =
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient http;
    private final URI centerUrl;
    private final Duration requestTimeout;
    private final Duration transferTimeout;
    private final Duration transferStallTimeout;
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

    public AgentTransportClient(URI centerUrl, long longPollSeconds, Duration transferTimeout,
                                Duration transferStallTimeout) {
        this(centerUrl, defaultHttpClient(), Duration.ofSeconds(30), longPollSeconds, transferTimeout,
                transferStallTimeout);
    }

    AgentTransportClient(URI centerUrl, HttpClient http, Duration requestTimeout) {
        this(centerUrl, http, requestTimeout, 0L, Duration.ofMinutes(30));
    }

    AgentTransportClient(URI centerUrl, HttpClient http, Duration requestTimeout, long longPollSeconds) {
        this(centerUrl, http, requestTimeout, longPollSeconds, Duration.ofMinutes(30));
    }

    AgentTransportClient(URI centerUrl, HttpClient http, Duration requestTimeout, long longPollSeconds,
                         Duration transferTimeout) {
        this(centerUrl, http, requestTimeout, longPollSeconds, transferTimeout, DEFAULT_TRANSFER_STALL_TIMEOUT);
    }

    AgentTransportClient(URI centerUrl, HttpClient http, Duration requestTimeout, long longPollSeconds,
                         Duration transferTimeout, Duration transferStallTimeout) {
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
        if (transferStallTimeout == null || transferStallTimeout.isZero() || transferStallTimeout.isNegative()
                || transferStallTimeout.compareTo(Duration.ofSeconds(5)) < 0
                || transferStallTimeout.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("transferStallTimeout must be between 5 seconds and 1 hour");
        }
        this.transferStallTimeout = transferStallTimeout;
        if (longPollSeconds < 1 || longPollSeconds > 25) {
            throw new IllegalArgumentException("longPollSeconds must be between 1 and 25");
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
        var endpoint = URI.create(centerUrl.resolve("/agent/v1/poll").toString()
                + "?wait_ms=" + (longPollSeconds * 1000L));
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
    public void updateState(String machineId, String token, String taskId, int attempt,
                            TaskUpdateRequest update) throws IOException, InterruptedException {
        var builder = newRequest(centerUrl.resolve("/agent/v1/tasks/" + encodePath(taskId) + "/state"))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + token)
                .header("X-Machine-ID", machineId)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
        addAttemptHeader(builder, attempt);
        var request = builder.POST(HttpRequest.BodyPublishers.ofByteArray(JsonCodec.write(update))).build();
        var response = send(request);
        if (response.statusCode() != 200) {
            throw new CenterTransportException("center task state update failed", response.statusCode());
        }
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
                                 long expectedBytes, String expectedSha256, int attempt, boolean overwrite)
            throws IOException, InterruptedException {
        if (transferId == null || transferId.isBlank() || destination == null
                || expectedBytes < 0 || expectedBytes > 4L * 1024 * 1024 * 1024
                || expectedSha256 == null || !expectedSha256.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid file transfer download metadata");
        }
        var target = destination.toAbsolutePath().normalize();
        var parent = target.getParent();
        if (parent == null) throw new IOException("destination has no parent");
        Files.createDirectories(parent);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !overwrite) {
            throw new IOException("destination already exists and overwrite is false");
        }
        // The partial name is stable across task retries.  A dropped HTTP
        // stream therefore resumes the same bytes instead of allocating a
        // fresh multi-GB temporary file on every retry.
        var safeTransferId = transferId.replaceAll("[^A-Za-z0-9._-]", "_");
        var temporary = parent.resolve(".rcm-part-" + target.getFileName() + "." + safeTransferId);
        // Never resume through a symlink planted next to the destination.  A
        // partial file is an Agent-owned implementation detail and must stay a
        // regular file even if the target directory is writable by another
        // process.
        if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(temporary, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(temporary);
        }
        long offset = Files.isRegularFile(temporary, LinkOption.NOFOLLOW_LINKS) ? Files.size(temporary) : 0L;
        if (offset > expectedBytes) {
            Files.deleteIfExists(temporary);
            offset = 0L;
        }
        if (offset == expectedBytes && Files.isRegularFile(temporary, LinkOption.NOFOLLOW_LINKS)) {
            var existingSha = digestFile(temporary);
            if (existingSha.equalsIgnoreCase(expectedSha256)) {
                moveIntoPlace(temporary, target, overwrite);
                return;
            }
            Files.deleteIfExists(temporary);
            offset = 0L;
        }

        var response = requestDownload(machineId, token, transferId, expectedBytes, expectedSha256, attempt, offset);
        try (var input = response.body()) {
            if (response.statusCode() != (offset > 0 ? 206 : 200)) {
                throw new CenterTransportException("center file transfer download failed", response.statusCode());
            }
            if (offset > 0) validateContentRange(response, offset, expectedBytes);
            var digest = MessageDigest.getInstance("SHA-256");
            if (offset > 0) digestFileInto(temporary, digest);
            var buffer = new byte[1024 * 1024];
            long count = offset;
            var deadlineNanos = System.nanoTime() + transferTimeout.toNanos();
            var openOptions = offset > 0
                    ? new StandardOpenOption[] { StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND }
                    : new StandardOpenOption[] { StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING };
            var outputOptions = new java.nio.file.OpenOption[openOptions.length + 1];
            outputOptions[0] = LinkOption.NOFOLLOW_LINKS;
            System.arraycopy(openOptions, 0, outputOptions, 1, openOptions.length);
            try (var output = Files.newOutputStream(temporary, outputOptions)) {
                int read;
                while ((read = readWithWatchdog(input, buffer, deadlineNanos)) >= 0) {
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
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
    }

    @Override
    public void updateProgress(String machineId, String token, String taskId, int attempt,
                               com.prodigalgal.remoteconnectmcp.protocol.TaskProgressUpdate progress)
            throws IOException, InterruptedException {
        var builder = newRequest(centerUrl.resolve("/agent/v1/tasks/" + encodePath(taskId) + "/progress"))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + token)
                .header("X-Machine-ID", machineId)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
        addAttemptHeader(builder, attempt);
        var request = builder.POST(HttpRequest.BodyPublishers.ofByteArray(JsonCodec.write(progress))).build();
        var response = send(request);
        if (response.statusCode() != 200) {
            throw new CenterTransportException("center task progress update failed", response.statusCode());
        }
    }

    private HttpResponse<InputStream> requestDownload(String machineId, String token, String transferId,
                                                       long expectedBytes, String expectedSha256, int attempt,
                                                       long offset) throws IOException, InterruptedException {
        var endpoint = centerUrl.resolve("/agent/v1/transfers/" + encodePath(transferId) + "/content");
        var builder = newRequest(endpoint).timeout(transferTimeout)
                .header("Authorization", "Bearer " + token)
                .header("X-Machine-ID", machineId)
                .header("Accept", "application/octet-stream")
                .header("X-RCM-Expected-Bytes", Long.toString(expectedBytes))
                .header("X-RCM-Expected-SHA256", expectedSha256.toLowerCase());
        if (offset > 0) builder.header("Range", "bytes=" + offset + "-");
        addAttemptHeader(builder, attempt);
        var responseFuture = http.sendAsync(builder.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        try {
            var response = responseFuture.get(transferTimeout.toMillis(), TimeUnit.MILLISECONDS);
            observeTransport(response);
            return response;
        } catch (TimeoutException exception) {
            responseFuture.cancel(true);
            throw new IOException("file transfer download timed out", exception);
        } catch (ExecutionException exception) {
            var cause = exception.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException("file transfer download failed", cause == null ? exception : cause);
        }
    }

    private static void validateContentRange(HttpResponse<?> response, long offset, long total) throws IOException {
        var value = response.headers().firstValue("Content-Range").orElse("").trim();
        var expectedPrefix = "bytes " + offset + "-";
        if (!value.startsWith(expectedPrefix) || !value.endsWith("/" + total)) {
            throw new IOException("center returned an invalid resumable content range");
        }
    }

    private static String digestFile(Path path) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digestFileInto(path, digest);
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
    }

    private static void digestFileInto(Path path, MessageDigest digest) throws IOException {
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS, StandardOpenOption.READ)) {
            var buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
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
        if (transferId == null || transferId.isBlank() || source == null
                || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                || expectedBytes < 0 || expectedBytes > 4L * 1024 * 1024 * 1024) {
            throw new IllegalArgumentException("invalid file transfer upload metadata");
        }
        var actualBytes = Files.size(source);
        if (actualBytes != expectedBytes) throw new IOException("source file size changed before upload");
        var resume = queryTransferResume(machineId, token, transferId, attempt);
        var resumeOffset = resume.offset();
        if (resumeOffset < 0) throw new IOException("center does not expose the required resumable transfer endpoint");
        if (resumeOffset > expectedBytes) throw new IOException("center resume offset exceeds source size");
        if (resumeOffset == expectedBytes) {
            if ("delivered".equalsIgnoreCase(resume.status())) {
                // The Center commits the metadata atomically with the final
                // chunk.  A lost response can therefore be recovered by HEAD
                // alone without retransmitting the complete file.
                return new com.prodigalgal.remoteconnectmcp.protocol.FileTransferResponse(
                        transferId, "", "delivered", expectedBytes,
                        expectedSha256 == null ? "" : expectedSha256.toLowerCase(), null);
            }
            throw new IOException("center transfer is at the expected size but has not reached delivered state");
        }
        var offset = resumeOffset;
        while (offset < expectedBytes) {
            var size = (int) Math.min(TRANSFER_CHUNK_BYTES, expectedBytes - offset);
            var chunk = readChunk(source, offset, size);
            if (chunk.length != size) throw new IOException("source file ended during resumable upload");
            var end = offset + chunk.length - 1;
            var endpoint = centerUrl.resolve("/agent/v1/transfers/" + encodePath(transferId) + "/content");
            var builder = newRequest(endpoint).timeout(transferTimeout)
                    .header("Authorization", "Bearer " + token)
                    .header("X-Machine-ID", machineId)
                    .header("Content-Type", mimeType == null || mimeType.isBlank() ? "application/octet-stream" : mimeType)
                    .header("X-RCM-File-Name", fileName == null ? source.getFileName().toString() : fileName)
                    .header("X-RCM-Expected-Bytes", Long.toString(expectedBytes))
                    .header("Content-Range", "bytes " + offset + "-" + end + "/" + expectedBytes)
                    .header("X-RCM-Transfer-Chunk", "1");
            if (expectedSha256 != null && expectedSha256.matches("(?i)[0-9a-f]{64}")) {
                builder.header("X-RCM-Expected-SHA256", expectedSha256.toLowerCase());
            }
            addAttemptHeader(builder, attempt);
            var response = sendTransfer(builder.PUT(HttpRequest.BodyPublishers.ofByteArray(chunk)).build());
            if (response.statusCode() != 200) {
                throw new CenterTransportException("center file transfer chunk upload failed", response.statusCode());
            }
            var acknowledged = JsonCodec.read(response.body(), com.prodigalgal.remoteconnectmcp.protocol.FileTransferResponse.class);
            var next = acknowledged == null ? -1L : acknowledged.bytes();
            if ("delivered".equalsIgnoreCase(acknowledged == null ? "" : acknowledged.status())) return acknowledged;
            if (next <= offset || next > expectedBytes) throw new IOException("center returned an invalid resumable offset");
            offset = next;
        }
        return new com.prodigalgal.remoteconnectmcp.protocol.FileTransferResponse(
                transferId, "", "delivered", expectedBytes,
                expectedSha256 == null ? "" : expectedSha256.toLowerCase(), null);
    }

    @Override
    public AgentTransport.TransferResume queryTransferResume(String machineId, String token, String transferId, int attempt)
            throws IOException, InterruptedException {
        var endpoint = centerUrl.resolve("/agent/v1/transfers/" + encodePath(transferId) + "/content");
        var builder = newRequest(endpoint).timeout(requestTimeout)
                .header("Authorization", "Bearer " + token)
                .header("X-Machine-ID", machineId)
                .header("Accept", "application/json");
        addAttemptHeader(builder, attempt);
        var response = send(builder.method("HEAD", HttpRequest.BodyPublishers.noBody()).build());
        if (response.statusCode() != 200) {
            throw new CenterTransportException("center file transfer resume probe failed", response.statusCode());
        }
        var value = response.headers().firstValue("X-RCM-Resume-Offset").orElse("").trim();
        if (value.isBlank()) throw new IOException("center did not return a resumable transfer offset");
        try {
            var offset = Long.parseLong(value);
            if (offset < 0) throw new NumberFormatException("negative offset");
            var status = response.headers().firstValue("X-RCM-Transfer-Status").orElse("").trim();
            return new AgentTransport.TransferResume(offset, status);
        } catch (NumberFormatException exception) {
            throw new IOException("center returned an invalid resumable offset", exception);
        }
    }

    private static byte[] readChunk(Path source, long offset, int size) throws IOException {
        var data = new byte[size];
        try (var input = Files.newByteChannel(source, LinkOption.NOFOLLOW_LINKS, StandardOpenOption.READ)) {
            input.position(offset);
            var view = java.nio.ByteBuffer.wrap(data);
            while (view.hasRemaining()) {
                var read = input.read(view);
                if (read < 0) break;
            }
            if (view.position() != size) return java.util.Arrays.copyOf(data, view.position());
            return data;
        }
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
        if (attempt < 1) throw new IllegalArgumentException("attempt must be positive");
        builder.header("X-Task-Attempt", Integer.toString(attempt));
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

    /**
     * Enforce a no-progress deadline after the Center response starts. The
     * absolute request timeout still bounds the whole transfer; this read
     * watchdog prevents a dead response body from retaining the Agent task.
     */
    private int readWithWatchdog(InputStream input, byte[] buffer, long deadlineNanos) throws IOException {
        var remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) throw new IOException("file transfer exceeded the maximum lifetime");
        var waitNanos = Math.min(remaining, transferStallTimeout.toNanos());
        var future = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return input.read(buffer);
            } catch (IOException exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        }, TRANSFER_READ_EXECUTOR);
        try {
            return future.get(waitNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            try { input.close(); } catch (IOException ignored) { }
            throw new IOException("file transfer stalled without progress", exception);
        } catch (InterruptedException exception) {
            future.cancel(true);
            try { input.close(); } catch (IOException ignored) { }
            Thread.currentThread().interrupt();
            throw new IOException("file transfer read interrupted", exception);
        } catch (ExecutionException exception) {
            var cause = exception.getCause();
            if (cause instanceof java.util.concurrent.CompletionException completion && completion.getCause() != null) {
                cause = completion.getCause();
            }
            if (cause instanceof IOException io) throw io;
            throw new IOException("file transfer read failed", cause == null ? exception : cause);
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

    private static void close(InputStream input) {
        if (input == null) return;
        try { input.close(); } catch (IOException ignored) { }
    }

    /**
     * Prefer HTTP/2 for the long-lived HTTPS path while allowing the JDK to
     * negotiate HTTP/1.1 when the endpoint advertises that protocol. QUIC/
     * HTTP/3 remains a separate explicit transport provider.
     */
    private static HttpClient defaultHttpClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }
}
