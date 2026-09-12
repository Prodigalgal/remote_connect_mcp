package com.prodigalgal.remoteconnectmcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.prodigalgal.remoteconnectmcp.protocol.AgentConfigUpdate;
import com.prodigalgal.remoteconnectmcp.protocol.ScopeMode;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentRuntimeSettingsTest {
    @Test
    void appliesOnlyNewGenerationsAndPersistsAcrossRestart(@TempDir Path stateDir) throws Exception {
        var base = new AgentConfig(URI.create("https://center.invalid"), "enrollment", "agent", "host",
                stateDir.toString(), ScopeMode.UNRESTRICTED, null, List.of("command"), false,
                stateDir, Duration.ofSeconds(5), 1);
        var settings = new AgentRuntimeSettings(base);
        var update = new AgentConfigUpdate(3, 1500, 3);

        assertTrue(settings.apply(update));
        assertFalse(settings.apply(new AgentConfigUpdate(2, 3000, 2)));
        assertEquals(3, settings.generation());
        assertEquals(Duration.ofMillis(1500), settings.pollInterval());
        assertEquals(3, settings.maxConcurrency());
        assertTrue(Files.isRegularFile(stateDir.resolve("runtime-config.json")));

        var restored = new AgentRuntimeSettings(base);
        assertEquals(3, restored.generation());
        assertEquals(Duration.ofMillis(1500), restored.pollInterval());
        assertEquals(3, restored.maxConcurrency());
    }
}
