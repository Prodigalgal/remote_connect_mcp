package com.prodigalgal.remoteconnectmcp.agent;

import com.prodigalgal.remoteconnectmcp.protocol.JsonCodec;
import com.prodigalgal.remoteconnectmcp.protocol.TaskCommand;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
        watchedTasks.add(record.taskId());
        startOutputGuard(record, maxOutputBytes);
        var durableRecord = record;
        process.onExit().thenAccept(finished -> {
            synchronized (DurableTaskStore.this) {
                if (!watchedTasks.contains(durableRecord.taskId())) return;
                try {
                    var code = finished.exitValue();
                    markCompleted(durableRecord, code, code == 0 ? null : "command exited with code " + code);
                } catch (Exception ignored) {
                    // The active runner will report the failure if it is still
                    // attached; this watcher must never resurrect a removed file.
                }
            }
        });
        return new Started(process, record);
    }

    /** Start a size guard for a recovered process as well as a new process. */
    void guard(Record record, long maxOutputBytes) {
        if (record == null || maxOutputBytes < 1) return;
        startOutputGuard(record, maxOutputBytes);
    }

    private void startOutputGuard(Record record, long maxOutputBytes) {
        if (record == null || !guardedTasks.add(record.taskId())) return;
        Thread.startVirtualThread(() -> {
            try {
                while (isAlive(record)) {
                    try {
                        if (Files.isRegularFile(Path.of(record.outputPath()))
                                && Files.size(Path.of(record.outputPath())) > maxOutputBytes) {
                            ProcessHandle.of(record.pid()).ifPresent(DurableTaskStore::terminateTree);
                            return;
                        }
                        Thread.sleep(250);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (Exception ignored) {
                        // The runner/next startup will report missing or corrupt
                        // durable state; the guard must never keep the Agent from
                        // shutting down.
                        return;
                    }
                }
            } finally {
                guardedTasks.remove(record.taskId());
            }
        });
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
        write(Path.of(record.recordPath()), new Record(record.taskId(), record.pid(), record.command(), record.cwd(),
                record.startedAt(), record.outputPath(), record.recordPath(), true, exitCode, error));
    }

    synchronized void remove(Record record) {
        watchedTasks.remove(record.taskId());
        guardedTasks.remove(record.taskId());
        deleteEventually(Path.of(record.outputPath()));
        deleteEventually(Path.of(record.recordPath()));
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
        // Windows may keep an inherited Redirect.to(...) handle alive for a
        // short interval after the process reports exit.  A bounded retry is
        // enough to make cleanup deterministic without turning shutdown into
        // an unbounded wait; a later startup sweep can remove an orphan.
        for (var attempt = 0; attempt < 40; attempt++) {
            try {
                if (!Files.deleteIfExists(path)) return;
                return;
            } catch (IOException exception) {
                if (attempt == 39) return;
                try {
                    Thread.sleep(100);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
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
