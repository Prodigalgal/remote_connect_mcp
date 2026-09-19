package com.prodigalgal.remoteconnectmcp.agent;

import com.prodigalgal.remoteconnectmcp.protocol.TaskCommand;
import com.prodigalgal.remoteconnectmcp.protocol.TaskProgressUpdate;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Best-effort progress delivery; task execution must not fail only because a snapshot was lost. */
final class TaskProgressReporter {
    private static final ExecutorService EXECUTOR = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("rcm-progress-upload", 0).factory());
    private static final Semaphore IN_FLIGHT = new Semaphore(128);
    private static final ConcurrentHashMap<String, Boolean> TASKS_IN_FLIGHT = new ConcurrentHashMap<>();

    private TaskProgressReporter() {
    }

    static void send(Logger logger, AgentTransport transport, AgentIdentity identity,
                     TaskCommand task, TaskProgressUpdate progress) {
        if (logger == null || transport == null || identity == null || task == null || progress == null) return;
        var taskId = task.id();
        // Progress is advisory. Never make the command/desktop/browser/file
        // runner wait on the Center, and never queue an unbounded number of
        // snapshots when a connection is stalled. One in-flight update per
        // task is enough because the next terminal/task snapshot is the
        // authoritative state and Attempt fencing rejects stale updates.
        if (taskId == null || TASKS_IN_FLIGHT.putIfAbsent(taskId, Boolean.TRUE) != null) return;
        if (!IN_FLIGHT.tryAcquire()) {
            TASKS_IN_FLIGHT.remove(taskId);
            return;
        }
        try {
            EXECUTOR.execute(() -> {
                try {
                    transport.updateProgress(identity.machineId(), identity.token(), taskId, task.attempt(), progress);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } catch (IOException | RuntimeException exception) {
                    logger.log(Level.FINE, "could not report task progress " + taskId, exception);
                } finally {
                    TASKS_IN_FLIGHT.remove(taskId);
                    IN_FLIGHT.release();
                }
            });
        } catch (RuntimeException rejected) {
            TASKS_IN_FLIGHT.remove(taskId);
            IN_FLIGHT.release();
            logger.log(Level.FINE, "could not schedule task progress " + taskId, rejected);
        }
    }
}
