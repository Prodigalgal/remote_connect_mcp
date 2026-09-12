package com.prodigalgal.remoteconnectmcp.agent;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-local byte budget shared by ordinary task output spools.
 *
 * The budget is a soft capture limit rather than a task admission gate: when
 * the aggregate ceiling is reached, a task keeps running and only subsequent
 * output is marked truncated.  This preserves command usability while
 * preventing a high concurrency setting from turning an offline Agent into an
 * unbounded disk consumer.
 */
final class AgentResourceBudget {
    private final long maxBytes;
    private final AtomicLong usedBytes = new AtomicLong();

    AgentResourceBudget(long maxBytes) {
        if (maxBytes < 1) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        this.maxBytes = maxBytes;
    }

    long tryReserve(long requested) {
        if (requested <= 0) return 0;
        while (true) {
            var current = usedBytes.get();
            if (current >= maxBytes) return 0;
            var granted = Math.min(requested, maxBytes - current);
            if (usedBytes.compareAndSet(current, current + granted)) return granted;
        }
    }

    void release(long bytes) {
        if (bytes <= 0) return;
        usedBytes.getAndUpdate(current -> Math.max(0L, current - bytes));
    }

    long maxBytes() {
        return maxBytes;
    }

    long usedBytes() {
        return usedBytes.get();
    }
}
