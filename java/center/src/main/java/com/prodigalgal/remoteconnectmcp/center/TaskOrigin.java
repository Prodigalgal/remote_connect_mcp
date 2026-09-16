package com.prodigalgal.remoteconnectmcp.center;

/**
 * Immutable owner metadata attached to a task at creation time.
 *
 * <p>The connection identifier is correlation metadata only; the principal
 * identifier is the authorization boundary.  Agent credentials never enter
 * this record and are never exposed to MCP callers.</p>
 */
public record TaskOrigin(String principalId, String tokenId, String connectionId) {
    public static final String SHARED_PRINCIPAL = "owner/shared-domain";
    public static final String COMPAT_TOKEN = "compat-mcp";

    public TaskOrigin {
        principalId = normalize(principalId, SHARED_PRINCIPAL, 180);
        tokenId = normalize(tokenId, COMPAT_TOKEN, 180);
        connectionId = normalize(connectionId, "internal", 256);
    }

    public static TaskOrigin shared() {
        return new TaskOrigin(SHARED_PRINCIPAL, COMPAT_TOKEN, "internal");
    }

    public boolean isShared() {
        return SHARED_PRINCIPAL.equals(principalId);
    }

    private static String normalize(String value, String fallback, int max) {
        if (value == null || value.isBlank()) return fallback;
        var normalized = value.trim();
        if (normalized.length() > max || normalized.indexOf('\u0000') >= 0
                || normalized.indexOf('\r') >= 0 || normalized.indexOf('\n') >= 0
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("task origin contains invalid text");
        }
        return normalized;
    }
}
