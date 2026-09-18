package com.prodigalgal.remoteconnectmcp.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;

/**
 * Small, implementation-free contract shared by command-agent and the
 * user-session desktop-companion.  It contains only wire records and the
 * non-secret scope policy file; AWT, Robot and OS-specific code live solely in
 * the desktop executable.
 */
public final class DesktopCompanionProtocol {
    public static final String COMPANION_DIR = "desktop";
    public static final String ENDPOINT_FILE = "desktop-companion.json";
    public static final String TOKEN_FILE = "desktop-companion.token";
    public static final String POLICY_FILE = "desktop-companion-policy.json";

    private DesktopCompanionProtocol() {
    }

    public record Endpoint(int port, String token) {
    }

    public record Request(String token, String operation, String executable, List<String> args, String cwd,
                          String text, Integer x, Integer y, String key, Integer x2, Integer y2,
                          @JsonProperty("duration_ms") Integer durationMs, Integer screen,
                          @JsonProperty("window_title") String windowTitle,
                          @JsonProperty("scope_mode") String scopeMode,
                          @JsonProperty("scope_root") String scopeRoot,
                          @JsonProperty("contract_expires_at") Instant contractExpiresAt,
                          @JsonProperty("session_id") String sessionId) {
        /** Compatibility constructor for older command-agent callers. */
        public Request(String token, String operation, String executable, List<String> args, String cwd,
                       String text, Integer x, Integer y, String key, Integer x2, Integer y2,
                       Integer durationMs, Integer screen, String windowTitle, String scopeMode,
                       String scopeRoot, Instant contractExpiresAt) {
            this(token, operation, executable, args, cwd, text, x, y, key, x2, y2, durationMs,
                    screen, windowTitle, scopeMode, scopeRoot, contractExpiresAt, null);
        }
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

    /** Non-secret machine policy published for the user-session process. */
    public record Policy(ScopeMode scopeMode, String workspaceRoot) {
    }

    public static Path companionDirectory(Path stateDir) {
        if (stateDir == null) throw new IllegalArgumentException("desktop companion state directory is missing");
        return stateDir.toAbsolutePath().normalize().resolve(COMPANION_DIR);
    }

    public static Path endpointFile(Path stateDir) {
        return companionDirectory(stateDir).resolve(ENDPOINT_FILE).normalize();
    }

    public static Path policyFile(Path stateDir) {
        return companionDirectory(stateDir).resolve(POLICY_FILE).normalize();
    }

    /**
     * Publish the machine-level scope without putting a token or any user
     * data in the file.  The command-agent owns this write; the companion only
     * reads it and fails closed when it is absent or malformed.
     */
    public static void writePolicy(Path stateDir, ScopeMode scopeMode, String workspaceRoot) throws IOException {
        var directory = companionDirectory(stateDir);
        var target = policyFile(stateDir);
        if (!directory.equals(target.getParent())) throw new IOException("desktop companion policy path escapes state directory");
        var mode = scopeMode == null ? ScopeMode.WORKSPACE : scopeMode;
        var root = workspaceRoot == null || workspaceRoot.isBlank() ? null : workspaceRoot.trim();
        if (root != null && (root.length() > 4096 || root.indexOf('\u0000') >= 0
                || root.indexOf('\r') >= 0 || root.indexOf('\n') >= 0
                || root.chars().anyMatch(Character::isISOControl))) {
            throw new IOException("desktop companion policy root is invalid");
        }
        if (mode.bounded() && (root == null || root.isBlank())) {
            throw new IOException("bounded desktop companion policy requires a workspace root");
        }
        Files.createDirectories(directory);
        var temporary = Files.createTempFile(directory, "desktop-policy-", ".tmp");
        try {
            Files.write(temporary, JsonCodec.write(new Policy(mode, root)));
            restrictOwner(temporary);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictOwner(target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Read and validate the non-secret policy. Missing or malformed policy is
     * deliberately narrowed to the companion state directory.
     */
    public static Policy readPolicy(Path stateDir) throws IOException {
        var normalizedState = stateDir.toAbsolutePath().normalize();
        var file = policyFile(normalizedState);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return new Policy(ScopeMode.PATH, normalizedState.toString());
        }
        if (Files.size(file) > 4096) throw new IOException("desktop companion policy is too large");
        var parsed = JsonCodec.read(Files.readAllBytes(file), Policy.class);
        if (parsed == null || parsed.scopeMode() == null) {
            throw new IOException("desktop companion policy has no scope mode");
        }
        var root = parsed.workspaceRoot();
        if (root != null && (root.isBlank() || root.indexOf('\u0000') >= 0
                || root.indexOf('\r') >= 0 || root.indexOf('\n') >= 0
                || root.length() > 4096)) {
            throw new IOException("desktop companion policy root is invalid");
        }
        return new Policy(parsed.scopeMode(), root == null ? null : root.trim());
    }

    private static void restrictOwner(Path path) {
        try {
            Files.setPosixFilePermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows and filesystems without POSIX modes are protected by the
            // installer ACL; the file content itself contains no secret.
        }
    }
}
