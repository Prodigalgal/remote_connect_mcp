package com.prodigalgal.remoteconnectmcp.center;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class McpOAuthServiceTest {
    private static final String CLIENT_ID = "https://chatgpt.com/oauth/client.json";
    private static final String REDIRECT = "https://chatgpt.com/connector_platform_oauth_redirect";
    private static final String RESOURCE = "https://remote-connect-mcp-center.example.com";

    @Test
    void exchangesPkceCodeAndRotatesRefreshTokenWithoutReturningBootstrapToken() {
        var config = new CenterOAuthConfig(Map.of(
                "RCM_CENTER_OAUTH_ENABLED", "true",
                "RCM_CENTER_OAUTH_ISSUER", RESOURCE,
                "RCM_CENTER_OAUTH_RESOURCE", RESOURCE,
                "RCM_CENTER_OAUTH_ACCESS_TOKEN_TTL_SECONDS", "900",
                "RCM_CENTER_OAUTH_REFRESH_TOKEN_TTL_SECONDS", "3600"));
        var tokenConfig = new CenterTokenConfig() {
            @Override
            public boolean acceptsMcp(String candidate) {
                return "bootstrap-token".equals(candidate);
            }
        };
        var principals = new McpPrincipalService(tokenConfig);
        var service = new McpOAuthService(config, principals);
        var verifier = "correct-horse-battery-staple-verifier";
        var challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(verifier));

        var authorizationCode = service.issueAuthorizationCode(CLIENT_ID, REDIRECT, challenge, "S256",
                "mcp:read offline_access", RESOURCE, "bootstrap-token");
        assertTrue(authorizationCode.code().startsWith("rcm_code_"));
        assertTrue(!authorizationCode.code().equals("bootstrap-token"));

        var response = service.exchangeAuthorizationCode(CLIENT_ID, REDIRECT, authorizationCode.code(), verifier, RESOURCE);
        assertEquals("Bearer", response.tokenType());
        assertTrue(response.accessToken().startsWith("rcm_at_"));
        assertTrue(service.resolve(response.accessToken()).isPresent());

        assertThrows(McpOAuthService.OAuthException.class,
                () -> service.exchangeAuthorizationCode(CLIENT_ID, REDIRECT, authorizationCode.code(), verifier, RESOURCE));
        var refreshed = service.refresh(CLIENT_ID, response.refreshToken(), RESOURCE);
        assertTrue(refreshed.accessToken().startsWith("rcm_at_"));
        assertThrows(McpOAuthService.OAuthException.class,
                () -> service.refresh(CLIENT_ID, response.refreshToken(), RESOURCE));
    }

    @Test
    void rejectsUnsupportedScopeBeforePersistingCode() {
        var config = new CenterOAuthConfig(Map.of(
                "RCM_CENTER_OAUTH_ENABLED", "true",
                "RCM_CENTER_OAUTH_ISSUER", RESOURCE,
                "RCM_CENTER_OAUTH_RESOURCE", RESOURCE));
        var tokenConfig = new CenterTokenConfig() {
            @Override
            public boolean acceptsMcp(String candidate) {
                return "bootstrap-token".equals(candidate);
            }
        };
        var service = new McpOAuthService(config, new McpPrincipalService(tokenConfig));
        assertThrows(McpOAuthService.OAuthException.class, () -> service.issueAuthorizationCode(
                CLIENT_ID, REDIRECT, "challenge", "S256", "mcp:admin", RESOURCE, "bootstrap-token"));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
