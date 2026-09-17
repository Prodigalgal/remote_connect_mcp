package com.prodigalgal.remoteconnectmcp.center;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.prodigalgal.remoteconnectmcp.protocol.RegisterRequest;
import com.prodigalgal.remoteconnectmcp.protocol.ScopeMode;
import com.prodigalgal.remoteconnectmcp.protocol.TaskCommand;
import com.prodigalgal.remoteconnectmcp.protocol.TaskKind;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactTransferServiceTest {
    @Test
    void streamsAgentFileToAnOwnerBoundSignedArtifact(@TempDir Path root) throws Exception {
        var registry = AgentRegistry.forTest("enrollment");
        var registration = registry.register(new RegisterRequest("command-agent", "host-a", "host-a", "linux", "amd64",
                "dev", root.toString(), ScopeMode.UNRESTRICTED, null, List.of("command", "file_transfer")), "enrollment");
        var tasks = new TaskService(registry);
        var store = new FileSystemArtifactStore(root.resolve("objects"));
        var tokens = new CenterTokenConfig() {
            @Override
            public String artifactDownloadSecret() {
                return "test-artifact-signing-secret";
            }
        };
        var service = new ArtifactTransferService(null, null, store, tasks, tokens);
        var origin = new TaskOrigin("principal-a", "token-a", "connection-a");
        var command = new TaskCommand("", TaskKind.COMMAND, "command", "ignored", root.toString(), Map.of(), 0, null, Instant.now());
        var request = new CreateTaskRequest(registration.machineId(), command, "transfer-1", "", "",
                ScopeMode.UNRESTRICTED, "", "", "low", false, origin);

        var created = service.createAgentToWeb(origin, request, root.resolve("report.txt").toString(), "report.txt", "text/plain");
        var data = "artifact payload".getBytes(StandardCharsets.UTF_8);
        var sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        var uploaded = service.receiveFromAgent(registration.machineId(), created.transfer().transferId(),
                new ByteArrayInputStream(data), data.length, sha, "report.txt", "text/plain");

        assertEquals("ready", uploaded.status());
        var descriptor = service.findByArtifact(created.transfer().artifactId(), origin).orElseThrow();
        assertEquals(data.length, descriptor.bytes());
        assertEquals(sha, descriptor.sha256());
        var signed = URI.create(descriptor.downloadUrl());
        var query = signed.getRawQuery();
        var expires = queryValue(query, "expires");
        var principal = queryValue(query, "principal");
        var signature = queryValue(query, "signature");
        try (var publicArtifact = service.openPublic(descriptor.artifactId(), Long.parseLong(expires), principal, signature).body()) {
            assertArrayEquals(data, publicArtifact.readAllBytes());
        }
        assertThrows(java.util.NoSuchElementException.class,
                () -> service.findByArtifact(descriptor.artifactId(), new TaskOrigin("principal-b", "token-b", "connection-b")).orElseThrow());
    }

    private static String queryValue(String query, String key) {
        for (var item : query.split("&")) {
            var pair = item.split("=", 2);
            if (pair.length == 2 && key.equals(pair[0])) {
                return java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("missing query parameter: " + key);
    }
}
