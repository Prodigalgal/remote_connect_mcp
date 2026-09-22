package com.prodigalgal.remoteconnectmcp.center;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.prodigalgal.remoteconnectmcp.protocol.ScopeMode;
import com.prodigalgal.remoteconnectmcp.protocol.LaneMode;
import com.prodigalgal.remoteconnectmcp.protocol.TaskCommand;
import com.prodigalgal.remoteconnectmcp.protocol.TaskKind;
import com.prodigalgal.remoteconnectmcp.protocol.WorkspacePolicyMode;
import java.util.Map;

/**
 * Flat, console-facing task request.  The internal {@link CreateTaskRequest}
 * deliberately carries a protocol {@code TaskCommand}; the admin API accepts
 * a flat JSON object so a browser does not have to know the Agent wire shape.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AdminCreateTaskRequest(
        @JsonProperty("machine_id") String machineId,
        String kind,
        @JsonProperty("required_capability") String requiredCapability,
        String command,
        String cwd,
        Map<String, String> env,
        @JsonProperty("timeout_seconds") Integer timeoutSeconds,
        @JsonProperty("idempotency_key") String idempotencyKey,
        @JsonProperty("project_id") String projectId,
        @JsonProperty("worktree_id") String worktreeId,
        @JsonProperty("scope_mode") String scopeMode,
        @JsonProperty("scope_root") String scopeRoot,
        @JsonProperty("session_id") String sessionId,
        String risk,
        @JsonProperty("elevation_required") Boolean elevationRequired,
        @JsonProperty("workspace_policy") WorkspacePolicyMode workspacePolicy,
        @JsonProperty("lane_mode") LaneMode laneMode,
        TaskCommand.DesktopAction desktop) {

    /** Construction overload with default scope and lane values. */
    public AdminCreateTaskRequest(String machineId, String command, String cwd, Map<String, String> env,
                                  Integer timeoutSeconds, String idempotencyKey, String projectId, String worktreeId) {
        this(machineId, "command", "command", command, cwd, env, timeoutSeconds, idempotencyKey, projectId, worktreeId,
                "", "", "", "low", false, null, null, null);
    }

    /** Construction overload with default lane values. */
    public AdminCreateTaskRequest(String machineId, String command, String cwd, Map<String, String> env,
                                  Integer timeoutSeconds, String idempotencyKey, String projectId, String worktreeId,
                                  String scopeMode, String scopeRoot, String sessionId, String risk,
                                  Boolean elevationRequired) {
        this(machineId, "command", "command", command, cwd, env, timeoutSeconds, idempotencyKey, projectId, worktreeId,
                scopeMode, scopeRoot, sessionId, risk, elevationRequired, null, null, null);
    }

    public AdminCreateTaskRequest {
        machineId = machineId == null ? "" : machineId.trim();
        kind = kind == null || kind.isBlank() ? "command" : kind.trim();
        requiredCapability = requiredCapability == null || requiredCapability.isBlank() ? kind : requiredCapability.trim();
        command = command == null ? "" : command;
        cwd = cwd == null ? "" : cwd.trim();
        env = env == null ? Map.of() : Map.copyOf(env);
        timeoutSeconds = timeoutSeconds == null ? 0 : timeoutSeconds;
        idempotencyKey = idempotencyKey == null ? "" : idempotencyKey.trim();
        projectId = projectId == null ? "" : projectId.trim();
        worktreeId = worktreeId == null ? "" : worktreeId.trim();
        scopeMode = scopeMode == null ? "" : scopeMode.trim();
        scopeRoot = scopeRoot == null ? "" : scopeRoot.trim();
        sessionId = sessionId == null ? "" : sessionId.trim();
        risk = risk == null ? "" : risk.trim();
        elevationRequired = elevationRequired == null ? Boolean.FALSE : elevationRequired;
    }

    public CreateTaskRequest toInternal() {
        var parsedKind = TaskKind.fromWireValue(kind);
        var task = new com.prodigalgal.remoteconnectmcp.protocol.TaskCommand(
                "", parsedKind, requiredCapability,
                command, cwd.isBlank() ? null : cwd, env, timeoutSeconds, desktop, java.time.Instant.now(), null, 0, null);
        var parsedScope = scopeMode.isBlank() ? null : ScopeMode.fromWireValue(scopeMode);
        return new CreateTaskRequest(machineId, task, idempotencyKey, projectId, worktreeId,
                parsedScope, scopeRoot, workspacePolicy, laneMode, sessionId, risk,
                elevationRequired, TaskOrigin.configured());
    }
}
