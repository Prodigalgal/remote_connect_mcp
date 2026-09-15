package com.prodigalgal.remoteconnectmcp.agent;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Host-local process budget shared by all task supervisors in one command
 * Agent.  It is an admission/capacity guard, not a task queue: a task that
 * cannot reserve its newly observed process tree is terminated by its owning
 * supervisor and reports a bounded failure to Center.
 */
final class AgentProcessBudget {
    private final int maxProcesses;
    private final AtomicInteger usedProcesses = new AtomicInteger();

    AgentProcessBudget(int maxProcesses) {
        if (maxProcesses < 1 || maxProcesses > 4096) {
            throw new IllegalArgumentException("max process budget is outside the allowed range");
        }
        this.maxProcesses = maxProcesses;
    }

    int tryReserve(int requested) {
        if (requested <= 0) return 0;
        while (true) {
            var current = usedProcesses.get();
            if (current >= maxProcesses) return 0;
            var granted = Math.min(requested, maxProcesses - current);
            if (usedProcesses.compareAndSet(current, current + granted)) return granted;
        }
    }

    void release(int processes) {
        if (processes <= 0) return;
        usedProcesses.getAndUpdate(current -> Math.max(0, current - processes));
    }

    int maxProcesses() {
        return maxProcesses;
    }

    int usedProcesses() {
        return usedProcesses.get();
    }
}
