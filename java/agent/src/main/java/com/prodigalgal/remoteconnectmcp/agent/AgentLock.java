package com.prodigalgal.remoteconnectmcp.agent;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * A process-level lock for one Agent state directory.  Service managers can
 * briefly start an old and a new process during restart; the lock makes that
 * race fail closed instead of creating duplicate Center identities or child
 * process pools.
 */
final class AgentLock implements AutoCloseable {
    private final Path path;
    private final FileChannel channel;
    private final FileLock lock;

    private AgentLock(Path path, FileChannel channel, FileLock lock) {
        this.path = path;
        this.channel = channel;
        this.lock = lock;
    }

    static AgentLock acquire(Path stateDir) throws IOException {
        return acquire(stateDir, "agent.lock");
    }

    static AgentLock acquire(Path directory, String fileName) throws IOException {
        if (directory == null) throw new IOException("Agent lock directory is missing");
        if (fileName == null || !fileName.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IOException("Agent lock file name is invalid");
        }
        var normalizedDirectory = directory.toAbsolutePath().normalize();
        Files.createDirectories(normalizedDirectory);
        var path = normalizedDirectory.resolve(fileName).normalize();
        if (!path.getParent().equals(normalizedDirectory)) throw new IOException("Agent lock path escapes state directory");
        var channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ,
                StandardOpenOption.WRITE);
        try {
            final FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException exception) {
                throw new IOException("another Agent process already owns " + path, exception);
            }
            if (lock == null) throw new IOException("another Agent process already owns " + path);
            var marker = ("pid=" + ProcessHandle.current().pid() + System.lineSeparator())
                    .getBytes(StandardCharsets.US_ASCII);
            channel.truncate(0);
            channel.position(0);
            channel.write(ByteBuffer.wrap(marker));
            channel.force(true);
            return new AgentLock(path, channel, lock);
        } catch (Exception exception) {
            try { channel.close(); } catch (IOException ignored) { }
            if (exception instanceof IOException io) throw io;
            throw new IOException("could not acquire Agent lock " + path, exception);
        }
    }

    @Override
    public void close() {
        try { lock.release(); } catch (IOException ignored) { }
        try { channel.close(); } catch (IOException ignored) { }
    }

    @Override
    public String toString() {
        return path.toString();
    }
}
