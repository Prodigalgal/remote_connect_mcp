package com.prodigalgal.remoteconnectmcp.desktop;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Single-instance lock for the user-session desktop companion. */
final class DesktopCompanionLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private DesktopCompanionLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    static DesktopCompanionLock acquire(Path directory, String fileName) throws IOException {
        if (directory == null) throw new IOException("desktop companion lock directory is missing");
        if (fileName == null || !fileName.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IOException("desktop companion lock file name is invalid");
        }
        var normalizedDirectory = directory.toAbsolutePath().normalize();
        Files.createDirectories(normalizedDirectory);
        var path = normalizedDirectory.resolve(fileName).normalize();
        if (!path.getParent().equals(normalizedDirectory)) throw new IOException("desktop companion lock path escapes state directory");
        var channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ,
                StandardOpenOption.WRITE);
        try {
            final FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException exception) {
                throw new IOException("another desktop companion process already owns the lock", exception);
            }
            if (lock == null) throw new IOException("another desktop companion process already owns the lock");
            var marker = ("pid=" + ProcessHandle.current().pid() + System.lineSeparator())
                    .getBytes(StandardCharsets.US_ASCII);
            channel.truncate(0);
            channel.position(0);
            channel.write(ByteBuffer.wrap(marker));
            channel.force(true);
            return new DesktopCompanionLock(channel, lock);
        } catch (Exception exception) {
            try { channel.close(); } catch (IOException ignored) { }
            if (exception instanceof IOException io) throw io;
            throw new IOException("could not acquire desktop companion lock", exception);
        }
    }

    @Override
    public void close() {
        try { lock.release(); } catch (IOException ignored) { }
        try { channel.close(); } catch (IOException ignored) { }
    }
}
