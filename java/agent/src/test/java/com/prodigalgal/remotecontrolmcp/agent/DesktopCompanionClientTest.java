package com.prodigalgal.remotecontrolmcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.prodigalgal.remotecontrolmcp.protocol.JsonCodec;
import com.prodigalgal.remotecontrolmcp.protocol.DesktopCompanionProtocol;
import com.prodigalgal.remotecontrolmcp.protocol.TaskCommand;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopCompanionClientTest {
    @Test
    void metadataSeparatesServiceIdentityFromInteractiveDesktopAndToleratesOlderCompanions(@TempDir Path stateDir) throws Exception {
        var companionDir = Files.createDirectories(stateDir.resolve("desktop"));
        var endpoint = companionDir.resolve(DesktopCompanionProtocol.ENDPOINT_FILE);
        Files.write(endpoint, JsonCodec.write(new DesktopCompanionProtocol.Endpoint(12345, "test-token")));
        var config = new AgentConfig(java.net.URI.create("https://center.invalid"), "enrollment", "agent", "host",
                stateDir.toString(), List.of("command", "desktop"), true, stateDir, Duration.ofSeconds(5), 1);
        assertEquals("", config.metadata().runtime().userContext().desktopPath());
        var contextFile = companionDir.resolve(DesktopCompanionProtocol.USER_CONTEXT_FILE);
        Files.write(contextFile, JsonCodec.write(new com.prodigalgal.remotecontrolmcp.protocol.AgentUserContext(
                "", "", "interactive-zzp", "C:\\Users\\interactive-zzp\\OneDrive\\桌面")));
        var reported = config.metadata().runtime().userContext();
        assertEquals(System.getProperty("user.name"), reported.commandUser());
        assertEquals(System.getProperty("user.home"), reported.commandHome());
        assertEquals("interactive-zzp", reported.interactiveUser());
        assertEquals("C:\\Users\\interactive-zzp\\OneDrive\\桌面", reported.desktopPath());
        Files.delete(endpoint);
        assertEquals("", config.metadata().runtime().userContext().interactiveUser());
        Files.writeString(contextFile, "x".repeat(8193));
        assertNull(DesktopCompanionClient.userContext(stateDir));
        Files.writeString(contextFile, "malformed");
        assertNull(DesktopCompanionClient.userContext(stateDir));
    }

    @Test
    void discoversLoopbackEndpointAndKeepsArtifactBounded(@TempDir Path stateDir) throws Exception {
        var companionDir = Files.createDirectories(stateDir.resolve("desktop"));
        try (var server = new ServerSocket(0, 4, InetAddress.getLoopbackAddress())) {
            Files.write(companionDir.resolve("desktop-companion.json"),
                    JsonCodec.write(new DesktopCompanionProtocol.Endpoint(server.getLocalPort(), "local-secret")));
            var worker = Thread.startVirtualThread(() -> {
                try (var socket = server.accept();
                     var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                     var writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
                    var request = JsonCodec.read(reader.readLine().getBytes(StandardCharsets.UTF_8), DesktopCompanionProtocol.Request.class);
                    assertEquals("local-secret", request.token());
                    assertEquals("screenshot", request.operation());
                    var response = new DesktopCompanionProtocol.Response(true, "ok", "image/png",
                            Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}), null);
                    writer.write(new String(JsonCodec.write(response), StandardCharsets.UTF_8));
                    writer.newLine();
                    writer.flush();
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            });

            var client = DesktopCompanionClient.discover(stateDir);
            assertNotNull(client);
            var response = client.call(new TaskCommand.DesktopAction("screenshot", null, List.of(), null,
                    null, null, null, null), Duration.ofSeconds(3));
            assertEquals("ok", response.output());
            assertEquals(3, response.data().length);
            worker.join(Duration.ofSeconds(3));
        }
    }
}
