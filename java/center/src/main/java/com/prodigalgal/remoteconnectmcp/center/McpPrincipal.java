package com.prodigalgal.remoteconnectmcp.center;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The principal resolved from an MCP Bearer token.  It intentionally carries
 * no plaintext credential; only the token's stable database identifier and
 * bounded capabilities are kept in the request context.
 */
public record McpPrincipal(String principalId, String tokenId, String displayName,
                           Set<String> scopes, boolean shared, Instant expiresAt) {
    public McpPrincipal {
        principalId = required(principalId, "principalId", 180);
        tokenId = required(tokenId, "tokenId", 180);
        displayName = displayName == null || displayName.isBlank() ? principalId : displayName.trim();
        if (displayName.length() > 256) throw new IllegalArgumentException("displayName is too long");
        scopes = normalizeScopes(scopes);
    }

    public TaskOrigin taskOrigin(String connectionId) {
        return new TaskOrigin(principalId, tokenId, connectionId);
    }

    public boolean allows(String scope) {
        if (scope == null || scope.isBlank()) return false;
        var value = scope.trim().toLowerCase(Locale.ROOT);
        return scopes.contains("*") || scopes.contains(value);
    }

    public static McpPrincipal configured() {
        return new McpPrincipal(TaskOrigin.CONFIGURED_PRINCIPAL, TaskOrigin.CONFIGURED_TOKEN,
                "Configured MCP", Set.of("*"), true, null);
    }

    private static Set<String> normalizeScopes(Set<String> values) {
        var result = new LinkedHashSet<String>();
        if (values != null) {
            for (var value : values) {
                if (value == null || value.isBlank()) continue;
                var normalized = value.trim().toLowerCase(Locale.ROOT);
                if (normalized.length() > 64 || !normalized.matches("[a-z0-9:*._-]+")) {
                    throw new IllegalArgumentException("scope is invalid");
                }
                result.add(normalized);
            }
        }
        if (result.isEmpty()) result.add("mcp:read");
        return Set.copyOf(result);
    }

    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        var normalized = value.trim();
        if (normalized.length() > max || normalized.indexOf('\u0000') >= 0
                || normalized.indexOf('\r') >= 0 || normalized.indexOf('\n') >= 0
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }
}
