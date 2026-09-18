package com.prodigalgal.remoteconnectmcp.center;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class CenterTokenConfig {
    private final String enrollmentToken;
    private final String mcpToken;
    private final String adminToken;
    private final String artifactSigningSecret;
    private final String artifactSigningSecretPrevious;
    private final String artifactSigningKid;
    private final String artifactSigningKidPrevious;
    private final boolean allowSharedEnrollment;

    public CenterTokenConfig() {
        var value = System.getenv("REMOTE_CONNECT_MCP_CENTER_ENROLLMENT_TOKEN");
        enrollmentToken = value == null || value.isBlank() ? null : value.trim();
        var mcp = System.getenv("REMOTE_CONNECT_MCP_CENTER_MCP_TOKEN");
        mcpToken = mcp == null || mcp.isBlank() ? null : mcp.trim();
        var admin = System.getenv("REMOTE_CONNECT_MCP_CENTER_ADMIN_TOKEN");
        adminToken = admin == null || admin.isBlank() ? null : admin.trim();
        var artifact = System.getenv("REMOTE_CONNECT_MCP_CENTER_ARTIFACT_SIGNING_SECRET");
        artifactSigningSecret = artifact == null || artifact.isBlank() ? null : artifact.trim();
        var previousArtifact = System.getenv("REMOTE_CONNECT_MCP_CENTER_ARTIFACT_SIGNING_SECRET_PREVIOUS");
        artifactSigningSecretPrevious = previousArtifact == null || previousArtifact.isBlank() ? null : previousArtifact.trim();
        artifactSigningKid = safeKid(System.getenv("REMOTE_CONNECT_MCP_CENTER_ARTIFACT_SIGNING_KID"), "v1");
        artifactSigningKidPrevious = safeKid(System.getenv("REMOTE_CONNECT_MCP_CENTER_ARTIFACT_SIGNING_KID_PREVIOUS"), "v0");
        allowSharedEnrollment = Boolean.parseBoolean(System.getenv().getOrDefault("RCM_CENTER_ALLOW_SHARED_ENROLLMENT", "false"));
    }

    public Optional<String> enrollmentToken() {
        return Optional.ofNullable(enrollmentToken);
    }

    public boolean acceptsEnrollment(String candidate) {
        return allowSharedEnrollment && accepts(enrollmentToken, candidate);
    }

    public Optional<String> mcpToken() {
        return Optional.ofNullable(mcpToken);
    }

    public boolean acceptsMcp(String candidate) {
        return accepts(mcpToken, candidate);
    }

    public Optional<String> adminToken() {
        return Optional.ofNullable(adminToken);
    }

    public boolean acceptsAdmin(String candidate) {
        return accepts(adminToken, candidate);
    }

    /** Key used only to sign short-lived browser artifact URLs. */
    public String artifactDownloadSecret() {
        if (artifactSigningSecret != null && !artifactSigningSecret.isBlank()) return artifactSigningSecret;
        throw new IllegalStateException("REMOTE_CONNECT_MCP_CENTER_ARTIFACT_SIGNING_SECRET is required to sign artifact URLs");
    }

    /** True when the current signing key is configured for artifact URLs. */
    public boolean artifactSigningConfigured() {
        if (artifactSigningSecret != null && !artifactSigningSecret.isBlank()) return true;
        // Protocol tests and embedders may provide a CenterTokenConfig
        // subclass that overrides artifactDownloadSecret() without exposing
        // process environment variables.
        try {
            return !artifactDownloadSecret().isBlank();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** Key used for newly issued artifact URLs. */
    public ArtifactSigningKey currentArtifactSigningKey() {
        return new ArtifactSigningKey(artifactSigningKid, artifactDownloadSecret());
    }

    /**
     * Current key first, then the optional previous key.  A rolling deploy can
     * therefore verify URLs issued before a secret rotation while all new URLs
     * use only the current key.
     */
    public List<ArtifactSigningKey> artifactSigningKeys() {
        var keys = new ArrayList<ArtifactSigningKey>(2);
        if (artifactSigningConfigured()) keys.add(new ArtifactSigningKey(artifactSigningKid, artifactSigningSecret));
        if (artifactSigningSecretPrevious != null && !artifactSigningSecretPrevious.isBlank()
                && !MessageDigest.isEqual(artifactSigningSecretPrevious.getBytes(StandardCharsets.UTF_8),
                artifactSigningSecret == null ? new byte[0] : artifactSigningSecret.getBytes(StandardCharsets.UTF_8))) {
            keys.add(new ArtifactSigningKey(artifactSigningKidPrevious, artifactSigningSecretPrevious));
        }
        if (keys.isEmpty()) {
            try {
                keys.add(new ArtifactSigningKey(artifactSigningKid, artifactDownloadSecret()));
            } catch (RuntimeException ignored) {
                // Readiness reports the missing key for production; memory
                // protocol tests may intentionally exercise that failure.
            }
        }
        return List.copyOf(keys);
    }

    public record ArtifactSigningKey(String kid, String secret) {
        public ArtifactSigningKey {
            if (kid == null || kid.isBlank() || secret == null || secret.isBlank()) {
                throw new IllegalArgumentException("artifact signing key is invalid");
            }
        }
    }

    private static String safeKid(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        var normalized = value.trim();
        return normalized.matches("[A-Za-z0-9._-]{1,64}") ? normalized : fallback;
    }

    private static boolean accepts(String expectedValue, String candidate) {
        if (expectedValue == null || candidate == null) {
            return false;
        }
        var expected = expectedValue.getBytes(StandardCharsets.UTF_8);
        var actual = candidate.trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }
}
