package com.prodigalgal.remoteconnectmcp.agent;

import com.prodigalgal.remoteconnectmcp.protocol.ExecutionContract;
import com.prodigalgal.remoteconnectmcp.protocol.TaskCommand;

/**
 * Effective per-task limits.  The registered Agent limits are the outer
 * ceiling; a Center-issued contract may narrow them but can never enlarge
 * them.  Keeping this calculation in one place prevents command, browser and
 * desktop paths from drifting apart.
 */
final class TaskLimits {
    private TaskLimits() {
    }

    static long outputBytes(AgentConfig config, TaskCommand task) {
        var limit = config.maxOutputBytes();
        var contract = contract(task);
        if (contract != null) limit = Math.min(limit, contract.budget().maxOutputBytes());
        return limit;
    }

    static long artifactBytes(TaskCommand task, long hardLimit) {
        var contract = contract(task);
        return contract == null ? hardLimit : Math.min(hardLimit, contract.budget().maxArtifactBytes());
    }

    /** Return a task timeout, or the caller's bounded fallback when unlimited. */
    static int timeoutSeconds(TaskCommand task, int fallback) {
        var taskTimeout = task == null ? 0 : task.timeoutSeconds();
        var contract = contract(task);
        var contractTimeout = contract == null ? 0 : contract.budget().maxDurationSeconds();
        var effective = taskTimeout > 0 && contractTimeout > 0
                ? Math.min(taskTimeout, contractTimeout)
                : Math.max(taskTimeout, contractTimeout);
        return effective > 0 ? effective : fallback;
    }

    private static ExecutionContract contract(TaskCommand task) {
        return task == null ? null : task.contract();
    }
}
