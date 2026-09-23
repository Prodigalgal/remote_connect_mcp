package com.prodigalgal.remoteconnectmcp.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * Small, implementation-free contract shared by command-agent and the
 * user-session desktop-companion.  It contains only wire records; AWT, Robot and OS-specific code live solely in
 * the desktop executable.
 */
public final class DesktopCompanionProtocol {
    public static final String COMPANION_DIR = "desktop";
    public static final String ENDPOINT_FILE = "desktop-companion.json";
    public static final String TOKEN_FILE = "desktop-companion.token";

    private DesktopCompanionProtocol() {
    }

    public record Endpoint(int port, String token) {
    }

    public record Request(String token, String operation, String executable, List<String> args, String cwd,
                          String text, Integer x, Integer y, String key, Integer x2, Integer y2,
                          @JsonProperty("duration_ms") Integer durationMs, Integer screen,
                          @JsonProperty("window_title") String windowTitle,
                          @JsonProperty("contract_expires_at") Instant contractExpiresAt,
                          @JsonProperty("session_id") String sessionId) {
    }

    public record Response(boolean ok, String output, @JsonProperty("mime_type") String mimeType,
                           @JsonProperty("data_base64") String dataBase64, String error) {
        public byte[] data() {
            if (dataBase64 == null || dataBase64.isBlank()) return new byte[0];
            try {
                return Base64.getDecoder().decode(dataBase64);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("invalid desktop artifact", exception);
            }
        }
    }

    public static Path companionDirectory(Path stateDir) {
        if (stateDir == null) throw new IllegalArgumentException("desktop companion state directory is missing");
        return stateDir.toAbsolutePath().normalize().resolve(COMPANION_DIR);
    }

    public static Path endpointFile(Path stateDir) {
        return companionDirectory(stateDir).resolve(ENDPOINT_FILE).normalize();
    }

}
