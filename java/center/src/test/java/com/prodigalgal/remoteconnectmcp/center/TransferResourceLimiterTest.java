package com.prodigalgal.remoteconnectmcp.center;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TransferResourceLimiterTest {
    @Test
    void enforcesMachineAndGlobalReservationsUntilReleased(@TempDir Path root) {
        var limiter = new TransferResourceLimiter(2, 2, 1, 128L * 1024 * 1024, root);
        var first = limiter.reserve("principal-a", "machine-a", 1);
        assertThrows(IllegalStateException.class, () -> limiter.reserve("principal-a", "machine-a", 1));
        first.close();
        try (var second = limiter.reserve("principal-a", "machine-a", 1);
             var third = limiter.reserve("principal-b", "machine-b", 1)) {
            assertThrows(IllegalStateException.class, () -> limiter.reserve("principal-c", "machine-c", 1));
        }
    }

    @Test
    void releasesReservationIdempotently(@TempDir Path root) {
        var limiter = new TransferResourceLimiter(1, 1, 1, 128L * 1024 * 1024, root);
        var lease = limiter.reserve("principal-a", "machine-a", 1);
        lease.close();
        lease.close();
        try (var replacement = limiter.reserve("principal-a", "machine-a", 1)) {
            // The second reservation proves the first close released exactly
            // once and did not underflow the counters.
        }
    }

    @Test
    void reservesPersistentAppendUntilTheAppendLeaseIsClosed(@TempDir Path root) throws Exception {
        var limiter = new TransferResourceLimiter(2, 2, 2, 128L * 1024 * 1024, root);
        var resume = root.resolve("resume");
        Files.createDirectories(resume);
        Files.write(resume.resolve("existing.part"), new byte[32]);

        var lease = limiter.ensurePersistentSpoolCapacity(resume, 64L * 1024 * 1024);
        assertThrows(IllegalStateException.class,
                () -> limiter.ensurePersistentSpoolCapacity(resume, 64L * 1024 * 1024));
        lease.close();
        try (var replacement = limiter.ensurePersistentSpoolCapacity(resume, 32)) {
            // Closing the first lease releases only its in-flight reservation;
            // the bytes already present on disk remain part of the scan.
        }
    }
}
