package com.prodigalgal.remoteconnectmcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.prodigalgal.remoteconnectmcp.protocol.ScopeMode;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentWakeClientTest {
    @Test
    void convertsCenterHttpSchemeToWebSocketEndpoint() {
        assertEquals("wss://center.example.test/agent/v1/ws",
                AgentWakeClient.websocketUri(URI.create("https://center.example.test/base")).toString());
        assertEquals("ws://127.0.0.1:8080/agent/v1/ws",
                AgentWakeClient.websocketUri(URI.create("http://127.0.0.1:8080")).toString());
    }

    @Test
    void websocketFeatureIsOptInWithoutChangingDefaultPolling() {
        var config = new AgentConfig(URI.create("http://127.0.0.1:8080"), "", "wake-agent", "wake-host",
                Path.of(".").toAbsolutePath().toString(), ScopeMode.UNRESTRICTED, null, List.of("command"),
                false, Path.of("."), Duration.ofMillis(250), 1);
        // No socket is opened by constructing the client; this guards the
        // low-resource, explicit-opt-in behavior used by the runtime.
        var client = AgentWakeClient.forTest(config, new AgentIdentity("machine", "token"), () -> { },
                java.net.http.HttpClient.newHttpClient());
        client.close();
    }
}
