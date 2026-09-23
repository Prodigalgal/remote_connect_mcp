package com.prodigalgal.remoteconnectmcp.desktop;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.prodigalgal.remoteconnectmcp.protocol.DesktopCompanionProtocol;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopCompanionServerTest {
    @Test
    void companionAcceptsAnyValidContract(@TempDir Path stateDir) throws Exception {
        var scope = stateDir.resolve("project");
        Files.createDirectories(scope);
        var request = request(scope);

        assertDoesNotThrow(() -> DesktopCompanionServer.validateRequest(request));
    }

    @Test
    void companionDoesNotImposeMachineRoot(@TempDir Path stateDir) throws Exception {
        var machineRoot = stateDir.resolve("workspace");
        var outside = stateDir.resolve("outside");
        Files.createDirectories(machineRoot);
        Files.createDirectories(outside);
        var request = request(outside);

        assertDoesNotThrow(() -> DesktopCompanionServer.validateRequest(request));
    }

    private static DesktopCompanionProtocol.Request request(Path cwd) {
        return new DesktopCompanionProtocol.Request("companion-token", "screenshot", null, List.of(),
                cwd.toString(), null, null, null, null, null, null, null, null, null,
                Instant.now().plusSeconds(60), "test-session");
    }
}
