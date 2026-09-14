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

    /** Effective wall-clock ceiling; zero means the task remains durable/unlimited. */
    static long durationSeconds(AgentConfig config, TaskCommand task) {
        var configured = config == null ? 0 : config.maxTaskDurationSeconds();
        var contract = contract(task);
        var contractDuration = contract == null ? 0 : contract.budget().maxDurationSeconds();
        var taskDuration = task == null ? 0 : task.timeoutSeconds();
        return positiveMinimum(configured, contractDuration, taskDuration);
    }

    /** Effective process-tree ceiling. The Agent setting is the outer bound. */
    static int childProcesses(AgentConfig config, TaskCommand task) {
        var configured = config == null ? 32 : config.maxTaskChildProcesses();
        var contract = contract(task);
        return contract == null ? configured : Math.min(configured, contract.budget().maxChildProcesses());
    }

    /** Effective RSS ceiling; zero means no platform-specific RSS limit. */
    static long rssBytes(AgentConfig config, TaskCommand task) {
        var configured = config == null ? 0 : config.maxTaskRssBytes();
        var contract = contract(task);
        var contractRss = contract == null ? 0 : contract.budget().maxRssBytes();
        return positiveMinimum(configured, contractRss);
    }

    /** Effective CPU-time ceiling; zero means no CPU-time limit. */
    static long cpuSeconds(AgentConfig config, TaskCommand task) {
        var configured = config == null ? 0 : config.maxTaskCpuSeconds();
        var contract = contract(task);
        var contractCpu = contract == null ? 0 : contract.budget().maxCpuSeconds();
        return positiveMinimum(configured, contractCpu);
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

    private static long positiveMinimum(long... values) {
        var result = 0L;
        for (var value : values) {
            if (value <= 0) continue;
            result = result <= 0 ? value : Math.min(result, value);
        }
        return result;
    }

    private static ExecutionContract contract(TaskCommand task) {
        return task == null ? null : task.contract();
    }
}
