package com.prodigalgal.remoteconnectmcp.center;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.prodigalgal.remoteconnectmcp.protocol.LaneMode;
import com.prodigalgal.remoteconnectmcp.protocol.TaskCommand;
import com.prodigalgal.remoteconnectmcp.protocol.TaskKind;
import java.util.Map;

/** Flat console-facing task request. Every Agent task has full-host access. */
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
        @JsonProperty("session_id") String sessionId,
        String risk,
        @JsonProperty("elevation_required") Boolean elevationRequired,
        @JsonProperty("lane_mode") LaneMode laneMode,
        TaskCommand.DesktopAction desktop) {

    public AdminCreateTaskRequest(String machineId, String command, String cwd, Map<String, String> env,
                                  Integer timeoutSeconds, String idempotencyKey) {
        this(machineId, "command", "command", command, cwd, env, timeoutSeconds, idempotencyKey,
                "", "low", false, null, null);
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
        sessionId = sessionId == null ? "" : sessionId.trim();
        risk = risk == null ? "" : risk.trim();
        elevationRequired = elevationRequired == null ? Boolean.FALSE : elevationRequired;
    }

    public CreateTaskRequest toInternal() {
        var task = new com.prodigalgal.remoteconnectmcp.protocol.TaskCommand(
                "", TaskKind.fromWireValue(kind), requiredCapability,
                command, cwd.isBlank() ? null : cwd, env, timeoutSeconds, desktop,
                java.time.Instant.now(), null, 0, null);
        return new CreateTaskRequest(machineId, task, idempotencyKey, laneMode, sessionId,
                risk, elevationRequired, TaskOrigin.configured());
    }
}
