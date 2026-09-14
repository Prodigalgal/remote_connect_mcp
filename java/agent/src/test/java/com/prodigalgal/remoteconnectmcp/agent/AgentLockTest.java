package com.prodigalgal.remoteconnectmcp.agent;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentLockTest {
    @Test
    void onlyOneProcessCanOwnAStateDirectoryLock(@TempDir Path stateDir) throws Exception {
        try (var first = AgentLock.acquire(stateDir)) {
            // Windows keeps the lock file opened exclusively by the owner;
            // checking existence is portable while the second acquisition
            // below verifies the actual mutual exclusion contract.
            assertTrue(Files.isRegularFile(stateDir.resolve("agent.lock")));
            assertThrows(java.io.IOException.class, () -> AgentLock.acquire(stateDir));
        }
        try (var second = AgentLock.acquire(stateDir)) {
            assertTrue(Files.isRegularFile(stateDir.resolve("agent.lock")));
        }
    }

    @Test
    void desktopCompanionUsesAnIndependentLockFile(@TempDir Path stateDir) throws Exception {
        try (var command = AgentLock.acquire(stateDir);
             var desktop = AgentLock.acquire(stateDir.resolve("desktop"), "desktop-companion.lock")) {
            assertTrue(Files.isRegularFile(stateDir.resolve("agent.lock")));
            assertTrue(Files.isRegularFile(stateDir.resolve("desktop/desktop-companion.lock")));
        }
    }
}
