package com.prodigalgal.remoteconnectmcp.agent;

import com.prodigalgal.remoteconnectmcp.protocol.JsonCodec;
import com.prodigalgal.remoteconnectmcp.protocol.TaskCommand;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Small on-host inventory for no-timeout command processes.  The child writes
 * directly to a private file, so it can keep running while the Agent is
 * restarted or its Center connection is unavailable.  A later Agent instance
 * discovers the PID and resumes output/state delivery.
 */
final class DurableTaskStore {
    private final Path directory;
    private final java.util.Set<String> watchedTasks = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> guardedTasks = ConcurrentHashMap.newKeySet();
    private final java.util.Map<String, CompletableFuture<Record>> completionSignals = new ConcurrentHashMap<>();

    DurableTaskStore(Path stateDir) throws IOException {
        this.directory = stateDir.toAbsolutePath().normalize().resolve("durable-tasks");
        Files.createDirectories(directory);
        restrictOwner(directory);
    }

    Started start(TaskCommand task, Path cwd) throws IOException {
        return start(task, cwd, 64L * 1024 * 1024);
    }

    Started start(TaskCommand task, Path cwd, long maxOutputBytes) throws IOException {
        if (maxOutputBytes < 1) throw new IllegalArgumentException("maxOutputBytes must be positive");
        var safeId = safe(task.id());
        var output = directory.resolve(safeId + ".log");
        var recordPath = directory.resolve(safeId + ".json");
        Files.deleteIfExists(output);
        var builder = new ProcessBuilder(shell(task.command()))
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(output.toFile()));
        CommandRunner.cleanEnvironment(builder.environment());
        if (task.env() != null) {
            task.env().forEach((key, value) -> {
                if (key != null && !key.isBlank() && value != null && !CommandRunner.isSensitive(key)) {
                    builder.environment().put(key, value);
                }
            });
        }
        var process = builder.start();
        var record = new Record(task.id(), process.pid(), task.command(), cwd.toString(), task.createdAt(),
                output.toString(), recordPath.toString(), false, null, null);
        try {
            write(recordPath, record);
        } catch (IOException exception) {
            process.destroyForcibly();
            throw exception;
        }
        watchCompletion(record, process);
        startOutputGuard(record, maxOutputBytes);
        return new Started(process, record);
    }

    /** Start a size guard for a recovered process as well as a new process. */
    void guard(Record record, long maxOutputBytes) {
        if (record == null || maxOutputBytes < 1) return;
        startOutputGuard(record, maxOutputBytes);
    }

    /**
     * Attach the same durable completion signal to a process recovered after an
     * Agent restart.  A recovered process is represented by a ProcessHandle,
     * not a Process instance, so the normal start-time watcher cannot be reused
     * implicitly.  Keeping completion event driven here avoids the old
     * "check the record every N milliseconds" recovery loop.
     */
    void watchRecoveredCompletion(Record record, ProcessHandle process) {
        if (record == null || record.completed() || process == null) return;
        var signal = completionSignals.computeIfAbsent(record.taskId(), ignored -> new CompletableFuture<>());
        if (!watchedTasks.add(record.taskId())) return;
        process.onExit().thenAccept(finished -> {
            synchronized (DurableTaskStore.this) {
                if (!watchedTasks.contains(record.taskId())) return;
                try {
                    var code = finished.exitValue();
                    markCompleted(record, code, code == 0 ? null : "command exited with code " + code);
                } catch (Exception ignored) {
                    // The active runner will report the failure if the record
                    // cannot be persisted; this callback must never resurrect
                    // a removed task file.
                }
            }
            signal.complete(find(record.taskId()).orElse(record));
        });
    }

    /** Register one completion future for a process owned by this Agent. */
    private void watchCompletion(Record record, Process process) {
        if (record == null || record.completed()) return;
        var signal = completionSignals.computeIfAbsent(record.taskId(), ignored -> new CompletableFuture<>());
        if (!watchedTasks.add(record.taskId())) return;
        if (process == null) return;
        process.onExit().thenAccept(finished -> {
            synchronized (DurableTaskStore.this) {
                if (!watchedTasks.contains(record.taskId())) return;
                try {
                    var code = finished.exitValue();
                    markCompleted(record, code, code == 0 ? null : "command exited with code " + code);
                } catch (Exception ignored) {
                    // The active runner will report the failure if it is still
                    // attached; this watcher must never resurrect a removed file.
                }
            }
            signal.complete(find(record.taskId()).orElse(record));
        });
    }

    private void startOutputGuard(Record record, long maxOutputBytes) {
        if (record == null || !guardedTasks.add(record.taskId())) return;
        Thread.startVirtualThread(() -> {
            WatchService watcher = null;
            try {
                var output = Path.of(record.outputPath()).toAbsolutePath().normalize();
                watcher = openWatcher(output);
                if (watcher == null) {
                    // Filesystems without a WatchService still get a final
                    // size check when the process exits; no fixed timer is
                    // introduced just for this optional safety guard.
                    var process = ProcessHandle.of(record.pid()).orElse(null);
                    if (process != null) process.onExit().thenRun(() -> checkOutputLimit(output, record, maxOutputBytes));
                    return;
                }
                var key = watcher;
                var process = ProcessHandle.of(record.pid()).orElse(null);
                if (process == null) return;
                process.onExit().thenRun(() -> closeQuietly(key));
                while (true) {
                    if (!guardedTasks.contains(record.taskId())) return;
                    WatchKey changed;
                    try {
                        changed = key.take();
                    } catch (ClosedWatchServiceException closed) {
                        return;
                    }
                    var relevant = false;
                    for (var event : changed.pollEvents()) {
                        if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue;
                        var context = event.context();
                        if (context instanceof Path path && path.getFileName().equals(output.getFileName())) relevant = true;
                    }
                    if (!changed.reset()) return;
                    if (relevant && checkOutputLimit(output, record, maxOutputBytes)) return;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                // The runner/next startup will report missing or corrupt
                // durable state; the guard must never keep the Agent from
                // shutting down.
            } finally {
                closeQuietly(watcher);
                guardedTasks.remove(record.taskId());
            }
        });
    }

    private static boolean checkOutputLimit(Path output, Record record, long maxOutputBytes) {
        try {
            if (Files.isRegularFile(output) && Files.size(output) > maxOutputBytes) {
                ProcessHandle.of(record.pid()).ifPresent(DurableTaskStore::terminateTree);
                return true;
            }
        } catch (Exception ignored) {
            // The runner/next startup reports missing or corrupt state.
        }
        return false;
    }

    private static WatchService openWatcher(Path output) {
        var parent = output.getParent();
        if (parent == null) return null;
        try {
            var watcher = FileSystems.getDefault().newWatchService();
            parent.register(watcher, StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
            return watcher;
        } catch (Exception exception) {
            return null;
        }
    }

    private static void closeQuietly(WatchService watcher) {
        if (watcher == null) return;
        try {
            watcher.close();
        } catch (Exception ignored) {
            // Closing an already closed watcher is harmless.
        }
    }

    List<Record> load() {
        var result = new ArrayList<Record>();
        try (var files = Files.list(directory)) {
            files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .forEach(path -> {
                        try {
                            var record = JsonCodec.read(Files.readAllBytes(path), Record.class);
                            if (record.taskId() != null && !record.taskId().isBlank() && record.pid() > 0
                                    && record.outputPath() != null && !record.outputPath().isBlank()) {
                                result.add(record);
                            }
                        } catch (Exception ignored) {
                            // A corrupt orphan is ignored; cleanup is safe and
                            // never blocks Agent registration.
                        }
                    });
        } catch (IOException ignored) {
        }
        result.sort(java.util.Comparator.comparing(Record::startedAt, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
        return List.copyOf(result);
    }

    Optional<Record> find(String taskId) {
        return load().stream().filter(record -> record.taskId().equals(taskId)).findFirst();
    }

    void markCompleted(Record record, int exitCode, String error) throws IOException {
        var completed = new Record(record.taskId(), record.pid(), record.command(), record.cwd(),
                record.startedAt(), record.outputPath(), record.recordPath(), true, exitCode, error);
        write(Path.of(record.recordPath()), completed);
        completionSignals.computeIfAbsent(record.taskId(), ignored -> new CompletableFuture<>()).complete(completed);
    }

    synchronized void remove(Record record) {
        watchedTasks.remove(record.taskId());
        guardedTasks.remove(record.taskId());
        completionSignals.remove(record.taskId());
        deleteEventually(Path.of(record.outputPath()));
        deleteEventually(Path.of(record.recordPath()));
    }

    Record awaitCompleted(Record record, Duration timeout) throws InterruptedException {
        if (record == null || record.completed()) return record;
        watchCompletion(record, null);
        var signal = completionSignals.computeIfAbsent(record.taskId(), ignored -> new CompletableFuture<>());
        try {
            return signal.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ignored) {
            return find(record.taskId()).orElse(record);
        } catch (java.util.concurrent.ExecutionException exception) {
            return find(record.taskId()).orElse(record);
        }
    }

    static boolean isAlive(Record record) {
        return ProcessHandle.of(record.pid()).map(ProcessHandle::isAlive).orElse(false);
    }

    private static void terminateTree(ProcessHandle process) {
        var descendants = process.descendants().toList();
        for (var index = descendants.size() - 1; index >= 0; index--) descendants.get(index).destroy();
        process.destroy();
        for (var index = descendants.size() - 1; index >= 0; index--) {
            if (descendants.get(index).isAlive()) descendants.get(index).destroyForcibly();
        }
        if (process.isAlive()) process.destroyForcibly();
    }

    static Path outputPath(Record record) {
        return Path.of(record.outputPath()).toAbsolutePath().normalize();
    }

    private static void deleteEventually(Path path) {
        // Windows may keep an inherited Redirect.to(...) handle alive after
        // the process reports exit.  Try once; the next startup sweep can
        // remove an orphan without a hidden delete polling loop.
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Cleanup is best effort and never blocks Agent shutdown.
        }
    }

    private static void write(Path target, Record record) throws IOException {
        var parent = target.toAbsolutePath().normalize().getParent();
        if (parent != null) Files.createDirectories(parent);
        var temp = parent.resolve("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            try (var channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                channel.write(java.nio.ByteBuffer.wrap(JsonCodec.write(record)));
                channel.force(true);
            }
            restrictOwner(temp);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictOwner(target);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String safe(String value) {
        var safe = value == null ? "task" : value.replaceAll("[^A-Za-z0-9_.-]", "_");
        return safe.length() > 100 ? safe.substring(0, 100) : safe;
    }

    private static List<String> shell(String command) {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return List.of("cmd.exe", "/d", "/s", "/c", command == null ? "" : command);
        }
        return List.of("/bin/sh", "-lc", command == null ? "" : command);
    }

    private static void restrictOwner(Path path) {
        try {
            var permissions = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            if (Files.isDirectory(path)) permissions.add(PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(path, permissions);
        } catch (Exception ignored) {
            // Windows ACLs are applied by the installer; POSIX permissions are
            // best-effort for filesystems that expose them.
        }
    }

    record Started(Process process, Record record) {
    }

    record Record(String taskId, long pid, String command, String cwd, Instant startedAt, String outputPath,
                  String recordPath, boolean completed, Integer exitCode, String error) {
        TaskCommand taskCommand() {
            return new TaskCommand(taskId, com.prodigalgal.remoteconnectmcp.protocol.TaskKind.COMMAND, "command",
                    command, cwd, java.util.Map.of(), 0, null, startedAt == null ? Instant.EPOCH : startedAt);
        }
    }
}
