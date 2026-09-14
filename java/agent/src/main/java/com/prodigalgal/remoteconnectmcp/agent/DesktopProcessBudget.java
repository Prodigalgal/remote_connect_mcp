package com.prodigalgal.remoteconnectmcp.agent;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;

/**
 * Bounds desktop processes started by the command Agent when no interactive
 * companion is available.  The companion has its own budget because it is a
 * separate user-session process; this budget prevents the headless fallback
 * from becoming an unbounded process launcher.
 */
final class DesktopProcessBudget {
    private static final int DEFAULT_MAX_LAUNCHED_PROCESSES = 16;
    private static final int MAX_ALLOWED_LAUNCHED_PROCESSES = 64;

    private final int maxLaunchedProcesses;
    private final Semaphore slots;
    private final ConcurrentMap<Long, ProcessHandle> processes = new ConcurrentHashMap<>();

    DesktopProcessBudget() {
        this(parseBounded(System.getenv("REMOTE_CONNECT_MCP_AGENT_DESKTOP_MAX_LAUNCHED_PROCESSES")));
    }

    DesktopProcessBudget(int maxLaunchedProcesses) {
        if (maxLaunchedProcesses < 1 || maxLaunchedProcesses > MAX_ALLOWED_LAUNCHED_PROCESSES) {
            throw new IllegalArgumentException("desktop launch limit is outside the allowed range");
        }
        this.maxLaunchedProcesses = maxLaunchedProcesses;
        this.slots = new Semaphore(maxLaunchedProcesses);
    }

    ProcessHandle start(ProcessBuilder builder) throws IOException {
        if (!slots.tryAcquire()) {
            throw new IOException("desktop launch limit reached (" + maxLaunchedProcesses + ")");
        }
        try {
            var process = builder.start();
            var handle = process.toHandle();
            processes.put(handle.pid(), handle);
            handle.onExit().thenRun(() -> {
                if (processes.remove(handle.pid(), handle)) slots.release();
            });
            return handle;
        } catch (Exception exception) {
            slots.release();
            if (exception instanceof IOException io) throw io;
            throw new IOException("could not launch desktop process", exception);
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
