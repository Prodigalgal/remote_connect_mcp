package com.prodigalgal.remoteconnectmcp.center;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.prodigalgal.remoteconnectmcp.protocol.TaskCommand;
import com.prodigalgal.remoteconnectmcp.protocol.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.ObjectProvider;

/** Small, versioned admin API consumed by the React console. */
@RestController
@RequestMapping("/api/v1/admin")
public final class AdminController {
    private final CenterTokenConfig tokens;
    private final AgentRegistry agents;
    private final TaskService tasks;
    private final EnrollmentTokenService enrollments;
    private final UpgradeService upgrades;
    private final AgentConfigurationService configurations;
    private final CenterAsyncExecutor async;
    private final AgentWakeRegistry wakes;
    private final ProjectService projects;

    @org.springframework.beans.factory.annotation.Autowired
    public AdminController(CenterTokenConfig tokens, AgentRegistry agents, TaskService tasks,
                           EnrollmentTokenService enrollments, UpgradeService upgrades,
                           AgentConfigurationService configurations, CenterAsyncExecutor async,
                           ObjectProvider<AgentWakeRegistry> wakeProvider,
                           ProjectService projects) {
        this.tokens = tokens;
        this.agents = agents;
        this.tasks = tasks;
        this.enrollments = enrollments;
        this.upgrades = upgrades;
        this.configurations = configurations;
        this.async = async;
        this.wakes = wakeProvider == null ? null : wakeProvider.getIfAvailable();
        this.projects = projects;
    }

    /** Compatibility constructor for direct protocol/controller tests. */
    AdminController(CenterTokenConfig tokens, AgentRegistry agents, TaskService tasks,
                    EnrollmentTokenService enrollments, UpgradeService upgrades,
                    AgentConfigurationService configurations, CenterAsyncExecutor async) {
        this(tokens, agents, tasks, enrollments, upgrades, configurations, async, null, null);
    }

    @GetMapping("/machines")
    public CompletableFuture<ResponseEntity<?>> machines(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                         @RequestParam(defaultValue = "0") int offset,
                                                         @RequestParam(defaultValue = "50") int limit) {
        return execute(() -> {
            authenticate(authorization);
            return ResponseEntity.ok(Map.of("items", agents.listMachines(offset, limit, java.time.Instant.now()), "offset", offset, "limit", limit));
        });
    }

    @GetMapping("/tasks")
    public CompletableFuture<ResponseEntity<?>> taskList(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                         @RequestParam(defaultValue = "0") int offset,
                                                         @RequestParam(defaultValue = "50") int limit) {
        return execute(() -> {
            authenticate(authorization);
            return ResponseEntity.ok(Map.of("items", tasks.list(offset, limit), "offset", offset, "limit", limit));
        });
    }

    @GetMapping("/tasks/{taskId}")
    public CompletableFuture<ResponseEntity<?>> task(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                     @PathVariable String taskId) {
        return execute(() -> {
            authenticate(authorization);
            return ResponseEntity.ok(tasks.find(taskId).map(TaskView::new).orElseThrow(() -> new IllegalArgumentException("task not found")));
        });
    }

    @GetMapping("/machines/{machineId}/config")
    public CompletableFuture<ResponseEntity<?>> machineConfig(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                               @PathVariable String machineId) {
        return execute(() -> {
            authenticate(authorization);
            agents.findMachine(machineId, java.time.Instant.now()).orElseThrow(() -> new IllegalArgumentException("machine not found"));
            var config = configurations.current(machineId);
            return ResponseEntity.ok(config == null ? com.prodigalgal.remoteconnectmcp.protocol.AgentConfigUpdate.defaults() : config);
        });
    }

    @PutMapping("/machines/{machineId}/config")
    public CompletableFuture<ResponseEntity<?>> updateMachineConfig(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                                     @PathVariable String machineId,
                                                                     @RequestBody(required = false) AgentConfigUpdateRequest request) {
        return execute(() -> {
            authenticate(authorization);
            agents.findMachine(machineId, java.time.Instant.now()).orElseThrow(() -> new IllegalArgumentException("machine not found"));
            var updated = configurations.update(machineId, request);
            // A WebSocket connection is only a wake-up accelerator; signal it
            // after the transaction commits so the Agent immediately fetches
            // the new generation over the authoritative HTTPS poll path.
            if (wakes != null) wakes.signal(machineId);
            return ResponseEntity.ok(updated);
        });
    }

    @GetMapping("/tasks/{taskId}/output")
    public CompletableFuture<ResponseEntity<?>> output(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                       @PathVariable String taskId,
                                                       @RequestParam(defaultValue = "0") long cursor,
                                                       @RequestParam(defaultValue = "16384") int limit) {
        return execute(() -> {
            authenticate(authorization);
            var page = tasks.readOutput(taskId, cursor, limit);
            var result = new LinkedHashMap<String, Object>();
            result.put("data_base64", Base64.getEncoder().encodeToString(page.data()));
            result.put("text", new String(page.data(), StandardCharsets.UTF_8));
            result.put("cursor", page.cursor());
            result.put("next_cursor", page.nextCursor());
            result.put("more", page.more());
            return ResponseEntity.ok(result);
        });
    }

    @GetMapping("/tasks/{taskId}/artifact")
    public CompletableFuture<ResponseEntity<?>> artifact(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                          @PathVariable String taskId) {
        return execute(() -> {
            authenticate(authorization);
            var artifact = tasks.readArtifact(taskId);
            if (artifact.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            var value = artifact.get();
            MediaType mediaType;
            try {
                mediaType = MediaType.parseMediaType(value.mimeType());
            } catch (IllegalArgumentException invalidMime) {
                mediaType = MediaType.APPLICATION_OCTET_STREAM;
            }
            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .contentLength(value.data().length)
                    .header("X-Artifact-SHA256", value.sha256())
                    .header("X-Content-Type-Options", "nosniff")
                    .header("Cache-Control", "no-store")
                    .body(value.data());
        });
    }

    @PostMapping("/tasks")
    public CompletableFuture<ResponseEntity<?>> createTask(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                           @RequestBody(required = false) Object request) {
        return execute(() -> {
            authenticate(authorization);
            var decoded = decodeTaskRequest(request);
            var internal = decoded.internal();
            if (!decoded.projectId().isBlank()) {
                if (projects == null) throw new IllegalStateException("project service is unavailable");
                var cwd = projects.resolveCwd(internal.machineId(), decoded.projectId(), decoded.worktreeId(), decoded.requestedCwd());
                var original = internal.command();
                var scoped = new com.prodigalgal.remoteconnectmcp.protocol.TaskCommand(
                        "", original.kind(), original.requiredCapability(), original.command(), cwd,
                        original.env(), original.timeoutSeconds(), original.desktop(), original.createdAt());
                internal = new CreateTaskRequest(internal.machineId(), scoped, internal.idempotencyKey());
            }
            return ResponseEntity.status(HttpStatus.CREATED).body(tasks.create(internal));
        });
    }

    /** Accept the flat console contract while keeping the old nested request compatible. */
    private static DecodedTaskRequest decodeTaskRequest(Object value) {
        if (value instanceof AdminCreateTaskRequest request) {
            return new DecodedTaskRequest(request.toInternal(), request.projectId(), request.worktreeId(), request.cwd());
        }
        if (!(value instanceof Map<?, ?> raw)) throw new IllegalArgumentException("task request is required");
        var commandValue = raw.get("command");
        if (commandValue instanceof String || commandValue == null) {
            var flat = new AdminCreateTaskRequest(
                    text(raw.get("machine_id")), text(raw.get("command")), text(raw.get("cwd")),
                    stringMap(raw.get("env")), integer(raw.get("timeout_seconds")), text(raw.get("idempotency_key")),
                    text(raw.get("project_id")), text(raw.get("worktree_id")));
            return new DecodedTaskRequest(flat.toInternal(), flat.projectId(), flat.worktreeId(), flat.cwd());
        }
        try {
            var command = JsonCodec.read(JsonCodec.write(commandValue), TaskCommand.class);
            var internal = new CreateTaskRequest(text(raw.get("machine_id")), command, text(raw.get("idempotency_key")));
            return new DecodedTaskRequest(internal, "", "", command.cwd());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("command must be a string or a valid task command object", exception);
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Integer integer(Object value) {
        if (value == null) return 0;
        if (value instanceof Number number) return number.intValue();
        try { return Integer.valueOf(String.valueOf(value)); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException("timeout_seconds must be an integer", exception); }
    }

    private static Map<String, String> stringMap(Object value) {
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?> raw)) throw new IllegalArgumentException("env must be an object");
        var result = new java.util.LinkedHashMap<String, String>();
        raw.forEach((key, entry) -> {
            if (key == null || entry == null) throw new IllegalArgumentException("env keys and values are required");
            result.put(String.valueOf(key), String.valueOf(entry));
        });
        return Map.copyOf(result);
    }

    private record DecodedTaskRequest(CreateTaskRequest internal, String projectId, String worktreeId, String requestedCwd) {
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public CompletableFuture<ResponseEntity<?>> cancelTask(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                           @PathVariable String taskId) {
        return execute(() -> {
            authenticate(authorization);
            return ResponseEntity.ok(tasks.cancel(taskId));
        });
    }

    @GetMapping("/upgrades")
    public CompletableFuture<ResponseEntity<?>> upgrades(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                          @RequestParam(defaultValue = "0") int offset,
                                                          @RequestParam(defaultValue = "20") int limit) {
        return execute(() -> {
            authenticate(authorization);
            return ResponseEntity.ok(Map.of("items", upgrades.list(offset, limit), "offset", offset, "limit", limit));
        });
    }

    @PostMapping("/upgrades")
    public CompletableFuture<ResponseEntity<?>> createUpgrade(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                              @RequestBody(required = false) CreateUpgradeCampaignRequest request) {
        return execute(() -> {
            authenticate(authorization);
            return ResponseEntity.status(HttpStatus.CREATED).body(upgrades.create(request));
        });
    }

    @PostMapping("/upgrades/{campaignId}/{action}")
    public CompletableFuture<ResponseEntity<?>> controlUpgrade(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                               @PathVariable String campaignId,
                                                               @PathVariable String action) {
        return execute(() -> {
            authenticate(authorization);
            return ResponseEntity.ok(upgrades.control(campaignId, action));
        });
    }

    @PostMapping("/enrollment-tokens")
    public CompletableFuture<ResponseEntity<?>> issueEnrollment(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                                @RequestBody(required = false) IssueEnrollmentRequest request) {
        return execute(() -> {
            authenticate(authorization);
            var body = request == null ? new IssueEnrollmentRequest("", null) : request;
            var seconds = body.expiresInSeconds() == null ? 24 * 60 * 60L : body.expiresInSeconds();
            var issued = enrollments.issue(body.requestedName(), Duration.ofSeconds(seconds));
            // The plaintext token is returned exactly once by this response;
            // database rows only contain the SHA-256 digest.
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                    "token_id", issued.tokenId(), "token", issued.token(),
                    "requested_name", issued.requestedName(), "expires_at", issued.expiresAt()));
        });
    }

    @PostMapping("/enrollment-tokens/{tokenId}/revoke")
    public CompletableFuture<ResponseEntity<?>> revokeEnrollment(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                                 @PathVariable String tokenId) {
        return execute(() -> {
            authenticate(authorization);
            return ResponseEntity.ok(Map.of("token_id", tokenId, "revoked", enrollments.revoke(tokenId) > 0));
        });
    }

    private CompletableFuture<ResponseEntity<?>> execute(java.util.concurrent.Callable<ResponseEntity<?>> action) {
        return async.submit(action).exceptionally(failure -> error(unwrap(failure)));
    }

    private void authenticate(String authorization) {
        if (!tokens.acceptsAdmin(bearerValue(authorization))) {
            throw new SecurityException("invalid admin credentials");
        }
    }

    private static String bearerValue(String authorization) {
        if (authorization == null) return "";
        var parts = authorization.trim().split("\\s+", 2);
        return parts.length == 2 && "Bearer".equalsIgnoreCase(parts[0]) ? parts[1].trim() : "";
    }

    private static ResponseEntity<Map<String, String>> error(Throwable exception) {
        if (exception instanceof SecurityException) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", message(exception)));
        }
        if (exception instanceof IllegalArgumentException) {
            return ResponseEntity.badRequest().body(Map.of("error", message(exception)));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "internal error"));
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

    public record IssueEnrollmentRequest(
            @JsonProperty("requested_name") String requestedName,
            @JsonProperty("expires_in_seconds") Long expiresInSeconds) {
    }
}
