package com.prodigalgal.remoteconnectmcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.prodigalgal.remoteconnectmcp.protocol.JsonCodec;
import com.prodigalgal.remoteconnectmcp.protocol.TaskCommand;
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
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopCompanionClientTest {
    @Test
    void discoversLoopbackEndpointAndKeepsArtifactBounded(@TempDir Path stateDir) throws Exception {
        var companionDir = Files.createDirectories(stateDir.resolve("desktop"));
        try (var server = new ServerSocket(0, 4, InetAddress.getLoopbackAddress())) {
            Files.write(companionDir.resolve("desktop-companion.json"),
                    JsonCodec.write(new DesktopCompanionClient.Endpoint(server.getLocalPort(), "local-secret")));
            var worker = Thread.startVirtualThread(() -> {
                try (var socket = server.accept();
                     var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                     var writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
                    var request = JsonCodec.read(reader.readLine().getBytes(StandardCharsets.UTF_8), DesktopCompanionClient.Request.class);
                    assertEquals("local-secret", request.token());
                    assertEquals("screenshot", request.operation());
                    var response = new DesktopCompanionClient.Response(true, "ok", "image/png",
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
