package com.prodigalgal.remoteconnectmcp.agent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounds desktop processes started by the command Agent when no interactive
 * companion is available.  The companion has its own budget because it is a
 * separate user-session process; this budget prevents the headless fallback
 * from becoming an unbounded process launcher.
 */
final class DesktopProcessBudget implements AutoCloseable {
    private static final int DEFAULT_MAX_LAUNCHED_PROCESSES = 16;
    private static final int MAX_ALLOWED_LAUNCHED_PROCESSES = 64;

    private final int maxLaunchedProcesses;
    private final AgentProcessBudget processBudget;
    private final Semaphore slots;
    private final ConcurrentMap<Long, ProcessHandle> processes = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    DesktopProcessBudget() {
        this(parseBounded(System.getenv("REMOTE_CONNECT_MCP_AGENT_DESKTOP_MAX_LAUNCHED_PROCESSES")), null);
    }

    DesktopProcessBudget(int maxLaunchedProcesses) {
        this(maxLaunchedProcesses, null);
    }

    DesktopProcessBudget(AgentProcessBudget processBudget) {
        this(parseBounded(System.getenv("REMOTE_CONNECT_MCP_AGENT_DESKTOP_MAX_LAUNCHED_PROCESSES")), processBudget);
    }

    DesktopProcessBudget(int maxLaunchedProcesses, AgentProcessBudget processBudget) {
        if (maxLaunchedProcesses < 1 || maxLaunchedProcesses > MAX_ALLOWED_LAUNCHED_PROCESSES) {
            throw new IllegalArgumentException("desktop launch limit is outside the allowed range");
        }
        this.maxLaunchedProcesses = maxLaunchedProcesses;
        this.processBudget = processBudget;
        this.slots = new Semaphore(maxLaunchedProcesses);
    }

    ProcessHandle start(ProcessBuilder builder) throws IOException {
        if (closed.get()) {
            throw new IOException("desktop process budget is closed");
        }
        if (!slots.tryAcquire()) {
            throw new IOException("desktop launch limit reached (" + maxLaunchedProcesses + ")");
        }
        var processReservation = processBudget == null ? 0 : processBudget.tryReserve(1);
        if (processBudget != null && processReservation < 1) {
            slots.release();
            throw new IOException("Agent process budget exceeded " + processBudget.maxProcesses() + " processes");
        }
        ProcessHandle handle = null;
        var registered = false;
        try {
            // Serialize the closed check with registration.  Without this
            // small critical section, close() could finish between the check
            // and processes.put(), leaving a newly-started GUI process
            // untracked and its global reservation leaked.
            synchronized (this) {
                if (closed.get()) throw new IOException("desktop process budget is closed");
                var process = builder.start();
                handle = process.toHandle();
                processes.put(handle.pid(), handle);
                registered = true;
            }
            var tracked = handle;
            tracked.onExit().thenRun(() -> {
                if (processes.remove(tracked.pid(), tracked)) {
                    slots.release();
                    if (processBudget != null) processBudget.release(1);
                }
            });
            return tracked;
        } catch (Exception exception) {
            if (registered && handle != null) {
                // close() or the onExit callback may have already accounted
                // for this entry.  Only the successful remove owns cleanup.
                if (processes.remove(handle.pid(), handle)) {
                    terminate(handle);
                    slots.release();
                    if (processBudget != null) processBudget.release(1);
                }
            } else {
                slots.release();
                if (processBudget != null && processReservation > 0) processBudget.release(processReservation);
            }
            if (exception instanceof IOException io) throw io;
            throw new IOException("could not launch desktop process", exception);
        }
    }

    /**
     * Stop every process launched through the headless desktop fallback and
     * release both local and Agent-wide process reservations.  A launch may
     * intentionally outlive its task, but it must not outlive the Agent that
     * owns the launch budget (for example during an upgrade or shutdown).
     */
    @Override
    public void close() {
        List<Map.Entry<Long, ProcessHandle>> snapshot;
        synchronized (this) {
            if (!closed.compareAndSet(false, true)) return;
            snapshot = new ArrayList<>(processes.entrySet());
        }
        snapshot.forEach(entry -> {
            var pid = entry.getKey();
            var handle = entry.getValue();
            if (processes.remove(pid, handle)) {
                terminate(handle);
                slots.release();
                if (processBudget != null) processBudget.release(1);
            }
        });
    }

    private static void terminate(ProcessHandle process) {
        if (process == null) return;
        try {
            process.descendants().forEach(child -> {
                try {
                    child.destroy();
                    if (child.isAlive()) child.destroyForcibly();
                } catch (RuntimeException ignored) { }
            });
            process.destroy();
            if (process.isAlive()) process.destroyForcibly();
        } catch (RuntimeException ignored) {
            // The process can exit between discovery and termination.
        }
    }

    private static int parseBounded(String value) {
        if (value == null || value.isBlank()) return DEFAULT_MAX_LAUNCHED_PROCESSES;
        try {
            var parsed = Integer.parseInt(value.trim());
            if (parsed < 1 || parsed > MAX_ALLOWED_LAUNCHED_PROCESSES) {
                throw new IllegalArgumentException("REMOTE_CONNECT_MCP_AGENT_DESKTOP_MAX_LAUNCHED_PROCESSES is outside the allowed range");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("REMOTE_CONNECT_MCP_AGENT_DESKTOP_MAX_LAUNCHED_PROCESSES must be an integer", exception);
        }
    }
}
