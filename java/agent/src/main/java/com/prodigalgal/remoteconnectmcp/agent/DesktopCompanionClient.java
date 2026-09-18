package com.prodigalgal.remoteconnectmcp.agent;

import com.prodigalgal.remoteconnectmcp.protocol.DesktopCompanionProtocol;
import com.prodigalgal.remoteconnectmcp.protocol.JsonCodec;
import com.prodigalgal.remoteconnectmcp.protocol.TaskCommand;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;

/** Client for the optional user-session desktop companion on loopback. */
final class DesktopCompanionClient {
    private static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;

    private final DesktopCompanionProtocol.Endpoint endpoint;

    private DesktopCompanionClient(DesktopCompanionProtocol.Endpoint endpoint) {
        this.endpoint = endpoint;
    }

    static DesktopCompanionClient discover(Path stateDir) {
        try {
            var file = DesktopCompanionProtocol.endpointFile(stateDir);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null;
            var bytes = Files.readAllBytes(file);
            if (bytes.length > 4096) return null;
            var endpoint = JsonCodec.read(bytes, DesktopCompanionProtocol.Endpoint.class);
            if (endpoint.port() < 1 || endpoint.port() > 65535 || endpoint.token() == null || endpoint.token().isBlank()) return null;
            return new DesktopCompanionClient(endpoint);
        } catch (Exception ignored) {
            return null;
        }
    }

    DesktopCompanionProtocol.Response call(TaskCommand.DesktopAction action, Duration timeout) throws IOException {
        return call(action, null, timeout);
    }

    DesktopCompanionProtocol.Response call(TaskCommand.DesktopAction action,
                                           com.prodigalgal.remoteconnectmcp.protocol.ExecutionContract contract,
                                           Duration timeout) throws IOException {
        var request = new DesktopCompanionProtocol.Request(endpoint.token(), action.operation(), action.executable(), action.args(),
                action.cwd(), action.text(), action.x(), action.y(), action.key(), action.x2(), action.y2(),
                action.durationMs(), action.screen(), action.windowTitle(),
                contract == null ? null : contract.scopeMode().wireValue(),
                contract == null ? null : contract.scopeRoot(),
                contract == null ? null : contract.expiresAt(),
                contract == null ? null : contract.sessionId());
        var payload = JsonCodec.write(request);
        if (payload.length > 128 * 1024) throw new IOException("desktop companion request is too large");
        var timeoutMillis = Math.max(1000, Math.min(300_000, timeout.toMillis()));
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", endpoint.port()), Math.min(2000, Math.toIntExact(timeoutMillis)));
            socket.setSoTimeout(Math.toIntExact(timeoutMillis));
            try (var writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                 var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                writer.write(new String(payload, StandardCharsets.UTF_8));
                writer.newLine();
                writer.flush();
                var line = reader.readLine();
                if (line == null) throw new IOException("desktop companion closed the connection");
                if (line.getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES) throw new IOException("desktop companion response is too large");
                var response = JsonCodec.read(line.getBytes(StandardCharsets.UTF_8), DesktopCompanionProtocol.Response.class);
                if (!response.ok()) throw new IOException(response.error() == null ? "desktop companion rejected the request" : response.error());
                return response;
            }
        }
    }

}
