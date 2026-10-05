package com.prodigalgal.remotecontrolmcp.center;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class McpOAuthControllerTest {
    private static final String CLIENT_ID = "https://chatgpt.com/oauth/client.json";
    private static final String RESOURCE = "https://remote-control-mcp-center.example.com";

    @ParameterizedTest
    @ValueSource(strings = {
            "https://chatgpt.com/connector_platform_oauth_redirect",
            "https://chatgpt.com/connector/oauth/callback-123?opaque=one;script-src=*"
    })
    void formPolicyAllowsTheValidatedCallbackOriginWithoutAcceptingQueryDirectives(String redirectUri) {
        var response = controller().authorizePage(CLIENT_ID, redirectUri, "code",
                "mcp:read", "state", "challenge", "S256", RESOURCE);

        assertEquals(200, response.getStatusCode().value());
        var page = (String) response.getBody();
        assertTrue(page.contains("content=\"default-src 'none'; style-src 'unsafe-inline'; "
                + "form-action 'self' https://chatgpt.com; base-uri 'none'\""));
        assertTrue(page.contains("<form method=\"post\" action=\"/oauth/authorize\">"));
        assertFalse(page.contains("<script"));
    }

    @Test
    void formPolicyPreservesAnExplicitValidatedCallbackPort() {
        var response = controller().authorizePage(CLIENT_ID,
                "https://chatgpt.com:443/connector_platform_oauth_redirect", "code",
                null, "state", "challenge", "S256", RESOURCE);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(((String) response.getBody()).contains(
                "form-action 'self' https://chatgpt.com:443; base-uri 'none'"));
    }

    @Test
    void untrustedCallbackCannotReachTheConsentForm() {
        var response = controller().authorizePage(CLIENT_ID,
                "https://untrusted.example/connector_platform_oauth_redirect", "code",
                null, "state", "challenge", "S256", RESOURCE);

        assertEquals(400, response.getStatusCode().value());
        assertFalse(((String) response.getBody()).contains("<form"));
    }

    private static McpOAuthController controller() {
        var config = new CenterOAuthConfig(Map.of(
                "RCM_CENTER_OAUTH_ENABLED", "true",
                "RCM_CENTER_PUBLIC_BASE_URL", RESOURCE));
        return new McpOAuthController(config, new McpOAuthService(config, new McpPrincipalService()));
    }
}
