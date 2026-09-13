package com.prodigalgal.remoteconnectmcp.agent;

import java.time.Duration;

/**
 * A bounded wake-up primitive for the optional WebSocket hint channel. It
 * never carries task data; the normal authenticated poll remains the source
 * of truth, so a lost wake-up is harmless.
 */
final class AgentWakeSignal {
    private final Object monitor = new Object();
    private boolean pending;

    void signal() {
        synchronized (monitor) {
            pending = true;
            monitor.notifyAll();
        }
    }

    void await(Duration timeout) throws InterruptedException {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return;
        }
        synchronized (monitor) {
            if (pending) {
                pending = false;
                return;
            }
            var remainingNanos = timeout.toNanos();
            while (!pending && remainingNanos > 0) {
                var started = System.nanoTime();
                var millis = Math.max(1L, Math.min(remainingNanos / 1_000_000L, Long.MAX_VALUE));
                var nanos = Math.toIntExact(Math.min(999_999L, Math.max(0L, remainingNanos - millis * 1_000_000L)));
                monitor.wait(millis, nanos);
                remainingNanos -= Math.max(1L, System.nanoTime() - started);
            }
            pending = false;
        }
    }
}
