package com.prodigalgal.remoteconnectmcp.agent;

import com.prodigalgal.remoteconnectmcp.protocol.JsonCodec;
import com.prodigalgal.remoteconnectmcp.protocol.TaskCommand;
import com.prodigalgal.remoteconnectmcp.protocol.TaskProgressUpdate;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Optional, event-driven progress adapter for command tasks.  A command may
 * set {@code RCM_PROGRESS_FILE} in its non-secret task environment and write
 * bounded JSON snapshots to that file.  The path is restricted to the
 * resolved task cwd, WatchService wakes the watcher, and the watcher closes
 * with the task; there is no fixed-interval polling or unbounded history.
 */
final class TaskProgressFileWatcher implements AutoCloseable {
    private static final int MAX_BYTES = 64 * 1024;
    private static final long MIN_UPDATE_NANOS = Duration.ofMillis(250).toNanos();

    private final Logger logger;
    private final Path file;
    private final WatchService watchService;
    private final TaskCommand task;
    private final AgentTransport transport;
    private final AgentIdentity identity;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong lastUpdateNanos = new AtomicLong(Long.MIN_VALUE);
    private final Thread thread;

    private TaskProgressFileWatcher(Logger logger, Path file, WatchService watchService,
                                    TaskCommand task, AgentTransport transport, AgentIdentity identity) {
        this.logger = logger;
        this.file = file;
        this.watchService = watchService;
        this.task = task;
        this.transport = transport;
        this.identity = identity;
        this.thread = Thread.ofVirtual().name("rcm-progress-" + task.id()).start(this::run);
    }

    static TaskProgressFileWatcher startIfConfigured(Logger logger, AgentConfig config,
                                                     AgentIdentity identity, TaskCommand task,
                                                     AgentTransport transport) {
        if (task == null || task.env() == null) return null;
        var raw = task.env().get("RCM_PROGRESS_FILE");
        if (raw == null || raw.isBlank()) return null;
        try {
            var cwd = AgentPaths.resolveCwd(config, identity.machineId(), task, task.cwd());
            var cwdReal = cwd.toRealPath();
            var input = Path.of(raw.trim());
            var candidate = (input.isAbsolute() ? input : cwd.resolve(input)).toAbsolutePath().normalize();
            var parent = candidate.getParent();
            if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("progress file parent is not an existing directory");
            }
            var parentReal = parent.toRealPath();
            if (!parentReal.startsWith(cwdReal) || Files.isSymbolicLink(candidate)) {
                throw new IOException("progress file must be a non-symlink path below the task cwd");
            }
            if (candidate.getFileName() == null || candidate.getFileName().toString().length() > 255) {
                throw new IOException("progress file name is invalid");
            }
            var watcher = FileSystems.getDefault().newWatchService();
            parentReal.register(watcher, StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
            var result = new TaskProgressFileWatcher(logger, candidate, watcher, task, transport, identity);
            result.readSnapshot();
            return result;
        } catch (Exception failure) {
            logger.log(Level.FINE, "ignoring invalid optional RCM_PROGRESS_FILE for task " + task.id(), failure);
            return null;
        }
    }

    private void run() {
        try {
            while (!closed.get()) {
                WatchKey key = watchService.take();
                for (var event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue;
                    if (event.context() instanceof Path changed
                            && changed.getFileName().equals(file.getFileName())) {
                        readSnapshot();
                    }
                }
                if (!key.reset()) return;
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (java.nio.file.ClosedWatchServiceException ignored) {
            // Normal task teardown.
        } catch (RuntimeException failure) {
            logger.log(Level.FINE, "progress file watcher stopped for task " + task.id(), failure);
        }
    }

    private void readSnapshot() {
        if (closed.get() || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return;
        var now = System.nanoTime();
        var previous = lastUpdateNanos.get();
        if (previous != Long.MIN_VALUE && now - previous < MIN_UPDATE_NANOS) return;
        if (!lastUpdateNanos.compareAndSet(previous, now)) return;
        try {
            var size = Files.size(file);
            if (size < 0 || size > MAX_BYTES) throw new IOException("progress file exceeds " + MAX_BYTES + " bytes");
            var update = JsonCodec.read(readBounded(), TaskProgressUpdate.class);
            TaskProgressReporter.send(logger, transport, identity, task, update);
        } catch (Exception failure) {
            logger.log(Level.FINE, "ignoring invalid progress snapshot for task " + task.id(), failure);
        }
    }

    private byte[] readBounded() throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            var output = new ByteArrayOutputStream(Math.min(MAX_BYTES, 4096));
            var buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                if (output.size() + read > MAX_BYTES) {
                    throw new IOException("progress file exceeds " + MAX_BYTES + " bytes");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            watchService.close();
        } catch (IOException ignored) {
        }
        thread.interrupt();
    }
}
