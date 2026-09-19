package com.prodigalgal.remoteconnectmcp.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.prodigalgal.remoteconnectmcp.protocol.ArtifactResponse;
import com.prodigalgal.remoteconnectmcp.protocol.ExecutionContract;
import com.prodigalgal.remoteconnectmcp.protocol.OutputResponse;
import com.prodigalgal.remoteconnectmcp.protocol.PollRequest;
import com.prodigalgal.remoteconnectmcp.protocol.PollResponse;
import com.prodigalgal.remoteconnectmcp.protocol.RegisterResponse;
import com.prodigalgal.remoteconnectmcp.protocol.ScopeMode;
import com.prodigalgal.remoteconnectmcp.protocol.TaskCommand;
import com.prodigalgal.remoteconnectmcp.protocol.TaskKind;
import com.prodigalgal.remoteconnectmcp.protocol.TaskUpdateRequest;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BrowserTaskRunnerTest {
    @Test
    void adapterReceivesBoundedRequestFileAndStreamsOutput(@TempDir Path stateDir) throws Exception {
        var adapter = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "echo {\"status\":\"completed\",\"output\":\"manifest-output\",\"artifact\":{\"path\":\"artifact.txt\",\"mime_type\":\"text/plain\"}} > \"%RCM_BROWSER_RESULT_FILE%\" & echo %RCM_BROWSER_SESSION_FILE% > \"%RCM_BROWSER_SESSION_FILE%\" & echo artifact > \"%RCM_BROWSER_ARTIFACT_DIR%\\artifact.txt\" & type \"%RCM_BROWSER_TASK_REQUEST_FILE%\""
                : "printf '%s' '{\"status\":\"completed\",\"output\":\"manifest-output\",\"artifact\":{\"path\":\"artifact.txt\",\"mime_type\":\"text/plain\"}}' > \"$RCM_BROWSER_RESULT_FILE\"; printf '%s' \"$RCM_BROWSER_SESSION_FILE\" > \"$RCM_BROWSER_SESSION_FILE\"; printf artifact > \"$RCM_BROWSER_ARTIFACT_DIR/artifact.txt\"; cat \"$RCM_BROWSER_TASK_REQUEST_FILE\"";
        var config = new AgentConfig(URI.create("http://127.0.0.1:18183"), "", "browser-agent", "browser-host",
                stateDir.toString(), ScopeMode.UNRESTRICTED, null, List.of("browser"), false, stateDir,
                Duration.ofMillis(250), 1, 4L * 1024 * 1024, 8L * 1024 * 1024, adapter);
        var task = new TaskCommand("browser-task", TaskKind.BROWSER, "browser", "{\"action\":\"observe\"}",
                stateDir.toString(), Map.of(), 30, null, Instant.now(),
                new ExecutionContract("machine-browser", "browser-host", ScopeMode.UNRESTRICTED,
                        null, null, null, "session-test", "browser", ExecutionContract.Budget.defaults(),
                        Instant.now().plusSeconds(3600), "test-browser", "low", false, "lease-test"), 1);
        var transport = new RecordingTransport();
        var browserAgent = writeBrowserAgent(stateDir);

        new BrowserTaskRunner(config, new AgentIdentity("machine-browser", "daily-browser"), task, transport,
                new AgentResourceBudget(config.maxAggregateOutputBytes()),
                new AgentProcessBudget(config.maxTotalChildProcesses()), browserAgent).run();

        assertTrue(transport.statuses.contains("running"), "statuses=" + transport.statuses);
        assertTrue(transport.statuses.contains("completed"), "statuses=" + transport.statuses);
        assertTrue(transport.output.toString().contains("browser-task"), "output=" + transport.output);
        assertTrue(transport.output.toString().contains("\"kind\":\"browser\""), "request=" + transport.output);
        assertTrue(transport.output.toString().contains("manifest-output"), "manifest=" + transport.output);
        assertTrue(transport.artifactMime.equals("text/plain"), "mime=" + transport.artifactMime);
        assertTrue(transport.artifact.toString().contains("artifact"), "artifact=" + transport.artifact);
        try (var files = Files.list(stateDir)) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().startsWith("browser-session")
                    && Files.isRegularFile(path)), "session marker was not passed to adapter");
        }
    }

    private static Path writeBrowserAgent(Path stateDir) throws IOException {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            var script = stateDir.resolve("browser-agent.cmd");
            Files.writeString(script, "@echo off\r\n"
                    + "> \"%RCM_BROWSER_RESULT_FILE%\" echo {\"status\":\"completed\",\"output\":\"manifest-output\",\"artifact\":{\"path\":\"artifact.txt\",\"mime_type\":\"text/plain\"}}\r\n"
                    + "> \"%RCM_BROWSER_SESSION_FILE%\" echo %RCM_BROWSER_SESSION_FILE%\r\n"
                    + "> \"%RCM_BROWSER_ARTIFACT_DIR%\\artifact.txt\" echo artifact\r\n"
                    + "type \"%RCM_BROWSER_TASK_REQUEST_FILE%\"\r\n", java.nio.charset.StandardCharsets.UTF_8);
            return script;
        }
        var script = stateDir.resolve("browser-agent.sh");
        Files.writeString(script, "#!/bin/sh\n"
                + "printf '%s' '{\"status\":\"completed\",\"output\":\"manifest-output\",\"artifact\":{\"path\":\"artifact.txt\",\"mime_type\":\"text/plain\"}}' > \"$RCM_BROWSER_RESULT_FILE\"\n"
                + "printf '%s' \"$RCM_BROWSER_SESSION_FILE\" > \"$RCM_BROWSER_SESSION_FILE\"\n"
                + "printf artifact > \"$RCM_BROWSER_ARTIFACT_DIR/artifact.txt\"\n"
                + "cat \"$RCM_BROWSER_TASK_REQUEST_FILE\"\n", java.nio.charset.StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, java.util.EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        return script;
    }

    private static final class RecordingTransport implements AgentTransport {
        private final List<String> statuses = new CopyOnWriteArrayList<>();
        private final StringBuilder output = new StringBuilder();
        private final StringBuilder artifact = new StringBuilder();
        private String artifactMime = "";

        @Override
        public RegisterResponse register(AgentConfig config) {
            return new RegisterResponse("machine-browser", "daily-browser");
        }

        @Override
        public PollResponse poll(String machineId, String token, PollRequest request) {
            return PollResponse.empty();
        }

        @Override
        public void updateState(String machineId, String token, String taskId, int attempt, TaskUpdateRequest request) {
            statuses.add(request.status());
        }

        @Override
        public OutputResponse appendOutput(String machineId, String token, String taskId, int attempt, long offset, byte[] data)
                throws IOException {
            synchronized (output) {
                output.append(new String(data, java.nio.charset.StandardCharsets.UTF_8));
            }
            return new OutputResponse(offset + data.length);
        }

        @Override
        public ArtifactResponse appendArtifact(String machineId, String token, String taskId, int attempt, String mimeType,
                                               String sha256, byte[] data) {
            artifactMime = mimeType;
            artifact.append(new String(data, java.nio.charset.StandardCharsets.UTF_8));
            return new ArtifactResponse(data.length, sha256);
        }

        @Override
        public com.prodigalgal.remoteconnectmcp.protocol.FileTransferResponse uploadTransfer(
                String machineId, String token, String transferId, Path source, String fileName,
                String mimeType, long expectedBytes, String expectedSha256, int attempt) {
            return new com.prodigalgal.remoteconnectmcp.protocol.FileTransferResponse(
                    transferId, null, "completed", expectedBytes, expectedSha256, null);
        }

        @Override
        public AgentTransport.TransferResume queryTransferResume(String machineId, String token,
                                                                 String transferId, int attempt) {
            return new AgentTransport.TransferResume(0, "ready");
        }
    }
}
