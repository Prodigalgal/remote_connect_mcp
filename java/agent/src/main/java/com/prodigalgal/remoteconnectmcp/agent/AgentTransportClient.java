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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
    private final long longPollSeconds;
    private final AtomicBoolean longPollHonored = new AtomicBoolean();
    private volatile String selectedTransport = TransportNegotiation.HTTPS;

    public AgentTransportClient(URI centerUrl) {
        this(centerUrl, defaultHttpClient(), Duration.ofSeconds(30), 0L);
    }

    public AgentTransportClient(URI centerUrl, long longPollSeconds) {
        this(centerUrl, defaultHttpClient(), Duration.ofSeconds(30), longPollSeconds);
    }

    AgentTransportClient(URI centerUrl, HttpClient http, Duration requestTimeout) {
        this(centerUrl, http, requestTimeout, 0L);
    }

    AgentTransportClient(URI centerUrl, HttpClient http, Duration requestTimeout, long longPollSeconds) {
        this.centerUrl = stripTrailingSlash(centerUrl);
        this.http = http;
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        this.requestTimeout = requestTimeout;
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
