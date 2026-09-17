package com.prodigalgal.remoteconnectmcp.center;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded Center-side transfer admission control.
 *
 * <p>The limiter is intentionally admission based rather than a periodic
 * sampler: a transfer reserves its expected spool footprint before opening a
 * remote body and releases it in the request's finally block.  This keeps
 * concurrency and temporary-disk usage bounded without introducing a polling
 * thread.  The limits are process-local; a PostgreSQL deployment should run a
 * single Center replica as documented, so the reservation is authoritative for
 * the active data plane.</p>
 */
final class TransferResourceLimiter {
    private static final long MAX_BYTES = ArtifactStore.MAX_STREAM_BYTES;
    private static final int DEFAULT_GLOBAL = 4;
    private static final int DEFAULT_PER_PRINCIPAL = 2;
    private static final int DEFAULT_PER_MACHINE = 1;
    private static final long DEFAULT_SPOOL = 8L * 1024 * 1024 * 1024;
    private static final long MIN_FREE_BYTES = 64L * 1024 * 1024;

    private final Object monitor = new Object();
    private final int maxGlobal;
    private final int maxPerPrincipal;
    private final int maxPerMachine;
    private final long maxSpoolBytes;
    private final Path tempDirectory;
    private final Map<String, Integer> principalCounts = new HashMap<>();
    private final Map<String, Integer> machineCounts = new HashMap<>();
    private final AtomicLong reservedBytes = new AtomicLong();
    private int active;

    TransferResourceLimiter() {
        this(intSetting("RCM_CENTER_TRANSFER_MAX_CONCURRENT", DEFAULT_GLOBAL, 1, 128),
                intSetting("RCM_CENTER_TRANSFER_MAX_PER_PRINCIPAL", DEFAULT_PER_PRINCIPAL, 1, 32),
                intSetting("RCM_CENTER_TRANSFER_MAX_PER_MACHINE", DEFAULT_PER_MACHINE, 1, 16),
                longSetting("RCM_CENTER_TRANSFER_MAX_SPOOL_BYTES", DEFAULT_SPOOL, MIN_FREE_BYTES, MAX_BYTES * 2),
                Path.of(System.getProperty("java.io.tmpdir", ".")));
    }

    TransferResourceLimiter(int maxGlobal, int maxPerPrincipal, int maxPerMachine, long maxSpoolBytes, Path tempDirectory) {
        if (maxGlobal < 1 || maxPerPrincipal < 1 || maxPerMachine < 1 || maxSpoolBytes < MIN_FREE_BYTES || tempDirectory == null) {
            throw new IllegalArgumentException("invalid transfer resource limits");
        }
        this.maxGlobal = maxGlobal;
        this.maxPerPrincipal = maxPerPrincipal;
        this.maxPerMachine = maxPerMachine;
        this.maxSpoolBytes = maxSpoolBytes;
        this.tempDirectory = tempDirectory.toAbsolutePath().normalize();
    }

    Lease reserve(String principalId, String machineId, long bytes) {
        if (principalId == null || principalId.isBlank() || machineId == null || machineId.isBlank()) {
            throw new IllegalArgumentException("transfer owner is required");
        }
        if (bytes < 0 || bytes > MAX_BYTES) throw new IllegalArgumentException("transfer reservation is outside the allowed range");
        var principal = principalId.trim();
        var machine = machineId.trim();
        synchronized (monitor) {
            var principalActive = principalCounts.getOrDefault(principal, 0);
            var machineActive = machineCounts.getOrDefault(machine, 0);
            var nextBytes = reservedBytes.get() + bytes;
            if (active >= maxGlobal) throw new IllegalStateException("file transfer concurrency limit reached");
            if (principalActive >= maxPerPrincipal) throw new IllegalStateException("file transfer principal concurrency limit reached");
            if (machineActive >= maxPerMachine) throw new IllegalStateException("file transfer machine concurrency limit reached");
            if (nextBytes > maxSpoolBytes) throw new IllegalStateException("file transfer spool reservation limit reached");
            ensureFreeSpace(bytes);
            active++;
            principalCounts.put(principal, principalActive + 1);
            machineCounts.put(machine, machineActive + 1);
            reservedBytes.addAndGet(bytes);
            return new Lease(principal, machine, bytes);
        }
    }

    private void ensureFreeSpace(long bytes) {
        try {
            Files.createDirectories(tempDirectory);
            FileStore store = Files.getFileStore(tempDirectory);
            var free = store.getUsableSpace();
            if (free < bytes + MIN_FREE_BYTES) {
                throw new IllegalStateException("insufficient Center temporary disk space for file transfer");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("cannot inspect Center temporary disk space", exception);
        }
    }

    final class Lease implements AutoCloseable {
        private final String principal;
        private final String machine;
        private final long bytes;
        private boolean closed;

        private Lease(String principal, String machine, long bytes) {
            this.principal = principal;
            this.machine = machine;
            this.bytes = bytes;
        }

        @Override
        public void close() {
            synchronized (monitor) {
                if (closed) return;
                closed = true;
                active = Math.max(0, active - 1);
                decrement(principalCounts, principal);
                decrement(machineCounts, machine);
                reservedBytes.addAndGet(-bytes);
            }
        }
    }

    private static void decrement(Map<String, Integer> counts, String key) {
        var next = counts.getOrDefault(key, 0) - 1;
        if (next <= 0) counts.remove(key); else counts.put(key, next);
    }

    private static int intSetting(String key, int fallback, int min, int max) {
        var raw = System.getenv(key);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            var value = Integer.parseInt(raw.trim());
            if (value < min || value > max) throw new IllegalStateException(key + " is outside the allowed range");
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(key + " must be an integer", exception);
        }
    }

    private static long longSetting(String key, long fallback, long min, long max) {
        var raw = System.getenv(key);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            var value = Long.parseLong(raw.trim());
            if (value < min || value > max) throw new IllegalStateException(key + " is outside the allowed range");
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(key + " must be an integer", exception);
        }
    }
}
