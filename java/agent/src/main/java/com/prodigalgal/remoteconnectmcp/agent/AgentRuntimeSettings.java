package com.prodigalgal.remoteconnectmcp.agent;

import com.prodigalgal.remoteconnectmcp.protocol.AgentConfigUpdate;
import com.prodigalgal.remoteconnectmcp.protocol.JsonCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Mutable, non-secret settings applied between heartbeats.  The file is
 * replaced atomically so a service restart never observes a partially-written
 * update.  Output limits and scope remain boot-time settings until their
 * validation and migration contract is extended explicitly.
 */
final class AgentRuntimeSettings {
    private static final Logger LOG = Logger.getLogger(AgentRuntimeSettings.class.getName());
    private static final String FILE_NAME = "runtime-config.json";

    private final Path stateDir;
    private final AtomicLong generation;
    private final AtomicReference<Duration> pollInterval;
    private final AtomicInteger maxConcurrency;

    AgentRuntimeSettings(AgentConfig base) {
        this.stateDir = base.stateDir().toAbsolutePath().normalize();
        this.generation = new AtomicLong(0L);
        this.pollInterval = new AtomicReference<>(base.pollInterval());
        this.maxConcurrency = new AtomicInteger(base.maxConcurrency());
        load(base);
    }

    long generation() {
        return generation.get();
    }

    Duration pollInterval() {
        return pollInterval.get();
    }

    int maxConcurrency() {
        return maxConcurrency.get();
    }

    boolean apply(AgentConfigUpdate update) throws IOException {
        if (update == null || update.generation() <= generation.get()) {
            return false;
        }
        // Constructing AgentConfigUpdate already validates the allowed range.
        var payload = JsonCodec.write(update);
        Files.createDirectories(stateDir);
        var temporary = Files.createTempFile(stateDir, FILE_NAME, ".tmp");
        try {
            Files.write(temporary, payload);
            try {
                Files.move(temporary, stateDir.resolve(FILE_NAME), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, stateDir.resolve(FILE_NAME), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        pollInterval.set(Duration.ofMillis(update.pollIntervalMs()));
        maxConcurrency.set(update.maxConcurrency());
        generation.set(update.generation());
        return true;
    }

    private void load(AgentConfig base) {
        var file = stateDir.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) return;
        try {
            var bytes = Files.readAllBytes(file);
            if (bytes.length > 16 * 1024) throw new IOException("runtime config is too large");
            var update = JsonCodec.read(bytes, AgentConfigUpdate.class);
            if (update.generation() <= 0) return;
            pollInterval.set(Duration.ofMillis(update.pollIntervalMs()));
            maxConcurrency.set(update.maxConcurrency());
            generation.set(update.generation());
        } catch (Exception exception) {
            LOG.log(Level.WARNING, "ignoring invalid persisted Agent runtime config", exception);
            pollInterval.set(base.pollInterval());
            maxConcurrency.set(base.maxConcurrency());
            generation.set(0L);
        }
    }
}
