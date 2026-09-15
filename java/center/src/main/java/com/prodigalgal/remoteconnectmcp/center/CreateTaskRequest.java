package com.prodigalgal.remoteconnectmcp.center;

import com.prodigalgal.remoteconnectmcp.protocol.TaskCommand;
import com.prodigalgal.remoteconnectmcp.protocol.ScopeMode;

/** Validated request used by MCP and the console to enqueue a task. */
public record CreateTaskRequest(
        String machineId,
        TaskCommand command,
        String idempotencyKey,
        String projectId,
        String worktreeId,
        ScopeMode scopeMode,
        String scopeRoot,
        String sessionId,
        String risk,
        boolean elevationRequired) {

    /** Compatibility constructor for callers written before execution contracts. */
    public CreateTaskRequest(String machineId, TaskCommand command, String idempotencyKey) {
        this(machineId, command, idempotencyKey, "", "", null, "", "", "low", false);
    }

    public CreateTaskRequest {
        machineId = machineId == null ? "" : machineId.trim();
        idempotencyKey = idempotencyKey == null ? "" : idempotencyKey.trim();
        projectId = projectId == null ? "" : projectId.trim();
        worktreeId = worktreeId == null ? "" : worktreeId.trim();
        scopeRoot = scopeRoot == null ? "" : scopeRoot.trim();
        sessionId = sessionId == null ? "" : sessionId.trim();
        risk = risk == null ? "" : risk.trim();
        if (command == null) {
            throw new IllegalArgumentException("command is required");
        }
    }
}
