package com.prodigalgal.remoteconnectmcp.agent;

import com.prodigalgal.remoteconnectmcp.protocol.JsonCodec;
import com.prodigalgal.remoteconnectmcp.protocol.ArtifactRequest;
import com.prodigalgal.remoteconnectmcp.protocol.ArtifactResponse;
import com.prodigalgal.remoteconnectmcp.protocol.PollRequest;
import com.prodigalgal.remoteconnectmcp.protocol.PollResponse;
import com.prodigalgal.remoteconnectmcp.protocol.OutputResponse;
import com.prodigalgal.remoteconnectmcp.protocol.RegisterResponse;
import com.prodigalgal.remoteconnectmcp.protocol.TaskUpdateRequest;
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

/** Asynchronous v1 HTTPS client used by the Java Agent runtime. */
public final class AgentTransportClient implements AgentTransport {
    private final HttpClient http;
    private final URI centerUrl;
    private final Duration requestTimeout;

    public AgentTransportClient(URI centerUrl) {
        this(centerUrl, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), Duration.ofSeconds(30));
    }

    AgentTransportClient(URI centerUrl, HttpClient http, Duration requestTimeout) {
        this.centerUrl = stripTrailingSlash(centerUrl);
        this.http = http;
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        this.requestTimeout = requestTimeout;
    }

    public RegisterResponse register(AgentConfig config) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(centerUrl.resolve("/agent/v1/register"))
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
        var request = HttpRequest.newBuilder(centerUrl.resolve("/agent/v1/poll"))
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
        return JsonCodec.read(response.body(), PollResponse.class);
    }

    @Override
    public void updateState(String machineId, String token, String taskId, TaskUpdateRequest update) throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder(centerUrl.resolve("/agent/v1/tasks/" + encodePath(taskId) + "/state"))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + token)
                .header("X-Machine-ID", machineId)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
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
        var payload = new com.prodigalgal.remoteconnectmcp.protocol.OutputRequest(offset, Base64.getEncoder().encodeToString(data));
        var request = HttpRequest.newBuilder(centerUrl.resolve("/agent/v1/tasks/" + encodePath(taskId) + "/output"))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + token)
                .header("X-Machine-ID", machineId)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(JsonCodec.write(payload)))
                .build();
        var response = send(request);
        if (response.statusCode() != 200) {
            throw new CenterTransportException("center task output update failed", response.statusCode());
        }
        return JsonCodec.read(response.body(), OutputResponse.class);
    }

    @Override
    public ArtifactResponse appendArtifact(String machineId, String token, String taskId, String mimeType, String sha256, byte[] data) throws IOException, InterruptedException {
        var payload = new ArtifactRequest(mimeType, sha256, Base64.getEncoder().encodeToString(data));
        var request = HttpRequest.newBuilder(centerUrl.resolve("/agent/v1/tasks/" + encodePath(taskId) + "/artifact"))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + token)
                .header("X-Machine-ID", machineId)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(JsonCodec.write(payload)))
                .build();
        var response = send(request);
        if (response.statusCode() != 200) {
            throw new CenterTransportException("center task artifact update failed", response.statusCode());
        }
        return JsonCodec.read(response.body(), ArtifactResponse.class);
    }

    @Override
    public void reportUpgrade(String machineId, String token, UpgradeStatusRequest update)
            throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(centerUrl.resolve("/agent/v1/upgrade/status"))
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

    /**
     * Use HttpClient's asynchronous pipeline for every Agent request. The
     * caller may wait on the returned stage, but it is always a virtual thread
     * (or a short-lived one-shot registration thread), so a slow Center never
     * pins a platform thread or the Agent poll loop's carrier.
     */
    private HttpResponse<byte[]> send(HttpRequest request) throws IOException, InterruptedException {
        var future = http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
        try {
            return future.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
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
}
