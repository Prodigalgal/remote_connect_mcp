package com.prodigalgal.remoteconnectmcp.center;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.prodigalgal.remoteconnectmcp.protocol.ScopeMode;
import java.util.Map;

/**
 * Flat, console-facing task request.  The internal {@link CreateTaskRequest}
 * deliberately carries a protocol {@code TaskCommand}; the admin API accepts
 * a flat JSON object so a browser does not have to know the Agent wire shape.
 */
public record AdminCreateTaskRequest(
        @JsonProperty("machine_id") String machineId,
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
        @JsonProperty("elevation_required") Boolean elevationRequired) {

    /** Compatibility constructor for the original flat console payload. */
    public AdminCreateTaskRequest(String machineId, String command, String cwd, Map<String, String> env,
                                  Integer timeoutSeconds, String idempotencyKey, String projectId, String worktreeId) {
        this(machineId, command, cwd, env, timeoutSeconds, idempotencyKey, projectId, worktreeId,
                "", "", "", "low", false);
    }

    public AdminCreateTaskRequest {
        machineId = machineId == null ? "" : machineId.trim();
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
        var task = new com.prodigalgal.remoteconnectmcp.protocol.TaskCommand(
                "", com.prodigalgal.remoteconnectmcp.protocol.TaskKind.COMMAND, null,
                command, cwd.isBlank() ? null : cwd, env, timeoutSeconds, null, null);
        var parsedScope = scopeMode.isBlank() ? null : ScopeMode.fromWireValue(scopeMode);
        return new CreateTaskRequest(machineId, task, idempotencyKey, projectId, worktreeId,
                parsedScope, scopeRoot, sessionId, risk, elevationRequired);
    }
}
