package com.prodigalgal.remoteconnectmcp.center;

import com.prodigalgal.remoteconnectmcp.protocol.TaskCommand;

/** Validated request used by MCP and the console to enqueue a task. */
public record CreateTaskRequest(
        String machineId,
        TaskCommand command,
        String idempotencyKey) {

    public CreateTaskRequest {
        machineId = machineId == null ? "" : machineId.trim();
        idempotencyKey = idempotencyKey == null ? "" : idempotencyKey.trim();
        if (command == null) {
            throw new IllegalArgumentException("command is required");
        }
    }
}
