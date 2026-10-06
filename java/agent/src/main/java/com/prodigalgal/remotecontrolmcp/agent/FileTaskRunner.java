package com.prodigalgal.remotecontrolmcp.agent;

import com.prodigalgal.remotecontrolmcp.protocol.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.logging.*;

/** File results share the existing durable task/artifact channel. Mutations are not replayed after a lost lease. */
final class FileTaskRunner implements Runnable {
    private static final Logger LOG = Logger.getLogger(FileTaskRunner.class.getName());
    private final AgentConfig config;
    private final AgentIdentity identity;
    private final TaskCommand task;
    private final AgentTransport transport;

    FileTaskRunner(AgentConfig config, AgentIdentity identity, TaskCommand task, AgentTransport transport) {
        this.config = config; this.identity = identity; this.task = task; this.transport = transport;
    }

    @Override public void run() {
        boolean mutationStarted = false;
        try {
            var request = JsonCodec.read(task.command().getBytes(StandardCharsets.UTF_8), FileRequest.class);
            request.validate();
            var cwd = AgentPaths.resolveCwd(config, identity.machineId(), task, task.cwd());
            state(new TaskUpdateRequest("running", null, null, Instant.now(), null, false));
            var timeout = Duration.ofSeconds(task.timeoutSeconds() <= 0 ? 300 : Math.min(300, task.timeoutSeconds()));
            mutationStarted = !request.readOnly();
            var result = new FileOperations(cwd, timeout, count -> TaskProgressReporter.send(LOG, transport, identity, task,
                    new TaskProgressUpdate(request.operation(), null, "processing filesystem entries", count, null, "entries"))).execute(request);
            var data = JsonCodec.write(result);
            var digest = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));
            AgentRetry.call(LOG, "file result upload " + task.id(), () -> transport.appendArtifact(identity.machineId(), identity.token(), task.id(), task.attempt(), "application/vnd.rcm.files+json", digest, data));
            state(new TaskUpdateRequest("completed", 0, null, null, Instant.now(), false));
        } catch (Exception error) {
            var canceled = error instanceof InterruptedException || error instanceof java.io.InterruptedIOException || Thread.currentThread().isInterrupted();
            if (canceled) Thread.interrupted();
            var message = SensitiveValueRedactor.redact(error.getMessage() == null ? "file operation failed" : error.getMessage());
            if (mutationStarted) message = "file operation outcome unknown; inspect target before retry: " + message;
            try { state(new TaskUpdateRequest(canceled ? "canceled" : "failed", null, message.substring(0, Math.min(2048, message.length())), null, Instant.now(), false)); }
            catch (Exception reporting) { LOG.log(Level.WARNING, "could not report filesystem task outcome " + task.id(), reporting); }
            finally { if (canceled) Thread.currentThread().interrupt(); }
        }
    }

    private void state(TaskUpdateRequest state) throws Exception {
        AgentRetry.call(LOG, "file task state " + task.id(), () -> { transport.updateState(identity.machineId(), identity.token(), task.id(), task.attempt(), state); return null; });
    }
}
