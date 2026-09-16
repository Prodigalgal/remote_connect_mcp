package com.prodigalgal.remoteconnectmcp.center;

import com.prodigalgal.remoteconnectmcp.protocol.TaskCommand;
import java.io.ByteArrayOutputStream;
import java.time.Instant;

/** Mutable Center-side task state; all access is serialized by TaskService. */
final class TaskState {
    private final String id;
    private final String machineId;
    private final TaskCommand command;
    private final String idempotencyKey;
    private final Instant createdAt;
    private final TaskOrigin origin;
    private final String laneKey;
    /** Durable correlation identity for one principal/transport session. */
    private final String executionSessionId;
    /** Task-specific result route; never publish output to a session-wide bus. */
    private final String resultChannel;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private String status = TaskStatus.QUEUED;
    /** Number of Center dispatch attempts; increments only when a lease is claimed. */
    private int attempt;
    private Integer exitCode;
    private String error;
    private boolean outputTruncated;
    /** Confirmed output cursor. Kept separately so metadata-only JDBC reads do not load the blob. */
    private long outputByteCount;
    private Instant dispatchedAt;
    private Instant startedAt;
    private Instant finishedAt;
    private Instant leaseUntil;
    private long artifactBytes;
    private String artifactMime;
    private String artifactSha256;
    private byte[] artifactData;

    TaskState(String id, String machineId, TaskCommand command, String idempotencyKey, Instant createdAt) {
        this(id, machineId, command, idempotencyKey, createdAt, TaskOrigin.shared());
    }

    TaskState(String id, String machineId, TaskCommand command, String idempotencyKey, Instant createdAt,
              TaskOrigin origin) {
        this(id, machineId, command, idempotencyKey, createdAt, origin,
                ExecutionLaneKey.derive(machineId, command == null ? null : command.contract()));
    }

    TaskState(String id, String machineId, TaskCommand command, String idempotencyKey, Instant createdAt,
              TaskOrigin origin, String laneKey) {
        this(id, machineId, command, idempotencyKey, createdAt, origin, laneKey,
                deriveExecutionSessionId(command, origin), deriveResultChannel(id));
    }

    TaskState(String id, String machineId, TaskCommand command, String idempotencyKey, Instant createdAt,
              TaskOrigin origin, String laneKey, String executionSessionId, String resultChannel) {
        this.id = id;
        this.machineId = machineId;
        this.command = command;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = createdAt;
        this.origin = origin == null ? TaskOrigin.shared() : origin;
        this.laneKey = laneKey == null || laneKey.isBlank()
                ? ExecutionLaneKey.derive(machineId, command == null ? null : command.contract()) : laneKey.trim();
        this.executionSessionId = normalizeCorrelation(executionSessionId,
                deriveExecutionSessionId(command, this.origin));
        this.resultChannel = normalizeCorrelation(resultChannel, deriveResultChannel(id));
    }

    static TaskState restore(String id, String machineId, TaskCommand command, String idempotencyKey,
                             Instant createdAt, String status, int attempt, Integer exitCode, String error,
                             boolean outputTruncated, Instant dispatchedAt, Instant startedAt,
                             Instant finishedAt, Instant leaseUntil, byte[] output,
                             long artifactBytes, String artifactMime, String artifactSha256, byte[] artifactData) {
        return restore(id, machineId, command, idempotencyKey, createdAt, status, attempt, exitCode, error,
                outputTruncated, dispatchedAt, startedAt, finishedAt, leaseUntil, output, artifactBytes,
                artifactMime, artifactSha256, artifactData, TaskOrigin.shared());
    }

    static TaskState restore(String id, String machineId, TaskCommand command, String idempotencyKey,
                             Instant createdAt, String status, int attempt, Integer exitCode, String error,
                             boolean outputTruncated, Instant dispatchedAt, Instant startedAt,
                             Instant finishedAt, Instant leaseUntil, byte[] output,
                             long artifactBytes, String artifactMime, String artifactSha256, byte[] artifactData,
                             TaskOrigin origin) {
        var state = new TaskState(id, machineId, command, idempotencyKey, createdAt, origin);
        return restoreInto(state, status, attempt, exitCode, error, outputTruncated, dispatchedAt, startedAt,
                finishedAt, leaseUntil, output, artifactBytes, artifactMime, artifactSha256, artifactData);
    }

    static TaskState restore(String id, String machineId, TaskCommand command, String idempotencyKey,
                             Instant createdAt, String status, int attempt, Integer exitCode, String error,
                             boolean outputTruncated, Instant dispatchedAt, Instant startedAt,
                             Instant finishedAt, Instant leaseUntil, byte[] output,
                             long artifactBytes, String artifactMime, String artifactSha256, byte[] artifactData,
                             TaskOrigin origin, String laneKey, String executionSessionId, String resultChannel) {
        var state = new TaskState(id, machineId, command, idempotencyKey, createdAt, origin, laneKey,
                executionSessionId, resultChannel);
        return restoreInto(state, status, attempt, exitCode, error, outputTruncated, dispatchedAt, startedAt,
                finishedAt, leaseUntil, output, artifactBytes, artifactMime, artifactSha256, artifactData);
    }

    static TaskState restore(String id, String machineId, TaskCommand command, String idempotencyKey,
                             Instant createdAt, String status, int attempt, Integer exitCode, String error,
                             boolean outputTruncated, Instant dispatchedAt, Instant startedAt,
                             Instant finishedAt, Instant leaseUntil, byte[] output,
                             long artifactBytes, String artifactMime, String artifactSha256, byte[] artifactData,
                             TaskOrigin origin, String laneKey) {
        var state = new TaskState(id, machineId, command, idempotencyKey, createdAt, origin, laneKey);
        return restoreInto(state, status, attempt, exitCode, error, outputTruncated, dispatchedAt, startedAt,
                finishedAt, leaseUntil, output, artifactBytes, artifactMime, artifactSha256, artifactData);
    }

    private static TaskState restoreInto(TaskState state, String status, int attempt, Integer exitCode, String error,
                                         boolean outputTruncated, Instant dispatchedAt, Instant startedAt,
                                         Instant finishedAt, Instant leaseUntil, byte[] output,
                                         long artifactBytes, String artifactMime, String artifactSha256, byte[] artifactData) {
        state.status(status);
        state.attempt(attempt);
        state.exitCode(exitCode);
        state.error(error);
        state.outputTruncated(outputTruncated);
        state.dispatchedAt(dispatchedAt);
        state.startedAt(startedAt);
        state.finishedAt(finishedAt);
        state.leaseUntil(leaseUntil);
        state.artifactBytes(artifactBytes);
        state.artifactMime(artifactMime);
        state.artifactSha256(artifactSha256);
        state.artifactData(artifactData);
        if (output != null && output.length > 0) {
            state.output().writeBytes(output);
        }
        state.outputBytes(output == null ? 0L : output.length);
        return state;
    }

    String id() { return id; }
    String machineId() { return machineId; }
    TaskCommand command() { return command; }
    String idempotencyKey() { return idempotencyKey; }
    Instant createdAt() { return createdAt; }
    TaskOrigin origin() { return origin; }
    String laneKey() { return laneKey; }
    String executionSessionId() { return executionSessionId; }
    String resultChannel() { return resultChannel; }
    ByteArrayOutputStream output() { return output; }
    String status() { return status; }
    void status(String value) { status = value; }
    int attempt() { return attempt; }
    void attempt(int value) { attempt = Math.max(0, value); }
    Integer exitCode() { return exitCode; }
    void exitCode(Integer value) { exitCode = value; }
    String error() { return error; }
    void error(String value) { error = value; }
    boolean outputTruncated() { return outputTruncated; }
    void outputTruncated(boolean value) { outputTruncated = value; }
    Instant dispatchedAt() { return dispatchedAt; }
    void dispatchedAt(Instant value) { dispatchedAt = value; }
    Instant startedAt() { return startedAt; }
    void startedAt(Instant value) { startedAt = value; }
    Instant finishedAt() { return finishedAt; }
    void finishedAt(Instant value) { finishedAt = value; }
    Instant leaseUntil() { return leaseUntil; }
    void leaseUntil(Instant value) { leaseUntil = value; }
    long artifactBytes() { return artifactBytes; }
    void artifactBytes(long value) { artifactBytes = value; }
    String artifactMime() { return artifactMime; }
    void artifactMime(String value) { artifactMime = value; }
    String artifactSha256() { return artifactSha256; }
    void artifactSha256(String value) { artifactSha256 = value; }
    byte[] artifactData() { return artifactData == null ? new byte[0] : artifactData.clone(); }
    void artifactData(byte[] value) { artifactData = value == null ? null : value.clone(); }

    long outputBytes() { return outputByteCount; }
    void outputBytes(long value) { outputByteCount = Math.max(0L, value); }

    private static String deriveExecutionSessionId(TaskCommand command, TaskOrigin origin) {
        var contract = command == null ? null : command.contract();
        if (contract != null && contract.sessionId() != null && !contract.sessionId().isBlank()) {
            return contract.sessionId().trim();
        }
        return origin == null ? TaskOrigin.shared().connectionId() : origin.connectionId();
    }

    private static String deriveResultChannel(String taskId) {
        var value = taskId == null || taskId.isBlank() ? "unknown" : taskId.trim();
        return "rcm.task." + value;
    }

    private static String normalizeCorrelation(String value, String fallback) {
        var normalized = value == null || value.isBlank() ? fallback : value.trim();
        if (normalized == null || normalized.isBlank() || normalized.length() > 256
                || normalized.indexOf('\u0000') >= 0 || normalized.indexOf('\r') >= 0
                || normalized.indexOf('\n') >= 0
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("task correlation is invalid");
        }
        return normalized;
    }
}
