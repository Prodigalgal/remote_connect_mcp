package com.prodigalgal.remoteconnectmcp.center;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.prodigalgal.remoteconnectmcp.protocol.TaskCommand;
import com.prodigalgal.remoteconnectmcp.protocol.JsonCodec;
import com.prodigalgal.remoteconnectmcp.protocol.ScopeMode;
import com.prodigalgal.remoteconnectmcp.protocol.LaneMode;
import com.prodigalgal.remoteconnectmcp.protocol.WorkspacePolicyMode;
import com.prodigalgal.remoteconnectmcp.protocol.SensitiveValueRedactor;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.beans.factory.ObjectProvider;

/** Small, versioned admin API consumed by the React console. */
@RestController
@RequestMapping("/api/v1/admin")
public final class AdminController {
    private final CenterTokenConfig tokens;
    private final AgentRegistry agents;
    private final TaskService tasks;
    private final McpPrincipalService principals;
    private final McpAccessService access;
    private final ExecutionSessionService sessions;
    private final EnrollmentTokenService enrollments;
    private final UpgradeService upgrades;
    private final AgentConfigurationService configurations;
    private final CenterAsyncExecutor async;
    private final AgentWakeRegistry wakes;
    private final ProjectService projects;
    private final TaskChangeRegistry changes;
    private final ReleaseCatalogService releases;
    private final AuditService audit;
    private final McpQuotaService quota;
    private final ArtifactTransferService transfers;

    @org.springframework.beans.factory.annotation.Autowired
    public AdminController(CenterTokenConfig tokens, AgentRegistry agents, TaskService tasks,
                           McpPrincipalService principals, McpAccessService access,
                           ExecutionSessionService sessions,
                           EnrollmentTokenService enrollments, UpgradeService upgrades,
                           AgentConfigurationService configurations, CenterAsyncExecutor async,
                           ObjectProvider<AgentWakeRegistry> wakeProvider,
                           ProjectService projects,
                           ObjectProvider<TaskChangeRegistry> changeProvider,
                           ObjectProvider<ReleaseCatalogService> releaseProvider,
                           ObjectProvider<AuditService> auditProvider,
                           ObjectProvider<McpQuotaService> quotaProvider,
                           ObjectProvider<ArtifactTransferService> transferProvider) {
        this.tokens = tokens;
        this.agents = agents;
        this.tasks = tasks;
        this.principals = principals;
        this.access = access;
        this.sessions = sessions;
        this.enrollments = enrollments;
        this.upgrades = upgrades;
        this.configurations = configurations;
        this.async = async;
        this.wakes = wakeProvider == null ? null : wakeProvider.getIfAvailable();
        this.projects = projects;
        this.changes = changeProvider == null ? null : changeProvider.getIfAvailable();
        this.releases = releaseProvider == null ? null : releaseProvider.getIfAvailable();
        this.audit = auditProvider == null ? null : auditProvider.getIfAvailable();
        this.quota = quotaProvider == null ? null : quotaProvider.getIfAvailable();
        this.transfers = transferProvider == null ? null : transferProvider.getIfAvailable();
    }

    /** Compatibility constructor for direct protocol/controller tests. */
    AdminController(CenterTokenConfig tokens, AgentRegistry agents, TaskService tasks,
                    EnrollmentTokenService enrollments, UpgradeService upgrades,
                    AgentConfigurationService configurations, CenterAsyncExecutor async) {
        this(tokens, agents, tasks, null, null, null, enrollments, upgrades, configurations, async, null, null, null, null, null, null, null);
    }

    /**
     * Long-polling control-plane changes. The response contains only an
     * opaque in-process sequence; callers re-fetch their bounded projections
     * after a change and never receive task output or credentials here.
     */
    @GetMapping("/events")
    public CompletableFuture<ResponseEntity<?>> events(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "0") long cursor,
            @RequestParam(value = "wait_ms", defaultValue = "25000") long waitMs) {
        return execute(() -> {
            authenticate(authorization);
            if (cursor < 0) throw new IllegalArgumentException("cursor must be non-negative");
            if (waitMs < 0 || waitMs > 25_000L) {
                throw new IllegalArgumentException("wait_ms must be between 0 and 25000");
            }
            if (changes == null) return noStore(Map.of("cursor", 0L, "changed", false));
            var observed = changes.version();
            if (cursor != observed || waitMs == 0) {
                return noStore(Map.of("cursor", observed, "changed", cursor != observed));
            }
            changes.awaitChange(observed, Duration.ofMillis(waitMs).toNanos());
            var next = changes.version();
            return noStore(Map.of("cursor", next, "changed", next != observed));
        });
    }

    private static ResponseEntity<Map<String, Object>> noStore(Map<String, Object> body) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(body);
    }

    @GetMapping("/machines")
    public CompletableFuture<ResponseEntity<?>> machines(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                         @RequestParam(defaultValue = "0") int offset,
                                                         @RequestParam(defaultValue = "50") int limit) {
        return execute(() -> {
            authenticate(authorization);
            var items = agents.listMachines(offset, limit, java.time.Instant.now());
            var total = agents.totalCount();
            return ResponseEntity.ok(Map.of("items", items, "offset", offset, "limit", limit,
                    "total", total, "has_more", hasMore(offset, items.size(), total)));
        });
    }

    /** Bounded, redacted audit projection for the console; MCP never exposes this feed. */
    @GetMapping("/audit")
    public CompletableFuture<ResponseEntity<?>> audit(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "event_type", required = false) String eventType,
            @RequestParam(value = "agent_id", required = false) String agentId,
            @RequestParam(value = "task_id", required = false) String taskId,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit) {
        return execute(() -> {
            authenticate(authorization);
            var items = audit == null ? java.util.List.<AuditEventView>of()
                    : audit.list(eventType, agentId, taskId, offset, limit);
            var total = audit == null ? 0 : audit.count(eventType, agentId, taskId);
            return ResponseEntity.ok(Map.of("items", items, "offset", offset, "limit", limit,
                    "total", total, "has_more", hasMore(offset, items.size(), total)));
        });
    }

    /** Explicit, bounded audit retention; never runs from a timer. */
    @PostMapping("/audit/gc")
    public CompletableFuture<ResponseEntity<?>> garbageCollectAudit(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "365") int retentionDays,
            @RequestParam(defaultValue = "500") int limit) {
        return execute(() -> {
            authenticate(authorization);
            var deleted = audit == null ? 0 : audit.purge(retentionDays, limit);
            if (audit != null) {
                audit.record("audit.gc", "admin", null, null, null, "medium", "accepted",
                        "deleted=" + deleted + ",retention_days=" + retentionDays);
            }
            return ResponseEntity.ok(Map.of("deleted", deleted, "retention_days", retentionDays, "limit", limit));
        });
    }

    @GetMapping("/tasks")
    public CompletableFuture<ResponseEntity<?>> taskList(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                         @RequestParam(defaultValue = "0") int offset,
                                                         @RequestParam(defaultValue = "50") int limit) {
        return execute(() -> {
            authenticate(authorization);
            var items = tasks.list(offset, limit);
            var total = tasks.totalCount();
            return ResponseEntity.ok(Map.of("items", items, "offset", offset, "limit", limit,
                    "total", total, "has_more", hasMore(offset, items.size(), total)));
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
            signalChange();
            if (audit != null) audit.record("agent.config.update", "admin", machineId, null, null, "medium", "accepted",
                    "generation=" + updated.generation());
            return ResponseEntity.ok(updated);
        });
    }

    @PostMapping("/machines/{machineId}/config/rollback")
    public CompletableFuture<ResponseEntity<?>> rollbackMachineConfig(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String machineId) {
        return execute(() -> {
            authenticate(authorization);
            agents.findMachine(machineId, java.time.Instant.now()).orElseThrow(() -> new IllegalArgumentException("machine not found"));
            var rolledBack = configurations.rollback(machineId);
            if (wakes != null) wakes.signal(machineId);
            signalChange();
            if (audit != null) audit.record("agent.config.rollback", "admin", machineId, null, null, "medium", "accepted",
                    "generation=" + rolledBack.generation());
            return ResponseEntity.ok(rolledBack);
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
            if (artifact.isPresent()) {
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
                        .header("Referrer-Policy", "no-referrer")
                        .header("Cache-Control", "private, no-store")
                        .body(value.data());
            }
            if (transfers == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            var streamed = transfers.openForAdminTask(taskId);
            if (streamed.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            var value = streamed.get();
            var mediaType = safeMediaType(value.mimeType());
            StreamingResponseBody body = output -> {
                try (var input = value.body()) {
                    if (input == null) throw new IOException("artifact object is unavailable");
                    input.transferTo(output);
                }
            };
            var headers = new HttpHeaders();
            headers.setContentType(mediaType);
            headers.setContentLength(value.bytes());
            headers.setContentDisposition(ContentDisposition.attachment().filename(value.fileName()).build());
            headers.set("X-Artifact-SHA256", value.sha256());
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("Referrer-Policy", "no-referrer");
            headers.setCacheControl("private, no-store");
            return ResponseEntity.ok().headers(headers).body(body);
        });
    }

    private static MediaType safeMediaType(String value) {
        try {
            return value == null || value.isBlank() ? MediaType.APPLICATION_OCTET_STREAM
                    : MediaType.parseMediaType(value);
        } catch (IllegalArgumentException invalidMime) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    @PostMapping("/tasks")
    public CompletableFuture<ResponseEntity<?>> createTask(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                           @RequestBody(required = false) Object request) {
        return execute(() -> {
            authenticate(authorization);
            var decoded = decodeTaskRequest(request);
            var internal = decoded.internal();
            // A bounded machine may safely inherit its registered workspace
            // for legacy admin payloads.  An unrestricted machine may not:
            // whole-host authority must be visible in the request so a UI,
            // retry handler, or model cannot obtain it by omission.
            if (!decoded.scopeExplicit() && decoded.projectId().isBlank()) {
                var machine = agents.findMachine(internal.machineId(), java.time.Instant.now())
                        .orElseThrow(() -> new IllegalArgumentException("machine not found"));
                if (ScopeMode.fromWireValue(machine.scopeMode()) == ScopeMode.UNRESTRICTED) {
                    throw new IllegalArgumentException("scope_mode=unrestricted must be explicit");
                }
            }
            if (!decoded.projectId().isBlank()) {
                if (projects == null) throw new IllegalStateException("project service is unavailable");
                var cwd = projects.resolveCwd(internal.machineId(), decoded.projectId(), decoded.worktreeId(), decoded.requestedCwd());
                var scopeRoot = projects.resolveScopeRoot(internal.machineId(), decoded.projectId(), decoded.worktreeId());
                var original = internal.command();
                var scoped = new com.prodigalgal.remoteconnectmcp.protocol.TaskCommand(
                        "", original.kind(), original.requiredCapability(), original.command(), cwd,
                        original.env(), original.timeoutSeconds(), original.desktop(), original.createdAt(), original.contract());
                var mode = internal.scopeMode() == null
                        ? (decoded.worktreeId().isBlank() ? ScopeMode.PROJECT : ScopeMode.WORKTREE)
                        : internal.scopeMode();
                internal = new CreateTaskRequest(internal.machineId(), scoped, internal.idempotencyKey(),
                        decoded.projectId(), decoded.worktreeId(), mode, scopeRoot,
                        internal.workspacePolicy(), internal.laneMode(), internal.sessionId(),
                        internal.risk(), internal.elevationRequired(), internal.origin());
            }
            return ResponseEntity.status(HttpStatus.CREATED).body(tasks.create(internal, "console"));
        });
    }

    /** Accept the flat console contract while keeping the old nested request compatible. */
    private static DecodedTaskRequest decodeTaskRequest(Object value) {
        if (value instanceof AdminCreateTaskRequest request) {
            return new DecodedTaskRequest(request.toInternal(), request.projectId(), request.worktreeId(), request.cwd(),
                    !request.scopeMode().isBlank());
        }
        if (!(value instanceof Map<?, ?> raw)) throw new IllegalArgumentException("task request is required");
        var commandValue = raw.get("command");
        if (commandValue instanceof String || commandValue == null) {
            var flat = new AdminCreateTaskRequest(
                    text(raw.get("machine_id")), text(raw.get("command")), text(raw.get("cwd")),
                    stringMap(raw.get("env")), integer(raw.get("timeout_seconds")), text(raw.get("idempotency_key")),
                     text(raw.get("project_id")), text(raw.get("worktree_id")), text(raw.get("scope_mode")),
                     text(raw.get("scope_root")), text(raw.get("session_id")), text(raw.get("risk")),
                     bool(raw.get("elevation_required")),
                     text(raw.get("workspace_policy")).isBlank() ? null : WorkspacePolicyMode.fromWireValue(text(raw.get("workspace_policy"))),
                     text(raw.get("lane_mode")).isBlank() ? null : LaneMode.fromWireValue(text(raw.get("lane_mode"))));
            return new DecodedTaskRequest(flat.toInternal(), flat.projectId(), flat.worktreeId(), flat.cwd(),
                    !flat.scopeMode().isBlank());
        }
        try {
            var command = JsonCodec.read(JsonCodec.write(commandValue), TaskCommand.class);
            if (command.contract() != null) {
                throw new IllegalArgumentException("execution contract is managed by Center and cannot be supplied by the caller");
            }
            var scopeMode = text(raw.get("scope_mode"));
            var projectId = text(raw.get("project_id"));
            var worktreeId = text(raw.get("worktree_id"));
            var internal = new CreateTaskRequest(text(raw.get("machine_id")), command,
                    text(raw.get("idempotency_key")), projectId, worktreeId,
                     scopeMode.isBlank() ? null : ScopeMode.fromWireValue(scopeMode),
                     text(raw.get("scope_root")),
                     text(raw.get("workspace_policy")).isBlank() ? null : WorkspacePolicyMode.fromWireValue(text(raw.get("workspace_policy"))),
                     text(raw.get("lane_mode")).isBlank() ? null : LaneMode.fromWireValue(text(raw.get("lane_mode"))),
                     text(raw.get("session_id")), text(raw.get("risk")),
                     bool(raw.get("elevation_required")), TaskOrigin.shared());
            return new DecodedTaskRequest(internal, projectId, worktreeId, command.cwd(), !scopeMode.isBlank());
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

    @PostMapping("/artifacts/gc")
    public CompletableFuture<ResponseEntity<?>> garbageCollectArtifacts(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "30") int retentionDays,
            @RequestParam(defaultValue = "100") int limit) {
        return execute(() -> {
            authenticate(authorization);
            return ResponseEntity.ok(tasks.gcArtifacts(retentionDays, limit));
        });
    }

    /** Issue one opaque user MCP token; plaintext is returned exactly once. */
    @PostMapping("/mcp-tokens")
    public CompletableFuture<ResponseEntity<?>> issueMcpToken(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) IssueMcpTokenRequest request) {
        return execute(() -> {
            authenticate(authorization);
            if (principals == null) throw new IllegalStateException("MCP principal service is unavailable");
            var body = request == null ? new IssueMcpTokenRequest("", "", null, java.util.Set.of()) : request;
            var issued = principals.issue(new McpPrincipalService.IssueRequest(body.principalId(), body.displayName(),
                    body.expiresInSeconds(), body.scopes()));
            signalChange();
            if (audit != null) audit.record("mcp-token.issue", "admin", null, null, null, "medium", "accepted",
                    "token_id=" + issued.tokenId() + ",principal_id=" + issued.principalId());
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                    "token_id", issued.tokenId(), "principal_id", issued.principalId(),
                    "display_name", issued.displayName(), "scopes", issued.scopes(),
                    "expires_at", issued.expiresAt() == null ? "" : issued.expiresAt(),
                    "token", issued.token()));
        });
    }

    @GetMapping("/mcp-tokens")
    public CompletableFuture<ResponseEntity<?>> mcpTokens(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit) {
        return execute(() -> {
            authenticate(authorization);
            if (principals == null) throw new IllegalStateException("MCP principal service is unavailable");
            var items = principals.list(offset, limit);
            var total = principals.count();
            return ResponseEntity.ok(Map.of("items", items, "offset", offset, "limit", limit,
                    "total", total, "has_more", hasMore(offset, items.size(), total)));
        });
    }

    @PostMapping("/mcp-tokens/{tokenId}/revoke")
    public CompletableFuture<ResponseEntity<?>> revokeMcpToken(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String tokenId) {
        return execute(() -> {
            authenticate(authorization);
            if (principals == null) throw new IllegalStateException("MCP principal service is unavailable");
            var revoked = principals.revoke(tokenId);
            if (revoked) signalChange();
            if (audit != null) audit.record("mcp-token.revoke", "admin", null, null, null, "medium",
                    revoked ? "accepted" : "not_found", "token_id_present=" + (tokenId != null && !tokenId.isBlank()));
            return ResponseEntity.ok(Map.of("token_id", tokenId == null ? "" : tokenId, "revoked", revoked));
        });
    }

    /** Grant a user Token explicit access to one machine. */
    @PostMapping("/access/machines")
    public CompletableFuture<ResponseEntity<?>> grantMachineAccess(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) MachineGrantRequest request) {
        return execute(() -> {
            authenticate(authorization);
            if (access == null) throw new IllegalStateException("MCP access service is unavailable");
            var body = request == null ? new MachineGrantRequest("", "", java.util.Set.of(), null) : request;
            var grant = access.grantMachine(body.principalId(), body.machineId(), body.scopes(),
                    grantExpiry(body.expiresInSeconds()));
            signalChange();
            if (audit != null) audit.record("mcp-access.machine-grant", "admin", body.machineId(), null,
                    null, "medium", "accepted", "principal_id=" + body.principalId());
            return ResponseEntity.status(HttpStatus.CREATED).body(grant);
        });
    }

    @GetMapping("/access/machines")
    public CompletableFuture<ResponseEntity<?>> machineAccess(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "") String principalId,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit) {
        return execute(() -> {
            authenticate(authorization);
            if (access == null) throw new IllegalStateException("MCP access service is unavailable");
            var items = access.listMachine(principalId, offset, limit);
            var total = access.machineCount(principalId);
            return ResponseEntity.ok(Map.of("items", items, "offset", offset, "limit", limit,
                    "total", total, "has_more", hasMore(offset, items.size(), total)));
        });
    }

    @DeleteMapping("/access/machines")
    public CompletableFuture<ResponseEntity<?>> revokeMachineAccess(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) MachineGrantRequest request) {
        return execute(() -> {
            authenticate(authorization);
            if (access == null) throw new IllegalStateException("MCP access service is unavailable");
            var body = request == null ? new MachineGrantRequest("", "", java.util.Set.of(), null) : request;
            var revoked = access.revokeMachine(body.principalId(), body.machineId());
            if (revoked) signalChange();
            return ResponseEntity.ok(Map.of("principal_id", body.principalId(), "machine_id", body.machineId(),
                    "revoked", revoked));
        });
    }

    /** Grant a user Token explicit membership in one registered project. */
    @PostMapping("/access/projects")
    public CompletableFuture<ResponseEntity<?>> grantProjectAccess(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) ProjectMemberRequest request) {
        return execute(() -> {
            authenticate(authorization);
            if (access == null) throw new IllegalStateException("MCP access service is unavailable");
            var body = request == null ? new ProjectMemberRequest("", "", java.util.Set.of(), null) : request;
            var grant = access.grantProject(body.principalId(), body.projectId(), body.scopes(),
                    grantExpiry(body.expiresInSeconds()));
            signalChange();
            if (audit != null) audit.record("mcp-access.project-grant", "admin", null, body.projectId(),
                    null, "medium", "accepted", "principal_id=" + body.principalId());
            return ResponseEntity.status(HttpStatus.CREATED).body(grant);
        });
    }

    @GetMapping("/access/projects")
    public CompletableFuture<ResponseEntity<?>> projectAccess(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "") String principalId,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit) {
        return execute(() -> {
            authenticate(authorization);
            if (access == null) throw new IllegalStateException("MCP access service is unavailable");
            var items = access.listProject(principalId, offset, limit);
            var total = access.projectCount(principalId);
            return ResponseEntity.ok(Map.of("items", items, "offset", offset, "limit", limit,
                    "total", total, "has_more", hasMore(offset, items.size(), total)));
        });
    }

    @DeleteMapping("/access/projects")
    public CompletableFuture<ResponseEntity<?>> revokeProjectAccess(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) ProjectMemberRequest request) {
        return execute(() -> {
            authenticate(authorization);
            if (access == null) throw new IllegalStateException("MCP access service is unavailable");
            var body = request == null ? new ProjectMemberRequest("", "", java.util.Set.of(), null) : request;
            var revoked = access.revokeProject(body.principalId(), body.projectId());
            if (revoked) signalChange();
            return ResponseEntity.ok(Map.of("principal_id", body.principalId(), "project_id", body.projectId(),
                    "revoked", revoked));
        });
    }

    /** Bounded execution-session projection for recovery and support tooling. */
    @GetMapping("/execution-sessions")
    public CompletableFuture<ResponseEntity<?>> executionSessions(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "") String principalId,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit) {
        return execute(() -> {
            authenticate(authorization);
            if (sessions == null) throw new IllegalStateException("execution session service is unavailable");
            var items = sessions.list(principalId, offset, limit);
            var total = sessions.count(principalId);
            return ResponseEntity.ok(Map.of("items", items, "offset", offset, "limit", limit,
                    "total", total, "has_more", hasMore(offset, items.size(), total)));
        });
    }

    /** Explicitly close one session; no background cleanup or timer is used. */
    @PostMapping("/execution-sessions/close")
    public CompletableFuture<ResponseEntity<?>> closeExecutionSession(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) SessionCloseRequest request) {
        return execute(() -> {
            authenticate(authorization);
            if (sessions == null) throw new IllegalStateException("execution session service is unavailable");
            var body = request == null ? new SessionCloseRequest("", "") : request;
            var closed = sessions.close(new TaskOrigin(body.principalId(), "admin", "admin"), body.sessionId());
            if (closed) signalChange();
            return ResponseEntity.ok(Map.of("principal_id", body.principalId(), "session_id", body.sessionId(),
                    "closed", closed));
        });
    }

    /** Read a bounded principal quota projection for the Console. */
    @GetMapping("/quotas/{principalId}")
    public CompletableFuture<ResponseEntity<?>> quota(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String principalId) {
        return execute(() -> {
            authenticate(authorization);
            if (quota == null) throw new IllegalStateException("quota service is unavailable");
            return ResponseEntity.ok(quota.snapshot(principalId));
        });
    }

    private static Boolean bool(Object value) {
        if (value == null) return Boolean.FALSE;
        if (value instanceof Boolean flag) return flag;
        return Boolean.parseBoolean(String.valueOf(value));
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

    private record DecodedTaskRequest(CreateTaskRequest internal, String projectId, String worktreeId,
                                      String requestedCwd, boolean scopeExplicit) {
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public CompletableFuture<ResponseEntity<?>> cancelTask(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                           @PathVariable String taskId) {
        return execute(() -> {
            authenticate(authorization);
            var canceled = tasks.cancel(taskId);
            if (transfers != null && TaskStatus.CANCELED.equals(canceled.status())) {
                transfers.cancelForTask(canceled.id());
            }
            return ResponseEntity.ok(canceled);
        });
    }

    @GetMapping("/upgrades")
    public CompletableFuture<ResponseEntity<?>> upgrades(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                          @RequestParam(defaultValue = "0") int offset,
                                                          @RequestParam(defaultValue = "20") int limit) {
        return execute(() -> {
            authenticate(authorization);
            var items = upgrades.list(offset, limit);
            var total = upgrades.count();
            return ResponseEntity.ok(Map.of("items", items, "offset", offset, "limit", limit,
                    "total", total, "has_more", hasMore(offset, items.size(), total)));
        });
    }

    /**
     * List published Java Agent releases for the console selector.  Only
     * bounded metadata is returned; artifact URLs and checksums are resolved
     * again by UpgradeService when a campaign is created.
     */
    @GetMapping("/releases")
    public CompletableFuture<ResponseEntity<?>> releases(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "true") boolean includePrerelease,
            @RequestParam(defaultValue = "false") boolean refresh) {
        return execute(() -> {
            authenticate(authorization);
            if (releases == null) throw new IllegalStateException("release catalog is unavailable");
            return ResponseEntity.ok().header("Cache-Control", "no-store")
                    .body(releases.list(limit, includePrerelease, refresh));
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

    @PostMapping("/upgrades/{campaignId}/targets/{machineId}/retry")
    public CompletableFuture<ResponseEntity<?>> retryUpgradeTarget(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String campaignId,
            @PathVariable String machineId) {
        return execute(() -> {
            authenticate(authorization);
            var result = upgrades.retryTarget(campaignId, machineId);
            signalChange();
            return ResponseEntity.ok(result);
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
            signalChange();
            if (audit != null) {
                // Never put the plaintext enrollment credential (or the
                // requested machine name) into audit storage.  The token is
                // returned once in the response and only its lifecycle event
                // is retained here.
                audit.record("enrollment.issue", "admin", null, null, null, "medium", "accepted",
                        "token_issued=true,expires_at=" + issued.expiresAt());
            }
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
            var revoked = enrollments.revoke(tokenId) > 0;
            if (revoked) signalChange();
            if (audit != null) {
                audit.record("enrollment.revoke", "admin", null, null, null, "medium",
                        revoked ? "accepted" : "not_found", "token_id_present=" + !tokenId.isBlank());
            }
            return ResponseEntity.ok(Map.of("token_id", tokenId, "revoked", revoked));
        });
    }

    private CompletableFuture<ResponseEntity<?>> execute(java.util.concurrent.Callable<ResponseEntity<?>> action) {
        return async.submit(action).exceptionally(failure -> error(unwrap(failure)));
    }

    private void signalChange() {
        if (changes != null) changes.signalGlobal();
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
        var message = exception == null || exception.getMessage() == null || exception.getMessage().isBlank()
                ? "request failed" : exception.getMessage();
        return SensitiveValueRedactor.redact(message);
    }

    private static boolean hasMore(int offset, int size, int total) {
        return offset >= 0 && size > 0 && offset < total - size;
    }

    private static java.time.Instant grantExpiry(Long expiresInSeconds) {
        if (expiresInSeconds == null || expiresInSeconds == 0) return null;
        var max = java.time.Duration.ofDays(3650).toSeconds();
        if (expiresInSeconds < 3600 || expiresInSeconds > max) {
            throw new IllegalArgumentException("expires_in_seconds must be 0 or between 3600 and 315360000 seconds");
        }
        return java.time.Instant.now().plusSeconds(expiresInSeconds);
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

    public record IssueMcpTokenRequest(
            @JsonProperty("principal_id") String principalId,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("expires_in_seconds") Long expiresInSeconds,
            java.util.Set<String> scopes) {
    }

    public record MachineGrantRequest(
            @JsonProperty("principal_id") String principalId,
            @JsonProperty("machine_id") String machineId,
            java.util.Set<String> scopes,
            @JsonProperty("expires_in_seconds") Long expiresInSeconds) {
    }

    public record ProjectMemberRequest(
            @JsonProperty("principal_id") String principalId,
            @JsonProperty("project_id") String projectId,
            java.util.Set<String> scopes,
            @JsonProperty("expires_in_seconds") Long expiresInSeconds) {
    }

    public record SessionCloseRequest(
            @JsonProperty("principal_id") String principalId,
            @JsonProperty("session_id") String sessionId) {
    }
}
