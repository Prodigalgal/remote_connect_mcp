package com.prodigalgal.remoteconnectmcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.prodigalgal.remoteconnectmcp.protocol.JsonCodec;
import com.prodigalgal.remoteconnectmcp.protocol.ScopeMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopCompanionServerTest {
    @Test
    void publishesOnlyTheMachineScopePolicy(@TempDir Path stateDir) throws Exception {
        DesktopCompanionServer.writePolicy(stateDir, ScopeMode.WORKSPACE, stateDir.toString());

        var policyFile = stateDir.resolve("desktop/desktop-companion-policy.json");
        assertTrue(Files.isRegularFile(policyFile));
        var policy = JsonCodec.read(Files.readAllBytes(policyFile), Map.class);
        assertEquals("workspace", policy.get("scope_mode"));
        assertEquals(stateDir.toString(), policy.get("workspace_root"));
        assertTrue(new String(Files.readAllBytes(policyFile), StandardCharsets.UTF_8)
                .indexOf("token") < 0);
    }
}
