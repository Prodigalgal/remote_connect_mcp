package com.prodigalgal.remoteconnectmcp.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.prodigalgal.remoteconnectmcp.protocol.JsonCodec;
import com.prodigalgal.remoteconnectmcp.protocol.DesktopCompanionProtocol;
import com.prodigalgal.remoteconnectmcp.protocol.ScopeMode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopCompanionServerTest {
    @Test
    void publishesOnlyTheMachineScopePolicy(@TempDir Path stateDir) throws Exception {
        DesktopCompanionProtocol.writePolicy(stateDir, ScopeMode.WORKSPACE, stateDir.toString());

        var policyFile = stateDir.resolve("desktop/desktop-companion-policy.json");
        assertTrue(Files.isRegularFile(policyFile));
        var policy = JsonCodec.read(Files.readAllBytes(policyFile), Map.class);
        assertEquals("workspace", policy.get("scope_mode"));
        assertEquals(stateDir.toString(), policy.get("workspace_root"));
        assertTrue(new String(Files.readAllBytes(policyFile), StandardCharsets.UTF_8)
                .indexOf("token") < 0);
    }

    @Test
    void unrestrictedMachineAcceptsNarrowerPerTaskContract(@TempDir Path stateDir) throws Exception {
        var scope = stateDir.resolve("project");
        Files.createDirectories(scope);
        var request = request(scope, scope, "path");

        assertDoesNotThrow(() -> DesktopCompanionServer.validateScope(
                new DesktopCompanionProtocol.Policy(ScopeMode.UNRESTRICTED, null), request));
    }

    @Test
    void boundedMachineStillRejectsContractOutsideMachineRoot(@TempDir Path stateDir) throws Exception {
        var machineRoot = stateDir.resolve("workspace");
        var outside = stateDir.resolve("outside");
        Files.createDirectories(machineRoot);
        Files.createDirectories(outside);
        var request = request(outside, outside, "path");

        assertThrows(IOException.class, () -> DesktopCompanionServer.validateScope(
                new DesktopCompanionProtocol.Policy(ScopeMode.WORKSPACE, machineRoot.toString()), request));
    }

    private static DesktopCompanionProtocol.Request request(Path cwd, Path scopeRoot, String scopeMode) {
        return new DesktopCompanionProtocol.Request("companion-token", "screenshot", null, List.of(),
                cwd.toString(), null, null, null, null, null, null, null, null, null,
                scopeMode, scopeRoot.toString(), Instant.now().plusSeconds(60), "test-session");
    }
}
