package com.prodigalgal.remoteconnectmcp.agent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.prodigalgal.remoteconnectmcp.protocol.ExecutionContract;
import com.prodigalgal.remoteconnectmcp.protocol.ScopeMode;
import com.prodigalgal.remoteconnectmcp.protocol.TaskCommand;
import com.prodigalgal.remoteconnectmcp.protocol.TaskKind;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentPathsTest {
    @TempDir
    Path tempDir;

    @Test
    void enforcesCenterIssuedScopeAgainstRealLocalPaths() throws Exception {
        var root = Files.createDirectories(tempDir.resolve("workspace"));
        var inside = Files.createDirectories(root.resolve("src"));
        var outside = Files.createDirectories(tempDir.resolve("outside"));
        var config = new AgentConfig(URI.create("http://127.0.0.1"), "enroll", "agent", "host-1",
                root.toString(), ScopeMode.WORKSPACE, root.toString(), List.of("command"), false,
                tempDir.resolve("state"), java.time.Duration.ofSeconds(1), 1);
        var contract = new ExecutionContract("machine-1", "host-1", ScopeMode.PATH, null, null,
                root.toString(), "session-1", "command", ExecutionContract.Budget.defaults(),
                Instant.now().plusSeconds(60), "key-1", "low", false, null);
        var task = new TaskCommand("task-1", TaskKind.COMMAND, "command", "echo ok", inside.toString(),
                Map.of(), 0, null, Instant.now(), contract);

        assertDoesNotThrow(() -> AgentPaths.resolveCwd(config, "machine-1", task, inside.toString()));
        assertThrows(java.io.IOException.class,
                () -> AgentPaths.resolveCwd(config, "machine-1", task, outside.toString()));
    }
}
