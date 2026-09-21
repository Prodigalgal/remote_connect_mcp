package com.prodigalgal.remoteconnectmcp.center;

import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Resolves every MCP bearer credential into the same principal abstraction.
 *
 * <p>The transport deliberately has one authentication path even though two
 * client-facing bootstrap modes exist: Codex/CLI clients use a configured or
 * issued opaque RCM token, while ChatGPT uses an OAuth access token issued by
 * the Center.  Keeping this lookup in one service prevents tool handlers from
 * learning which credential family was used.</p>
 */
@Service
public final class McpAuthenticationService {
    private final McpPrincipalService principals;
    private final McpOAuthService oauth;

    public McpAuthenticationService(McpPrincipalService principals, McpOAuthService oauth) {
        this.principals = principals;
        this.oauth = oauth;
    }

    public Optional<McpPrincipal> resolve(String candidate) {
        return principals.resolve(candidate).or(() -> oauth.resolve(candidate));
    }
}
