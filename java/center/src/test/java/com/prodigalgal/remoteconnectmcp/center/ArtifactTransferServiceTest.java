package com.prodigalgal.remoteconnectmcp.center;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.prodigalgal.remoteconnectmcp.protocol.RegisterRequest;
import com.prodigalgal.remoteconnectmcp.protocol.ScopeMode;
import com.prodigalgal.remoteconnectmcp.protocol.TaskCommand;
import com.prodigalgal.remoteconnectmcp.protocol.TaskKind;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
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
        var retried = service.createAgentToWeb(origin, request, root.resolve("report.txt").toString(), "report.txt", "text/plain");
        assertEquals(created.transfer().transferId(), retried.transfer().transferId());
        var data = "artifact payload".getBytes(StandardCharsets.UTF_8);
        var sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        var uploaded = service.receiveFromAgent(registration.machineId(), created.transfer().transferId(),
                new ByteArrayInputStream(data), data.length, sha, "report.txt", "text/plain");

        assertEquals("delivered", uploaded.status());
        var descriptor = service.findByArtifact(created.transfer().artifactId(), origin).orElseThrow();
        assertEquals(data.length, descriptor.bytes());
        assertEquals(sha, descriptor.sha256());
        var signed = URI.create(descriptor.downloadUrl());
        var query = signed.getRawQuery();
        var expires = queryValue(query, "expires");
        var principal = queryValue(query, "principal");
        var connection = queryValue(query, "connection");
        var session = queryValue(query, "session");
        var purpose = queryValue(query, "purpose");
        var signature = queryValue(query, "signature");
        assertFalse(session.isBlank());
        try (var publicArtifact = service.openPublic(descriptor.artifactId(), Long.parseLong(expires), principal, connection, session, purpose, signature).body()) {
            assertArrayEquals(data, publicArtifact.readAllBytes());
        }
        assertThrows(SecurityException.class,
                () -> service.openPublic(descriptor.artifactId(), Long.parseLong(expires), principal, connection,
                        session + "-other", purpose, signature));

        var failedRequest = new CreateTaskRequest(registration.machineId(), command, "transfer-failure", "", "",
                ScopeMode.UNRESTRICTED, "", "", "low", false, origin);
        var failed = service.createAgentToWeb(origin, failedRequest, root.resolve("failed.txt").toString(), "failed.txt", "text/plain");
        assertThrows(IllegalArgumentException.class, () -> service.receiveFromAgent(registration.machineId(),
                failed.transfer().transferId(), new ByteArrayInputStream(data), data.length, "0".repeat(64),
                "failed.txt", "text/plain"));
        var failedDescriptor = service.findByTransfer(failed.transfer().transferId(), origin).orElseThrow();
        assertEquals("failed", failedDescriptor.status());
        assertNotNull(failedDescriptor.error());
        assertThrows(java.util.NoSuchElementException.class,
                () -> service.findByArtifact(descriptor.artifactId(), new TaskOrigin("principal-b", "token-b", "connection-b")).orElseThrow());
    }

    @Test
    void resumesAgentUploadByAcknowledgedOffset(@TempDir Path root) throws Exception {
        var registry = AgentRegistry.forTest("enrollment");
        var registration = registry.register(new RegisterRequest("command-agent", "host-resume", "host-resume", "linux", "amd64",
                "dev", root.toString(), ScopeMode.UNRESTRICTED, null, List.of("command", "file_transfer")), "enrollment");
        var tasks = new TaskService(registry);
        var store = new FileSystemArtifactStore(root.resolve("objects"));
        var tokens = new CenterTokenConfig() {
            @Override
            public String artifactDownloadSecret() {
                return "resume-signing-secret";
            }
        };
        var service = new ArtifactTransferService(null, null, store, tasks, tokens);
        var origin = new TaskOrigin("principal-resume", "token-resume", "connection-resume");
        var request = new CreateTaskRequest(registration.machineId(),
                new TaskCommand("", TaskKind.COMMAND, "command", "ignored", root.toString(), Map.of(), 0, null, Instant.now()),
                "resume-transfer", "", "", ScopeMode.UNRESTRICTED, "", "", "low", false, origin);
        var data = "resumable payload".getBytes(StandardCharsets.UTF_8);
        var sha = sha256(data);
        var transfer = service.createAgentToWeb(origin, request, root.resolve("resume.txt").toString(), "resume.txt", "text/plain");

        var first = service.receiveFromAgentChunk(registration.machineId(), transfer.transfer().transferId(),
                new ByteArrayInputStream(java.util.Arrays.copyOfRange(data, 0, 8)), 8, 0, data.length, sha,
                "resume.txt", "text/plain", null);
        assertEquals("delivering", first.status());
        assertEquals(8, first.bytes());
        assertEquals(8, service.resumeFromAgent(registration.machineId(), transfer.transfer().transferId(), null).offset());
        var replay = service.receiveFromAgentChunk(registration.machineId(), transfer.transfer().transferId(),
                new ByteArrayInputStream(java.util.Arrays.copyOfRange(data, 0, 8)), 8, 0, data.length, sha,
                "resume.txt", "text/plain", null);
        assertEquals(first.bytes(), replay.bytes());

        var completed = service.receiveFromAgentChunk(registration.machineId(), transfer.transfer().transferId(),
                new ByteArrayInputStream(java.util.Arrays.copyOfRange(data, 8, data.length)), data.length - 8, 8,
                data.length, sha, "resume.txt", "text/plain", null);
        assertEquals("delivered", completed.status());
        assertEquals(data.length, completed.bytes());
        var descriptor = service.findByArtifact(transfer.transfer().artifactId(), origin).orElseThrow();
        assertEquals(data.length, descriptor.bytes());
        assertEquals(sha, descriptor.sha256());
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
