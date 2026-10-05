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
        var principals = new McpPrincipalService();
        var bootstrap = principals.issue(new McpPrincipalService.IssueRequest("owner", "owner", 3600L,
                Set.of("mcp:read", "mcp:execute"), Map.of("machine-a", Set.of("command", "task_read"))));
        var service = new McpOAuthService(config, principals);
        var verifier = "correct-horse-battery-staple-verifier";
        var challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(verifier));

        var authorizationCode = service.issueAuthorizationCode(CLIENT_ID, REDIRECT, challenge, "S256",
                "mcp:read offline_access", RESOURCE, bootstrap.token());
        assertTrue(authorizationCode.code().startsWith("rcm_code_"));
        assertTrue(!authorizationCode.code().equals(bootstrap.token()));
        assertEquals(bootstrap.tokenId(), authorizationCode.sourceTokenId());

        var response = service.exchangeAuthorizationCode(CLIENT_ID, REDIRECT, authorizationCode.code(), verifier, RESOURCE);
        assertEquals("Bearer", response.tokenType());
        assertTrue(response.accessToken().startsWith("rcm_at_"));
        assertEquals(bootstrap.tokenId(), service.resolve(response.accessToken()).orElseThrow().tokenId());

        assertThrows(McpOAuthService.OAuthException.class,
                () -> service.exchangeAuthorizationCode(CLIENT_ID, REDIRECT, authorizationCode.code(), verifier, RESOURCE));
        var refreshed = service.refresh(CLIENT_ID, response.refreshToken(), RESOURCE);
        assertTrue(refreshed.accessToken().startsWith("rcm_at_"));
        assertThrows(McpOAuthService.OAuthException.class,
                () -> service.refresh(CLIENT_ID, response.refreshToken(), RESOURCE));

        var pendingCode = service.issueAuthorizationCode(CLIENT_ID, REDIRECT, challenge, "S256",
                "mcp:read", RESOURCE, bootstrap.token());
        assertTrue(principals.revoke(bootstrap.tokenId()));
        assertTrue(service.resolve(refreshed.accessToken()).isEmpty());
        assertThrows(McpOAuthService.OAuthException.class,
                () -> service.refresh(CLIENT_ID, refreshed.refreshToken(), RESOURCE));
        assertThrows(McpOAuthService.OAuthException.class,
                () -> service.exchangeAuthorizationCode(CLIENT_ID, REDIRECT, pendingCode.code(), verifier, RESOURCE));
    }

    @Test
    void rejectsUnsupportedScopeBeforePersistingCode() {
        var config = new CenterOAuthConfig(Map.of(
                "RCM_CENTER_OAUTH_ENABLED", "true",
                "RCM_CENTER_OAUTH_ISSUER", RESOURCE,
                "RCM_CENTER_OAUTH_RESOURCE", RESOURCE));
        var principals = new McpPrincipalService();
        var bootstrap = principals.issue(new McpPrincipalService.IssueRequest("owner", "owner", 3600L,
                Set.of("mcp:read", "mcp:execute"), Map.of("machine-a", Set.of("command"))));
        var service = new McpOAuthService(config, principals);
        assertThrows(McpOAuthService.OAuthException.class, () -> service.issueAuthorizationCode(
                CLIENT_ID, REDIRECT, "challenge", "S256", "mcp:admin", RESOURCE, bootstrap.token()));
    }

    @Test
    void grantsOnlyRequestedScopesAllowedByTheBootstrapPrincipal() {
        var config = new CenterOAuthConfig(Map.of(
                "RCM_CENTER_OAUTH_ENABLED", "true",
                "RCM_CENTER_OAUTH_ISSUER", RESOURCE,
                "RCM_CENTER_OAUTH_RESOURCE", RESOURCE,
                "RCM_CENTER_OAUTH_SCOPES", "mcp:read mcp:execute mcp:project"));
        var principals = new McpPrincipalService();
        var issued = principals.issue(new McpPrincipalService.IssueRequest(
                "owner", "owner", 3600L, Set.of("mcp:read", "mcp:execute"),
                Map.of("machine-a", Set.of("command", "task_read"))));
        var service = new McpOAuthService(config, principals);

        var authorizationCode = service.issueAuthorizationCode(CLIENT_ID, REDIRECT, "challenge", "S256",
                null, RESOURCE, issued.token());

        assertEquals(Set.of("mcp:read", "mcp:execute"), authorizationCode.scopes());
        var partialGrant = service.issueAuthorizationCode(CLIENT_ID, REDIRECT, "challenge", "S256",
                "mcp:read mcp:execute mcp:project offline_access", RESOURCE, issued.token());
        assertEquals(Set.of("mcp:read", "mcp:execute", "offline_access"), partialGrant.scopes());
        assertThrows(McpOAuthService.OAuthException.class, () -> service.issueAuthorizationCode(
                CLIENT_ID, REDIRECT, "challenge", "S256", "mcp:project", RESOURCE, issued.token()));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
