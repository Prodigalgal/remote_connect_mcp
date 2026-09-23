package com.prodigalgal.remoteconnectmcp.center;

import com.prodigalgal.remoteconnectmcp.protocol.LaneMode;
import com.prodigalgal.remoteconnectmcp.protocol.TaskCommand;

/** Validated request used by MCP and the console to enqueue a full-host task. */
public record CreateTaskRequest(
        String machineId,
        TaskCommand command,
        String idempotencyKey,
        LaneMode laneMode,
        String sessionId,
        String risk,
        boolean elevationRequired,
        TaskOrigin origin,
        boolean readOnlyLaneHint) {

    public CreateTaskRequest(String machineId, TaskCommand command, String idempotencyKey) {
        this(machineId, command, idempotencyKey, null, "", "low", false,
                TaskOrigin.configured(), false);
    }

    public CreateTaskRequest(String machineId, TaskCommand command, String idempotencyKey,
                             LaneMode laneMode, String sessionId, String risk,
                             boolean elevationRequired, TaskOrigin origin) {
        this(machineId, command, idempotencyKey, laneMode, sessionId, risk,
                elevationRequired, origin, false);
    }

    public CreateTaskRequest {
        machineId = machineId == null ? "" : machineId.trim();
        idempotencyKey = idempotencyKey == null ? "" : idempotencyKey.trim();
        laneMode = laneMode == null ? null : laneMode;
        sessionId = sessionId == null ? "" : sessionId.trim();
        risk = risk == null ? "" : risk.trim();
        origin = origin == null ? TaskOrigin.configured() : origin;
        if (command == null) throw new IllegalArgumentException("command is required");
    }

}
