package com.prodigalgal.remoteconnectmcp.agent;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A bounded, file-backed output buffer. The process reader only appends to the
 * local file; a separate uploader can be offline or retrying without closing
 * the child process' stdout pipe. The file is removed after the task reaches a
 * terminal state (or Agent shutdown cancels the task).
 */
final class TaskOutputSpool implements AutoCloseable {
    private static final int MAX_TASK_ID_FILE_CHARS = 64;

    private final Path path;
    private final FileChannel channel;
    private final long maxBytes;
    private final AgentResourceBudget budget;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private long size;
    private boolean truncated;
    private boolean complete;
    private boolean closed;

    TaskOutputSpool(Path stateDir, String taskId, long maxBytes) throws IOException {
        this(stateDir, taskId, maxBytes, null);
    }

    TaskOutputSpool(Path stateDir, String taskId, long maxBytes, AgentResourceBudget budget) throws IOException {
        if (maxBytes < 1) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        var directory = stateDir.toAbsolutePath().normalize().resolve("output-spool");
        Files.createDirectories(directory);
        var safeTaskId = taskId == null ? "task" : taskId.replaceAll("[^A-Za-z0-9_.-]", "_");
        if (safeTaskId.length() > MAX_TASK_ID_FILE_CHARS) {
            safeTaskId = safeTaskId.substring(0, MAX_TASK_ID_FILE_CHARS);
        }
        this.path = Files.createTempFile(directory, "task-" + safeTaskId + "-", ".out");
        this.channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
        this.maxBytes = maxBytes;
        this.budget = budget;
    }

    static void cleanupOrphans(Path stateDir, Duration maxAge) {
        try {
            var directory = stateDir.toAbsolutePath().normalize().resolve("output-spool");
            if (!Files.isDirectory(directory)) return;
            var cutoff = Instant.now().minus(maxAge);
            try (var files = Files.list(directory)) {
                files.filter(path -> path.getFileName().toString().endsWith(".out"))
                        .filter(path -> {
                            try {
                                return Files.getLastModifiedTime(path).toInstant().isBefore(cutoff);
                            } catch (IOException ignored) {
                                return false;
                            }
                        })
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException ignored) {
                                // Another process may still hold the file.
                            }
                        });
            }
        } catch (IOException ignored) {
            // Spool cleanup is opportunistic and must not prevent startup.
        }
    }

    /** Consumes the complete input buffer, retaining only the configured bound. */
    void append(byte[] bytes, int offset, int length) throws IOException {
        if (bytes == null || length <= 0) {
            return;
        }
        lock.lock();
        try {
            if (size >= maxBytes) {
                truncated = true;
                return;
            }
            var requested = Math.min((long) length, maxBytes - size);
            var writable = budget == null ? requested : budget.tryReserve(requested);
            if (writable <= 0) {
                truncated = true;
                return;
            }
            var writableInt = Math.toIntExact(writable);
            var written = 0;
            try {
                var buffer = ByteBuffer.wrap(bytes, offset, writableInt);
                while (buffer.hasRemaining()) {
                    channel.position(size);
                    var count = channel.write(buffer);
                    if (count <= 0) throw new IOException("output spool made no write progress");
                    size += count;
                    written += count;
                }
            } finally {
                if (budget != null && written < writableInt) {
                    budget.release(writableInt - written);
                }
            }
            if (writableInt < length) {
                truncated = true;
            }
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    void complete() {
        lock.lock();
        try {
            complete = true;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    Chunk awaitChunk(long offset, int maxChunk) throws IOException, InterruptedException {
        if (offset < 0 || maxChunk < 1) {
            throw new IllegalArgumentException("invalid spool cursor");
        }
        lock.lockInterruptibly();
        try {
            while (offset >= size && !complete) {
                changed.await();
            }
            if (offset > size) {
                throw new IOException("spool cursor is ahead of captured output");
            }
            var end = Math.min(size, offset + maxChunk);
            var data = new byte[Math.toIntExact(end - offset)];
            if (data.length > 0) {
                var buffer = ByteBuffer.wrap(data);
                channel.position(offset);
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) < 0) {
                        throw new IOException("output spool ended before the expected cursor");
                    }
                }
            }
            return new Chunk(data, complete && end >= size, truncated);
        } finally {
            lock.unlock();
        }
    }

    boolean truncated() {
        lock.lock();
        try {
            return truncated;
        } finally {
            lock.unlock();
        }
    }

    long size() {
        lock.lock();
        try {
            return size;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            if (closed) return;
            closed = true;
            try {
                channel.close();
            } catch (IOException ignored) {
                // Cleanup is best effort after the task has already ended.
            }
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // A later startup cleanup can remove an orphaned spool file.
            }
            if (budget != null) budget.release(size);
        } finally {
            lock.unlock();
        }
    }

    record Chunk(byte[] data, boolean endOfStream, boolean truncated) {
        Chunk {
            data = data == null ? new byte[0] : Arrays.copyOf(data, data.length);
        }
    }
}
