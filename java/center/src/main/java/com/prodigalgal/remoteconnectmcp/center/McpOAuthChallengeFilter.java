package com.prodigalgal.remoteconnectmcp.center;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Adds the RFC 9728 discovery challenge to unauthenticated MCP requests.
 * The SDK transport remains responsible for producing the 401; this filter
 * only makes the response actionable for ChatGPT and other MCP hosts.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class McpOAuthChallengeFilter extends OncePerRequestFilter {
    private final CenterOAuthConfig config;
    private final McpAuthenticationService authentication;

    public McpOAuthChallengeFilter(CenterOAuthConfig config, McpAuthenticationService authentication) {
        this.config = config;
        this.authentication = authentication;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        var uri = request.getRequestURI();
        if (config.isConfigured() && uri != null && uri.endsWith("/mcp")
                && authentication.resolve(bearerValue(request.getHeader("Authorization"))).isEmpty()) {
            response.setHeader("WWW-Authenticate", "Bearer resource_metadata=\""
                    + config.protectedResourceMetadataUrl() + "\"");
            response.setHeader("Cache-Control", "no-store");
        }
        filterChain.doFilter(request, response);
    }

    private static String bearerValue(String authorization) {
        if (authorization == null) return "";
        var parts = authorization.trim().split("\\s+", 2);
        return parts.length == 2 && "Bearer".equalsIgnoreCase(parts[0]) ? parts[1].trim() : "";
    }
}
