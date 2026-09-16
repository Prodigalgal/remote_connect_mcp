package com.prodigalgal.remoteconnectmcp.center;

import java.time.Instant;
import java.util.Set;

/** Non-secret MCP token projection used by the console. */
public record McpTokenView(String tokenId, String principalId, String displayName,
                           Set<String> scopes, Instant expiresAt, Instant revokedAt,
                           Instant createdAt) {
}
