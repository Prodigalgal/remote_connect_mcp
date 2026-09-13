package com.prodigalgal.remoteconnectmcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentIdentityStoreTest {
    @Test
    void savesAndLoadsIdentityThroughAnAtomicStateFile(@TempDir Path tempDir) throws Exception {
        var store = new AgentIdentityStore(tempDir.resolve("agent-state"));
        var identity = new AgentIdentity("machine_test", "daily-token");

        store.save(identity);

        assertEquals(identity, store.load().orElseThrow());
        assertTrue(Files.isRegularFile(tempDir.resolve("agent-state").resolve("identity.json")));
        try (var files = Files.list(tempDir.resolve("agent-state"))) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void deleteRemovesOnlyTheIdentityFile(@TempDir Path tempDir) throws Exception {
        var stateDir = tempDir.resolve("agent-state");
        var store = new AgentIdentityStore(stateDir);
        Files.createDirectories(stateDir);
        Files.writeString(stateDir.resolve("keep.log"), "diagnostic");
        store.save(new AgentIdentity("machine_test", "daily-token"));

        store.delete();

        assertTrue(store.load().isEmpty());
        assertTrue(Files.isRegularFile(stateDir.resolve("keep.log")));
    }
}
