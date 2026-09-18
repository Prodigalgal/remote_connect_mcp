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
        TaskOrigin origin,
        boolean readOnlyLaneHint) {

    /** Construction overload with default execution policy fields. */
    public CreateTaskRequest(String machineId, TaskCommand command, String idempotencyKey) {
        this(machineId, command, idempotencyKey, "", "", null, "", null, null,
                "", "low", false, TaskOrigin.configured(), false);
    }

    /** Construction overload with default owner policy. */
    public CreateTaskRequest(String machineId, TaskCommand command, String idempotencyKey,
                             String projectId, String worktreeId, ScopeMode scopeMode, String scopeRoot,
                             String sessionId, String risk, boolean elevationRequired) {
        this(machineId, command, idempotencyKey, projectId, worktreeId, scopeMode, scopeRoot,
                null, null, sessionId, risk, elevationRequired, TaskOrigin.configured(), false);
    }

    /** Construction overload with an explicit owner. */
    public CreateTaskRequest(String machineId, TaskCommand command, String idempotencyKey,
                             String projectId, String worktreeId, ScopeMode scopeMode, String scopeRoot,
                             String sessionId, String risk, boolean elevationRequired, TaskOrigin origin) {
        this(machineId, command, idempotencyKey, projectId, worktreeId, scopeMode, scopeRoot,
                null, null, sessionId, risk, elevationRequired, origin, false);
    }

    /** Construction overload with default lane hint. */
    public CreateTaskRequest(String machineId, TaskCommand command, String idempotencyKey,
                             String projectId, String worktreeId, ScopeMode scopeMode, String scopeRoot,
                             WorkspacePolicyMode workspacePolicy, LaneMode laneMode, String sessionId,
                             String risk, boolean elevationRequired, TaskOrigin origin) {
        this(machineId, command, idempotencyKey, projectId, worktreeId, scopeMode, scopeRoot,
                workspacePolicy, laneMode, sessionId, risk, elevationRequired, origin, false);
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
        origin = origin == null ? TaskOrigin.configured() : origin;
        if (command == null) {
            throw new IllegalArgumentException("command is required");
        }
    }
}
