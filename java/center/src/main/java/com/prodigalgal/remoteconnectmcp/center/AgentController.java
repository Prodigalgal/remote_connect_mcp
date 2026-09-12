package com.prodigalgal.remoteconnectmcp.center;

import com.prodigalgal.remoteconnectmcp.protocol.PollRequest;
import com.prodigalgal.remoteconnectmcp.protocol.PollResponse;
import com.prodigalgal.remoteconnectmcp.protocol.RegisterRequest;
import com.prodigalgal.remoteconnectmcp.protocol.RegisterResponse;
import com.prodigalgal.remoteconnectmcp.protocol.OutputRequest;
import com.prodigalgal.remoteconnectmcp.protocol.TaskUpdateRequest;
import com.prodigalgal.remoteconnectmcp.protocol.ArtifactRequest;
import com.prodigalgal.remoteconnectmcp.protocol.UpgradeStatusRequest;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import java.time.Duration;

@RestController
@RequestMapping("/agent/v1")
public final class AgentController {
    private static final int MAX_OUTPUT_REQUEST_BASE64 = 256 * 1024;
    private static final int MAX_ARTIFACT_REQUEST_BASE64 = 12 * 1024 * 1024;
    private static final long MAX_LONG_POLL_MS = 25_000L;
    private final AgentRegistry registry;
    private final TaskService tasks;
    private final UpgradeService upgrades;
    private final AgentConfigurationService configurations;
    private final CenterAsyncExecutor async;
    private final AgentWakeRegistry wakes;

    @Autowired
    public AgentController(AgentRegistry registry, TaskService tasks, UpgradeService upgrades,
                           AgentConfigurationService configurations, CenterAsyncExecutor async,
                           org.springframework.beans.factory.ObjectProvider<AgentWakeRegistry> wakeProvider) {
        this.registry = registry;
        this.tasks = tasks;
        this.upgrades = upgrades;
        this.configurations = configurations;
        this.async = async;
        this.wakes = wakeProvider == null ? null : wakeProvider.getIfAvailable();
    }

    /** Compatibility constructor for direct protocol/controller tests. */
    AgentController(AgentRegistry registry, TaskService tasks, UpgradeService upgrades,
                    AgentConfigurationService configurations, CenterAsyncExecutor async) {
        this(registry, tasks, upgrades, configurations, async, null);
    }

    @PostMapping("/register")
    public CompletableFuture<ResponseEntity<?>> register(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                          @RequestBody(required = false) RegisterRequest request) {
        return execute(() -> {
            RegisterResponse response = registry.register(request, bearerValue(authorization));
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        });
    }

    @PostMapping("/poll")
    public CompletableFuture<ResponseEntity<?>> poll(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                     @RequestHeader(value = "X-Machine-ID", required = false) String machineId,
                                                     @RequestBody(required = false) PollRequest request,
                                                     @RequestParam(value = "wait_ms", defaultValue = "0") long waitMs) {
        return execute(() -> {
            var pollRequest = request == null ? new PollRequest(java.util.List.of(), 0, java.util.List.of()) : request;
            var normalizedWait = normalizeLongPoll(waitMs);
            var response = pollUntilChange(machineId, bearerValue(authorization), pollRequest, normalizedWait);
            if (normalizedWait > 0 && wakes != null) {
                return ResponseEntity.ok().header("X-RCM-Long-Poll", "accepted").body(response);
            }
            return ResponseEntity.ok(response);
        });
    }

    private PollResponse pollUntilChange(String machineId, String token, PollRequest request, long waitMs)
            throws Exception {
        if (waitMs <= 0 || wakes == null) {
            return pollOnce(machineId, token, request);
        }
        var deadline = System.nanoTime() + Duration.ofMillis(waitMs).toNanos();
        while (true) {
            // Capture before the row read. If a task is created between the
            // read and await, the sequence has changed and await returns
            // immediately instead of losing the wake-up race.
            var observed = wakes.version(machineId);
            try {
                var response = pollOnce(machineId, token, request);
                if (hasWork(response)) return response;
                var remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    // A missed wake must not make the deadline return the
                    // snapshot from before the event. One final authoritative
                    // poll is tied to this request boundary, not a timer loop.
                    return pollOnce(machineId, token, request);
                }
                wakes.awaitChange(machineId, observed, remaining);
                if (System.nanoTime() >= deadline) {
                    // Same deadline re-read for a notification lost in the
                    // database/listener path.
                    return pollOnce(machineId, token, request);
                }
            } finally {
                // Wait-state references are per request, not a machine cache.
                // Releasing here lets a removed/offline machine disappear
                // without a periodic cleanup sweep.
                wakes.release(machineId);
            }
        }
    }

    private PollResponse pollOnce(String machineId, String token, PollRequest request) {
        registry.poll(machineId, token, request);
        var config = configurations.current(machineId);
        if (config != null && config.generation() <= request.configGeneration()) config = null;
        // Fetch cancellation requests without claiming a new task. This keeps
        // upgrade offers ahead of queued work and guarantees a remote cancel
        // is observable even when the Agent has no free execution slot.
        var cancellations = tasks.poll(machineId, new PollRequest(request.runningTaskIds(), 0,
                request.availableCapabilities(), request.metadata(), request.configGeneration()));
        if (!cancellations.cancelTaskIds().isEmpty()) {
            return new PollResponse(cancellations.task(), cancellations.cancelTaskIds(), cancellations.upgrade(), config);
        }
        var upgrade = upgrades.offer(machineId, request);
        if (upgrade != null) return new PollResponse(null, java.util.List.of(), upgrade, config);
        var response = tasks.poll(machineId, request);
        return new PollResponse(response.task(), response.cancelTaskIds(), response.upgrade(), config);
    }

    private static boolean hasWork(PollResponse response) {
        return response != null && (response.task() != null || response.upgrade() != null
                || !response.cancelTaskIds().isEmpty() || response.config() != null);
    }

    private static long normalizeLongPoll(long waitMs) {
        if (waitMs < 0 || waitMs > MAX_LONG_POLL_MS) {
            throw new IllegalArgumentException("wait_ms must be between 0 and " + MAX_LONG_POLL_MS);
        }
        return waitMs;
    }

    @PostMapping("/upgrade/status")
    public CompletableFuture<ResponseEntity<?>> upgradeStatus(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                               @RequestHeader(value = "X-Machine-ID", required = false) String machineId,
                                                               @RequestBody(required = false) UpgradeStatusRequest request) {
        return execute(() -> {
            authenticate(machineId, authorization);
            return ResponseEntity.ok(upgrades.updateStatus(machineId, request));
        });
    }

    @PostMapping("/tasks/{taskId}/state")
    public CompletableFuture<ResponseEntity<?>> taskState(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                          @RequestHeader(value = "X-Machine-ID", required = false) String machineId,
                                                          @RequestHeader(value = "X-Task-Output-Truncated", required = false) String outputTruncated,
                                                          @PathVariable String taskId,
                                                          @RequestBody(required = false) TaskUpdateRequest request) {
        return execute(() -> {
            authenticate(machineId, authorization);
            var update = request;
            if (update != null && "1".equals(outputTruncated)) {
                update = new TaskUpdateRequest(update.status(), update.exitCode(), update.error(), update.startedAt(), update.finishedAt(), true);
            }
            return ResponseEntity.ok(tasks.updateState(machineId, taskId, update));
        });
    }

    @PostMapping("/tasks/{taskId}/output")
    public CompletableFuture<ResponseEntity<?>> taskOutput(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                           @RequestHeader(value = "X-Machine-ID", required = false) String machineId,
                                                           @PathVariable String taskId,
                                                           @RequestBody(required = false) OutputRequest request) {
        return execute(() -> {
            authenticate(machineId, authorization);
            if (request == null || request.data() == null) {
                throw new IllegalArgumentException("output body is required");
            }
            if (request.data().length() > MAX_OUTPUT_REQUEST_BASE64) {
                throw new IllegalArgumentException("output request exceeds 256 KiB");
            }
            var data = Base64.getDecoder().decode(request.data());
            return ResponseEntity.ok(tasks.appendOutput(machineId, taskId, request.offset(), data));
        });
    }

    @PostMapping("/tasks/{taskId}/artifact")
    public CompletableFuture<ResponseEntity<?>> taskArtifact(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                             @RequestHeader(value = "X-Machine-ID", required = false) String machineId,
                                                             @PathVariable String taskId,
                                                             @RequestBody(required = false) ArtifactRequest request) {
        return execute(() -> {
            authenticate(machineId, authorization);
            if (request == null || request.data() == null) {
                throw new IllegalArgumentException("artifact body is required");
            }
            if (request.data().length() > MAX_ARTIFACT_REQUEST_BASE64) {
                throw new IllegalArgumentException("artifact request exceeds 12 MiB");
            }
            var data = Base64.getDecoder().decode(request.data());
            return ResponseEntity.ok(tasks.appendArtifact(machineId, taskId, request.mimeType(), request.sha256(), data));
        });
    }

    private CompletableFuture<ResponseEntity<?>> execute(java.util.concurrent.Callable<ResponseEntity<?>> action) {
        return async.submit(action).exceptionally(failure -> error(unwrap(failure)));
    }

    private void authenticate(String machineId, String authorization) {
        if (!registry.acceptsAgent(machineId, bearerValue(authorization))) {
            throw new SecurityException("invalid agent credentials");
        }
    }

    private static String bearerValue(String authorization) {
        if (authorization == null) {
            return "";
        }
        var parts = authorization.trim().split("\\s+", 2);
        return parts.length == 2 && "Bearer".equalsIgnoreCase(parts[0]) ? parts[1].trim() : "";
    }

    private static ResponseEntity<Map<String, String>> error(Throwable exception) {
        if (exception instanceof SecurityException) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(java.util.Map.of("error", message(exception)));
        }
        if (exception instanceof IllegalArgumentException) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", message(exception)));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(java.util.Map.of("error", "internal error"));
    }

    private static String message(Throwable exception) {
        return exception == null || exception.getMessage() == null || exception.getMessage().isBlank()
                ? "request failed" : exception.getMessage();
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure instanceof CompletionException && failure.getCause() != null) {
            return unwrap(failure.getCause());
        }
        return failure;
    }
}
