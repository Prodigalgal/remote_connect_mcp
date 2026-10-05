package com.prodigalgal.remotecontrolmcp.center;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.prodigalgal.remotecontrolmcp.protocol.RegisterRequest;
import com.prodigalgal.remotecontrolmcp.protocol.PollRequest;
import com.prodigalgal.remotecontrolmcp.protocol.TaskCommand;
import com.prodigalgal.remotecontrolmcp.protocol.TaskKind;
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
    void largeTextIsStreamedInUtf8PagesWithoutLoadingTheWholeObject(@TempDir Path root) throws Exception {
        var registry = AgentRegistry.forTest("enrollment");
        var machine = registry.register(new RegisterRequest("text-agent", "host-text", "host-text", "linux", "amd64",
                "dev", root.toString(), List.of("command", "file_transfer")), "enrollment");
        var tasks = new TaskService(registry);
        var store = org.mockito.Mockito.spy(new FileSystemArtifactStore(root.resolve("objects")));
        var tokens = new CenterTokenConfig() {
            @Override
            public String artifactDownloadSecret() { return "text-page-signing-secret"; }
        };
        var service = new ArtifactTransferService(null, null, store, tasks, tokens);
        var origin = TaskOrigin.configured();
        var command = new TaskCommand("", TaskKind.COMMAND, "command", "ignored", root.toString(), Map.of(), 0, null, Instant.now());
        var created = service.createAgentToWeb(origin, new CreateTaskRequest(machine.machineId(), command, "text-pages"),
                root.resolve("large.txt").toString(), "large.txt", "text/plain");
        var leased = tasks.poll(machine.machineId(), new PollRequest(List.of(), 1, List.of("file_transfer"))).task();
        var content = "中文😀line\n".repeat(12000);
        var bytes = content.getBytes(StandardCharsets.UTF_8);
        service.receiveFromAgent(machine.machineId(), created.transfer().transferId(), new ByteArrayInputStream(bytes),
                bytes.length, sha256(bytes), "large.txt", "text/plain", leased.attempt());
        var reconstructed = new StringBuilder();
        long cursor = 0;
        while (true) {
            var page = service.readTextPage(created.transfer().artifactId(), origin, cursor, 4096).orElseThrow();
            assertTrue(page.data().length <= 4096);
            assertTrue(page.nextCursor() > cursor);
            reconstructed.append(new String(page.data(), StandardCharsets.UTF_8));
            cursor = page.nextCursor();
            if (!page.more()) break;
        }
        assertEquals(content, reconstructed.toString());
        org.mockito.Mockito.verify(store, org.mockito.Mockito.never()).read(org.mockito.ArgumentMatchers.anyString());
        assertEquals(bytes.length, service.findByArtifact(created.transfer().artifactId(), origin).orElseThrow().bytes());
        assertThrows(IllegalArgumentException.class,
                () -> service.readTextPage(created.transfer().artifactId(), origin, bytes.length + 1, 4096));
        assertThrows(IllegalArgumentException.class,
                () -> service.readTextPage(created.transfer().artifactId(), new TaskOrigin("other", "token", "connection"), 0, 4096));
    }

    @Test
    void readsSmallImageInlineForMcpRendering(@TempDir Path root) throws Exception {
        var registry = AgentRegistry.forTest("enrollment");
        var registration = registry.register(new RegisterRequest("command-agent", "host-image", "host-image", "linux", "amd64",
                "dev", root.toString(), List.of("command", "file_transfer")), "enrollment");
        var tasks = new TaskService(registry);
        var store = new FileSystemArtifactStore(root.resolve("objects"));
        var tokens = new CenterTokenConfig() {
            @Override
            public String artifactDownloadSecret() {
                return "image-artifact-signing-secret";
            }
        };
        var service = new ArtifactTransferService(null, null, store, tasks, tokens);
        var origin = new TaskOrigin("principal-image", "token-image", "connection-image");
        var request = new CreateTaskRequest(registration.machineId(),
                new TaskCommand("", TaskKind.COMMAND, "command", "ignored", root.toString(), Map.of(), 0, null, Instant.now()),
                 "image-transfer", null, "", "low", false, origin);
        var image = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a};
        var sha = sha256(image);
        var created = service.createAgentToWeb(origin, request, root.resolve("camera.jpg").toString(), "camera.jpg", "image/jpeg");
        var leased = tasks.poll(registration.machineId(), new PollRequest(List.of(), 1, List.of("file_transfer"))).task();
        assertNotNull(leased);
        service.receiveFromAgent(registration.machineId(), created.transfer().transferId(), new ByteArrayInputStream(image),
                image.length, sha, "camera.jpg", "image/jpeg", leased.attempt());

        var inline = service.readInline(created.transfer().artifactId(), origin, 512 * 1024).orElseThrow();
        assertEquals("image/jpeg", inline.mimeType());
        assertEquals(sha, inline.sha256());
        assertArrayEquals(image, inline.data());
        assertTrue(service.readInline(created.transfer().artifactId(), origin, 1).isEmpty());

        var textRequest = new CreateTaskRequest(registration.machineId(), request.command(), "text-transfer", null,
                "", "low", false, origin);
        var text = "inline text payload".getBytes(StandardCharsets.UTF_8);
        var textCreated = service.createAgentToWeb(origin, textRequest, root.resolve("note.txt").toString(), "note.txt", "text/plain");
        var textLeased = tasks.poll(registration.machineId(), new PollRequest(List.of(), 1, List.of("file_transfer"))).task();
        assertNotNull(textLeased);
        service.receiveFromAgent(registration.machineId(), textCreated.transfer().transferId(), new ByteArrayInputStream(text),
                text.length, sha256(text), "note.txt", "text/plain", textLeased.attempt());
        var inlineText = service.readInlineContent(textCreated.transfer().artifactId(), origin, 512 * 1024).orElseThrow();
        assertEquals("text/plain", inlineText.mimeType());
        assertArrayEquals(text, inlineText.data());
    }

    @Test
    void streamsAgentFileToAnOwnerBoundSignedArtifact(@TempDir Path root) throws Exception {
        var registry = AgentRegistry.forTest("enrollment");
        var registration = registry.register(new RegisterRequest("command-agent", "host-a", "host-a", "linux", "amd64",
                "dev", root.toString(), List.of("command", "file_transfer")), "enrollment");
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
        var request = new CreateTaskRequest(registration.machineId(), command, "transfer-1", null,
                "", "low", false, origin);

        var created = service.createAgentToWeb(origin, request, root.resolve("report.txt").toString(), "report.txt", "text/plain");
        var retried = service.createAgentToWeb(origin, request, root.resolve("report.txt").toString(), "report.txt", "text/plain");
        assertEquals(created.transfer().transferId(), retried.transfer().transferId());
        var leased = tasks.poll(registration.machineId(), new PollRequest(List.of(), 1, List.of("file_transfer"))).task();
        assertNotNull(leased);
        var attempt = leased.attempt();
        var data = "artifact payload".getBytes(StandardCharsets.UTF_8);
        var sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        var uploaded = service.receiveFromAgent(registration.machineId(), created.transfer().transferId(),
                new ByteArrayInputStream(data), data.length, sha, "report.txt", "text/plain", attempt);

        assertEquals("delivered", uploaded.status());
        var descriptor = service.findByArtifact(created.transfer().artifactId(), origin).orElseThrow();
        assertEquals(data.length, descriptor.bytes());
        assertEquals(sha, descriptor.sha256());
        var signed = URI.create(descriptor.downloadUrl());
        var query = signed.getRawQuery();
        var token = queryValue(query, "token");
        try (var publicArtifact = service.openPublic(token).body()) {
            assertArrayEquals(data, publicArtifact.readAllBytes());
        }
        assertThrows(SecurityException.class, () -> service.openPublic(token.substring(0, token.length() - 1) + "x"));

        var failedRequest = new CreateTaskRequest(registration.machineId(), command, "transfer-failure", null,
                "", "low", false, origin);
        var failed = service.createAgentToWeb(origin, failedRequest, root.resolve("failed.txt").toString(), "failed.txt", "text/plain");
        var failedLease = tasks.poll(registration.machineId(), new PollRequest(List.of(), 1, List.of("file_transfer"))).task();
        assertNotNull(failedLease);
        assertThrows(IllegalArgumentException.class, () -> service.receiveFromAgent(registration.machineId(),
                failed.transfer().transferId(), new ByteArrayInputStream(data), data.length, "0".repeat(64),
                "failed.txt", "text/plain", failedLease.attempt()));
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
                "dev", root.toString(), List.of("command", "file_transfer")), "enrollment");
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
                 "resume-transfer", null, "", "low", false, origin);
        var data = "resumable payload".getBytes(StandardCharsets.UTF_8);
        var sha = sha256(data);
        var transfer = service.createAgentToWeb(origin, request, root.resolve("resume.txt").toString(), "resume.txt", "text/plain");
        var leased = tasks.poll(registration.machineId(), new PollRequest(List.of(), 1, List.of("file_transfer"))).task();
        assertNotNull(leased);
        var attempt = leased.attempt();

        var first = service.receiveFromAgentChunk(registration.machineId(), transfer.transfer().transferId(),
                new ByteArrayInputStream(java.util.Arrays.copyOfRange(data, 0, 8)), 8, 0, data.length, sha,
                "resume.txt", "text/plain", attempt);
        assertEquals("delivering", first.status());
        assertEquals(8, first.bytes());
        assertEquals(8, service.resumeFromAgent(registration.machineId(), transfer.transfer().transferId(), attempt).offset());
        var replay = service.receiveFromAgentChunk(registration.machineId(), transfer.transfer().transferId(),
                new ByteArrayInputStream(java.util.Arrays.copyOfRange(data, 0, 8)), 8, 0, data.length, sha,
                "resume.txt", "text/plain", attempt);
        assertEquals(first.bytes(), replay.bytes());

        var completed = service.receiveFromAgentChunk(registration.machineId(), transfer.transfer().transferId(),
                new ByteArrayInputStream(java.util.Arrays.copyOfRange(data, 8, data.length)), data.length - 8, 8,
                data.length, sha, "resume.txt", "text/plain", attempt);
        assertEquals("delivered", completed.status());
        assertEquals(data.length, completed.bytes());
        var descriptor = service.findByArtifact(transfer.transfer().artifactId(), origin).orElseThrow();
        assertEquals(data.length, descriptor.bytes());
        assertEquals(sha, descriptor.sha256());
    }

    @Test
    void keepsPartialSpoolWhenAChunkEndsBeforeItsDeclaredLength(@TempDir Path root) throws Exception {
        var registry = AgentRegistry.forTest("enrollment");
        var registration = registry.register(new RegisterRequest("command-agent", "host-stall", "host-stall", "linux", "amd64",
                "dev", root.toString(), List.of("command", "file_transfer")), "enrollment");
        var tasks = new TaskService(registry);
        var store = new FileSystemArtifactStore(root.resolve("objects"));
        var tokens = new CenterTokenConfig() {
            @Override
            public String artifactDownloadSecret() {
                return "stall-signing-secret";
            }
        };
        var service = new ArtifactTransferService(null, null, store, tasks, tokens);
        var origin = new TaskOrigin("principal-stall", "token-stall", "connection-stall");
        var data = "partial payload".getBytes(StandardCharsets.UTF_8);
        var request = new CreateTaskRequest(registration.machineId(),
                new TaskCommand("", TaskKind.COMMAND, "command", "ignored", root.toString(), Map.of(), 0, null, Instant.now()),
                 "stall-transfer", null, "", "low", false, origin);
        var transfer = service.createAgentToWeb(origin, request, root.resolve("stall.txt").toString(), "stall.txt", "text/plain");
        var leased = tasks.poll(registration.machineId(), new PollRequest(List.of(), 1, List.of("file_transfer"))).task();
        assertNotNull(leased);
        assertThrows(ArtifactTransferService.TransferTemporaryException.class,
                () -> service.receiveFromAgentChunk(registration.machineId(), transfer.transfer().transferId(),
                        new ByteArrayInputStream(java.util.Arrays.copyOfRange(data, 0, 3)), 6, 0, data.length,
                        sha256(data), "stall.txt", "text/plain", leased.attempt()));
        assertEquals(3, service.resumeFromAgent(registration.machineId(), transfer.transfer().transferId(), leased.attempt()).offset());
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

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
}
