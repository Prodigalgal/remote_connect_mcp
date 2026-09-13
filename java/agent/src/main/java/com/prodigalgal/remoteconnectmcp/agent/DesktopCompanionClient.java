package com.prodigalgal.remoteconnectmcp.agent;

import com.fasterxml.jackson.annotation.JsonProperty;
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
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;

/** Client for the optional user-session desktop companion on loopback. */
final class DesktopCompanionClient {
    private static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
    private static final Path COMPANION_DIR = Path.of("desktop");
    private static final Path ENDPOINT_NAME = Path.of("desktop-companion.json");

    private final Endpoint endpoint;

    private DesktopCompanionClient(Endpoint endpoint) {
        this.endpoint = endpoint;
    }

    static DesktopCompanionClient discover(Path stateDir) {
        try {
            var file = stateDir.toAbsolutePath().normalize().resolve(COMPANION_DIR).resolve(ENDPOINT_NAME);
            if (!Files.isRegularFile(file)) return null;
            var bytes = Files.readAllBytes(file);
            if (bytes.length > 4096) return null;
            var endpoint = JsonCodec.read(bytes, Endpoint.class);
            if (endpoint.port() < 1 || endpoint.port() > 65535 || endpoint.token() == null || endpoint.token().isBlank()) return null;
            return new DesktopCompanionClient(endpoint);
        } catch (Exception ignored) {
            return null;
        }
    }

    Response call(TaskCommand.DesktopAction action, Duration timeout) throws IOException {
        var request = new Request(endpoint.token(), action.operation(), action.executable(), action.args(),
                action.cwd(), action.text(), action.x(), action.y(), action.key(), action.x2(), action.y2(),
                action.durationMs(), action.screen(), action.windowTitle());
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
                var response = JsonCodec.read(line.getBytes(StandardCharsets.UTF_8), Response.class);
                if (!response.ok()) throw new IOException(response.error() == null ? "desktop companion rejected the request" : response.error());
                return response;
            }
        }
    }

    record Endpoint(int port, String token) {
    }

    record Request(String token, String operation, String executable, java.util.List<String> args, String cwd,
                   String text, Integer x, Integer y, String key, Integer x2, Integer y2,
                   @JsonProperty("duration_ms") Integer durationMs, Integer screen,
                   @JsonProperty("window_title") String windowTitle) {
    }

    record Response(boolean ok, String output, @JsonProperty("mime_type") String mimeType,
                    @JsonProperty("data_base64") String dataBase64, String error) {
        byte[] data() {
            if (dataBase64 == null || dataBase64.isBlank()) return new byte[0];
            try { return Base64.getDecoder().decode(dataBase64); }
            catch (IllegalArgumentException exception) { throw new IllegalArgumentException("invalid desktop artifact", exception); }
        }
    }
}
