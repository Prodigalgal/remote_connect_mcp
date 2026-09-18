package com.prodigalgal.remoteconnectmcp.center;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class CenterTokenConfig {
    private final String enrollmentToken;
    private final String mcpToken;
    private final String adminToken;
    private final String artifactSigningSecret;
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

    private static boolean accepts(String expectedValue, String candidate) {
        if (expectedValue == null || candidate == null) {
            return false;
        }
        var expected = expectedValue.getBytes(StandardCharsets.UTF_8);
        var actual = candidate.trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }
}
