package com.prodigalgal.remoteconnectmcp.center;

import com.prodigalgal.remoteconnectmcp.protocol.LaneMode;
import java.time.Instant;

/** Compact, non-secret task projection used by the console and MCP. */
public record TaskView(
        String id,
        String machineId,
        String kind,
        String requiredCapability,
        String command,
        String cwd,
        int timeoutSeconds,
        String status,
        int attempt,
        Integer exitCode,
        String error,
        long outputBytes,
        boolean outputTruncated,
        Instant createdAt,
        Instant dispatchedAt,
        Instant startedAt,
        Instant finishedAt,
        Instant leaseUntil,
        long artifactBytes,
        String artifactMime,
        String artifactSha256,
        LaneMode laneMode,
        String risk,
        Instant contractExpiresAt,
        String executionSessionId,
        String resultChannel,
        long changeSequence,
        String progressPhase,
        Integer progressPercent,
        String progressMessage,
        Long progressCurrent,
        Long progressTotal,
        String progressUnit,
        Instant progressUpdatedAt,
        Instant metadataExpiresAt,
        Instant outputExpiresAt,
        boolean pinned,
        Instant archivedAt) {

    public TaskView(TaskState task) {
        this(task.id(), task.machineId(), task.command().kind().wireValue(), task.command().requiredCapability(),
                task.command().command(), task.command().cwd(), task.command().timeoutSeconds(), task.status(),
                task.attempt(), task.exitCode(), task.error(), task.outputBytes(), task.outputTruncated(), task.createdAt(),
                task.dispatchedAt(), task.startedAt(), task.finishedAt(), task.leaseUntil(), task.artifactBytes(),
                task.artifactMime(), task.artifactSha256(),
                task.command().contract() == null ? null : task.command().contract().laneMode(),
                task.command().contract() == null ? null : task.command().contract().risk(),
                task.command().contract() == null ? null : task.command().contract().expiresAt(),
                task.executionSessionId(), task.resultChannel(), task.changeSequence(), task.progressPhase(),
                task.progressPercent(), task.progressMessage(), task.progressCurrent(), task.progressTotal(),
                task.progressUnit(), task.progressUpdatedAt(), task.metadataExpiresAt(), task.outputExpiresAt(),
                task.pinned(), task.archivedAt());
    }

}
