package com.prodigalgal.remoteconnectmcp.center;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CenterOAuthConfigTest {
    @Test
    void acceptsChatGptCimdAndStableRedirectOnlyWhenHttps() {
        var config = new CenterOAuthConfig(Map.of(
                "RCM_CENTER_OAUTH_ENABLED", "true",
                "RCM_CENTER_PUBLIC_BASE_URL", "https://remote-connect-mcp-center.example.com"));

        assertTrue(config.isConfigured());
        assertTrue(config.validClientId("https://chatgpt.com/oauth/client.json"));
        assertTrue(config.validRedirectUri("https://chatgpt.com/connector_platform_oauth_redirect"));
        assertTrue(config.validRedirectUri("https://chatgpt.com/connector/oauth/callback-123"));
        assertFalse(config.validClientId("http://chatgpt.com/oauth/client.json"));
        assertFalse(config.validRedirectUri("https://evil.example/redirect"));
    }

    @Test
    void disabledOrNonHttpsIssuerIsNotAdvertised() {
        var disabled = new CenterOAuthConfig(Map.of(
                "RCM_CENTER_PUBLIC_BASE_URL", "https://remote-connect-mcp-center.example.com"));
        var nonHttps = new CenterOAuthConfig(Map.of(
                "RCM_CENTER_OAUTH_ENABLED", "true",
                "RCM_CENTER_OAUTH_ISSUER", "http://auth.example.com",
                "RCM_CENTER_OAUTH_RESOURCE", "https://resource.example.com"));

        assertFalse(disabled.isConfigured());
        assertFalse(nonHttps.isConfigured());
    }
}
