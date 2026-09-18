package com.prodigalgal.remoteconnectmcp.center;

import com.prodigalgal.remoteconnectmcp.protocol.TaskCommand;
import com.prodigalgal.remoteconnectmcp.protocol.ScopeMode;
import com.prodigalgal.remoteconnectmcp.protocol.LaneMode;
import com.prodigalgal.remoteconnectmcp.protocol.WorkspacePolicyMode;

/** Validated request used by MCP and the console to enqueue a task. */
public record CreateTaskRequest(
        String machineId,
        TaskCommand command,
        String idempotencyKey,
        String projectId,
        String worktreeId,
        ScopeMode scopeMode,
        String scopeRoot,
        WorkspacePolicyMode workspacePolicy,
        LaneMode laneMode,
        String sessionId,
        String risk,
        boolean elevationRequired,
        TaskOrigin origin) {

    /** Compatibility constructor for callers written before execution contracts. */
    public CreateTaskRequest(String machineId, TaskCommand command, String idempotencyKey) {
        this(machineId, command, idempotencyKey, "", "", null, "", null, null,
                "", "low", false, TaskOrigin.shared());
    }

    /** Compatibility constructor for the pre-principal request shape. */
    public CreateTaskRequest(String machineId, TaskCommand command, String idempotencyKey,
                             String projectId, String worktreeId, ScopeMode scopeMode, String scopeRoot,
                             String sessionId, String risk, boolean elevationRequired) {
        this(machineId, command, idempotencyKey, projectId, worktreeId, scopeMode, scopeRoot,
                null, null, sessionId, risk, elevationRequired, TaskOrigin.shared());
    }

    /** Compatibility constructor for callers that already carry an owner. */
    public CreateTaskRequest(String machineId, TaskCommand command, String idempotencyKey,
                             String projectId, String worktreeId, ScopeMode scopeMode, String scopeRoot,
                             String sessionId, String risk, boolean elevationRequired, TaskOrigin origin) {
        this(machineId, command, idempotencyKey, projectId, worktreeId, scopeMode, scopeRoot,
                null, null, sessionId, risk, elevationRequired, origin);
    }

    public CreateTaskRequest {
        machineId = machineId == null ? "" : machineId.trim();
        idempotencyKey = idempotencyKey == null ? "" : idempotencyKey.trim();
        projectId = projectId == null ? "" : projectId.trim();
        worktreeId = worktreeId == null ? "" : worktreeId.trim();
        scopeRoot = scopeRoot == null ? "" : scopeRoot.trim();
        workspacePolicy = workspacePolicy == null ? null : workspacePolicy;
        laneMode = laneMode == null ? null : laneMode;
        sessionId = sessionId == null ? "" : sessionId.trim();
        risk = risk == null ? "" : risk.trim();
        origin = origin == null ? TaskOrigin.shared() : origin;
        if (command == null) {
            throw new IllegalArgumentException("command is required");
        }
    }
}
