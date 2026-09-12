package com.prodigalgal.remoteconnectmcp.center;

import com.prodigalgal.remoteconnectmcp.protocol.TaskCommand;
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
        String artifactSha256) {

    public TaskView(TaskState task) {
        this(task.id(), task.machineId(), task.command().kind().wireValue(), task.command().requiredCapability(),
                task.command().command(), task.command().cwd(), task.command().timeoutSeconds(), task.status(),
                task.exitCode(), task.error(), task.outputBytes(), task.outputTruncated(), task.createdAt(),
                task.dispatchedAt(), task.startedAt(), task.finishedAt(), task.leaseUntil(), task.artifactBytes(),
                task.artifactMime(), task.artifactSha256());
    }
}
