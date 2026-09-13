package com.prodigalgal.remoteconnectmcp.center;

import java.time.Instant;

/** Bounded projection of an Agent-local Git worktree operation. */
public record WorktreeView(
        String id,
        String projectId,
        String ref,
        String path,
        String operation,
        String status,
        String taskId,
        Instant createdAt,
        Instant updatedAt) {
}
