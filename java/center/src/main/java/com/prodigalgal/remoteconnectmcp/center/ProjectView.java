package com.prodigalgal.remoteconnectmcp.center;

import java.time.Instant;
import java.util.List;

/** Bounded project projection; repository contents never cross the Center. */
public record ProjectView(
        String id,
        String machineId,
        String name,
        String rootPath,
        String repositoryPath,
        String defaultRef,
        Instant createdAt,
        Instant updatedAt,
        List<WorktreeView> worktrees) {

    public ProjectView {
        worktrees = worktrees == null ? List.of() : List.copyOf(worktrees);
    }
}
