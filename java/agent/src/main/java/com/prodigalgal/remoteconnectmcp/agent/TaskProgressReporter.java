package com.prodigalgal.remoteconnectmcp.agent;

import com.prodigalgal.remoteconnectmcp.protocol.TaskCommand;
import com.prodigalgal.remoteconnectmcp.protocol.TaskProgressUpdate;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Best-effort progress delivery; task execution must not fail only because a snapshot was lost. */
final class TaskProgressReporter {
    private TaskProgressReporter() {
    }

    static void send(Logger logger, AgentTransport transport, AgentIdentity identity,
                     TaskCommand task, TaskProgressUpdate progress) {
        try {
            // Progress is advisory and must never hold the child process at a
            // retry loop.  Durable state/output delivery remains responsible
            // for recovery; a later task_read will observe the next snapshot.
            transport.updateProgress(identity.machineId(), identity.token(), task.id(), task.attempt(), progress);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException exception) {
            logger.log(Level.FINE, "could not report task progress " + task.id(), exception);
        }
    }
}
