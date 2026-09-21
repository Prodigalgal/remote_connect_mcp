package com.prodigalgal.remoteconnectmcp.center;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * ChatGPT MCP OAuth bridge.
 *
 * <p>The Center does not require a second user directory.  The authorization
 * page accepts an existing RCM token, validates it locally, and exchanges it
 * for an opaque, short-lived OAuth access token.  ChatGPT only ever receives
 * the OAuth token; Codex and other direct clients can continue to use the
 * original RCM Bearer token against {@code /mcp}.</p>
 */
@RestController
public final class McpOAuthController {
    private final CenterOAuthConfig config;
    private final McpOAuthService oauth;

    public McpOAuthController(CenterOAuthConfig config, McpOAuthService oauth) {
        this.config = config;
        this.oauth = oauth;
    }

    @GetMapping(value = "/.well-known/oauth-protected-resource", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> protectedResourceMetadata() {
        if (!oauth.isEnabledAndConfigured()) return ResponseEntity.notFound().build();
        var body = new LinkedHashMap<String, Object>();
        body.put("resource", config.resource());
        body.put("authorization_servers", List.of(config.issuer()));
        body.put("scopes_supported", metadataScopes());
        return json(body);
    }

    @GetMapping(value = "/.well-known/oauth-authorization-server", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> authorizationServerMetadata() {
        if (!oauth.isEnabledAndConfigured()) return ResponseEntity.notFound().build();
        var body = new LinkedHashMap<String, Object>();
        body.put("issuer", config.issuer());
        body.put("authorization_response_iss_parameter_supported", true);
        body.put("authorization_endpoint", config.authorizationEndpoint());
        body.put("token_endpoint", config.tokenEndpoint());
        body.put("client_id_metadata_document_supported", true);
        body.put("token_endpoint_auth_methods_supported", List.of("none"));
        body.put("code_challenge_methods_supported", List.of("S256"));
        body.put("scopes_supported", metadataScopes());
        return json(body);
    }

    /**
     * The browser-facing consent page.  It intentionally has no script and
     * no external resources so a token cannot be sent to a third party.
     */
    @GetMapping(value = "/oauth/authorize", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<?> authorizePage(
            @RequestParam(name = "client_id", required = false) String client_id,
            @RequestParam(name = "redirect_uri", required = false) String redirect_uri,
            @RequestParam(name = "response_type", required = false) String response_type,
            @RequestParam(name = "scope", required = false) String scope,
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "code_challenge", required = false) String code_challenge,
            @RequestParam(name = "code_challenge_method", required = false) String code_challenge_method,
            @RequestParam(name = "resource", required = false) String resource) {
        if (!oauth.isEnabledAndConfigured()) return html(HttpStatus.NOT_FOUND,
                errorPage("OAuth 未启用", "当前 Center 尚未启用 OAuth 连接。"));
        var validation = validateAuthorizeRequest(client_id, redirect_uri, response_type,
                code_challenge, code_challenge_method, resource, state);
        if (validation != null) return validation;
        return html(HttpStatus.OK, form(client_id, redirect_uri, response_type, scope, state,
                code_challenge, code_challenge_method, resource));
    }

    @PostMapping(value = "/oauth/authorize", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<?> authorize(
            @RequestParam(name = "client_id", required = false) String client_id,
            @RequestParam(name = "redirect_uri", required = false) String redirect_uri,
            @RequestParam(name = "response_type", required = false) String response_type,
            @RequestParam(name = "scope", required = false) String scope,
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "code_challenge", required = false) String code_challenge,
            @RequestParam(name = "code_challenge_method", required = false) String code_challenge_method,
            @RequestParam(name = "resource", required = false) String resource,
            @RequestParam(name = "rcm_token", required = false) String rcm_token) {
        if (!oauth.isEnabledAndConfigured()) return errorJson("temporarily_unavailable", "OAuth 未启用", HttpStatus.SERVICE_UNAVAILABLE);
        var validation = validateAuthorizeRequest(client_id, redirect_uri, response_type,
                code_challenge, code_challenge_method, resource, state);
        if (validation != null) return validation;
        if (rcm_token == null || rcm_token.isBlank()) {
            return html(HttpStatus.BAD_REQUEST, errorPage("需要 RCM Token", "请输入当前成员使用的 RCM Token。"));
        }
        try {
            var issued = oauth.issueAuthorizationCode(client_id, redirect_uri, code_challenge,
                    code_challenge_method, scope, resource, rcm_token);
            var callbackBuilder = UriComponentsBuilder.fromUriString(redirect_uri)
                    .queryParam("code", issued.code())
                    .queryParam("iss", config.issuer());
            if (state != null && !state.isBlank()) callbackBuilder.queryParam("state", state);
            var callback = callbackBuilder.build().encode().toUriString();
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, callback)
                    .cacheControl(CacheControl.noStore())
                    .build();
        } catch (McpOAuthService.OAuthException exception) {
            return redirectOrError(redirect_uri, state, exception.error(), safeMessage(exception));
        } catch (SecurityException exception) {
            return redirectOrError(redirect_uri, state, "access_denied", "RCM Token 无效或已过期");
        } catch (RuntimeException exception) {
            return redirectOrError(redirect_uri, state, "server_error", "授权服务暂时不可用");
        }
    }

    @PostMapping(value = "/oauth/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> token(
            @RequestParam(name = "grant_type", required = false) String grant_type,
            @RequestParam(name = "client_id", required = false) String client_id,
            @RequestParam(name = "redirect_uri", required = false) String redirect_uri,
            @RequestParam(name = "code", required = false) String code,
            @RequestParam(name = "code_verifier", required = false) String code_verifier,
            @RequestParam(name = "refresh_token", required = false) String refresh_token,
            @RequestParam(name = "resource", required = false) String resource) {
        if (!oauth.isEnabledAndConfigured()) return errorJson("temporarily_unavailable", "OAuth 未启用", HttpStatus.SERVICE_UNAVAILABLE);
        try {
            McpOAuthService.TokenResponse response;
            if ("authorization_code".equals(grant_type)) {
                response = oauth.exchangeAuthorizationCode(client_id, redirect_uri, code, code_verifier, resource);
            } else if ("refresh_token".equals(grant_type)) {
                response = oauth.refresh(client_id, refresh_token, resource);
            } else {
                return errorJson("unsupported_grant_type", "仅支持 authorization_code 或 refresh_token", HttpStatus.BAD_REQUEST);
            }
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
        } catch (McpOAuthService.OAuthException exception) {
            return errorJson(exception.error(), safeMessage(exception), HttpStatus.BAD_REQUEST);
        } catch (RuntimeException exception) {
            return errorJson("invalid_grant", "授权请求无效", HttpStatus.BAD_REQUEST);
        }
    }

    private ResponseEntity<?> validateAuthorizeRequest(String clientId, String redirectUri,
                                                              String responseType, String codeChallenge,
                                                              String codeChallengeMethod, String resource, String state) {
        if (!config.validClientId(clientId) || !config.validRedirectUri(redirectUri)) {
            return html(HttpStatus.BAD_REQUEST, errorPage("OAuth 请求无效", "ChatGPT client_id 或 redirect_uri 不在允许范围内。"));
        }
        if (!"code".equals(responseType) || codeChallenge == null || codeChallenge.isBlank()
                || !"S256".equalsIgnoreCase(codeChallengeMethod) || !config.validResource(resource)) {
            return redirectOrError(redirectUri, state, "invalid_request", "response_type、PKCE 或 resource 参数无效");
        }
        return null;
    }

    private ResponseEntity<?> redirectOrError(String redirectUri, String state, String error, String description) {
        if (config.validRedirectUri(redirectUri)) {
            var builder = UriComponentsBuilder.fromUriString(redirectUri)
                    .queryParam("error", error)
                    .queryParam("error_description", description)
                    .queryParam("iss", config.issuer());
            if (state != null && !state.isBlank()) builder.queryParam("state", state);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, builder.build().encode().toUriString())
                    .cacheControl(CacheControl.noStore()).build();
        }
        return errorJson(error, description, HttpStatus.BAD_REQUEST);
    }

    private ResponseEntity<?> errorJson(String error, String description, HttpStatus status) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", error, "error_description", description));
    }

    private ResponseEntity<String> json(Map<String, Object> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON).body(toJson(body));
    }

    private ResponseEntity<String> html(HttpStatus status, String body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .contentType(MediaType.TEXT_HTML).body(body);
    }

    private String form(String clientId, String redirectUri, String responseType, String scope,
                       String state, String codeChallenge, String codeChallengeMethod, String resource) {
        var fields = new LinkedHashMap<String, String>();
        fields.put("client_id", clientId);
        fields.put("redirect_uri", redirectUri);
        fields.put("response_type", responseType);
        fields.put("scope", scope == null ? "" : scope);
        fields.put("state", state == null ? "" : state);
        fields.put("code_challenge", codeChallenge);
        fields.put("code_challenge_method", codeChallengeMethod);
        fields.put("resource", resource == null ? "" : resource);
        var hidden = new StringBuilder();
        fields.forEach((key, value) -> hidden.append("<input type=\"hidden\" name=\"")
                .append(htmlEscape(key)).append("\" value=\"")
                .append(htmlEscape(value)).append("\">");
        return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>连接 Remote Connect MCP</title>"
                + "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'\">"
                + "<style>body{font-family:system-ui,sans-serif;max-width:560px;margin:12vh auto;padding:0 24px;color:#172033}main{border:1px solid #d8dee9;border-radius:16px;padding:28px;box-shadow:0 8px 30px #17203318}label{display:block;font-weight:600;margin:18px 0 8px}input{box-sizing:border-box;width:100%;padding:12px;border:1px solid #aeb8c8;border-radius:8px;font-size:16px}button{margin-top:22px;width:100%;padding:12px;border:0;border-radius:8px;background:#1463d8;color:#fff;font-size:16px;cursor:pointer}.muted{color:#5b6575;font-size:14px}</style></head><body><main><h1>连接 Remote Connect MCP</h1><p>ChatGPT 正在请求访问你的 Remote Connect 终端。请输入已分配给你的 RCM Token；Token 只用于本次授权，不会发送给 ChatGPT。</p><form method=\"post\" action=\"/oauth/authorize\">"
                + hidden + "<label for=\"rcm_token\">RCM Token</label><input id=\"rcm_token\" name=\"rcm_token\" type=\"password\" autocomplete=\"off\" required maxlength=1024><p class=\"muted\">授权后 ChatGPT 将使用短期 OAuth 令牌调用 MCP。你可以在 Center 中撤销该令牌。</p><button type=\"submit\">授权并返回 ChatGPT</button></form></main></body></html>";
    }

    private static String errorPage(String title, String message) {
        return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>"
                + htmlEscape(title) + "</title></head><body style=\"font-family:system-ui,sans-serif;max-width:560px;margin:12vh auto;padding:0 24px\"><h1>"
                + htmlEscape(title) + "</h1><p>" + htmlEscape(message) + "</p></body></html>";
    }

    private List<String> metadataScopes() {
        var scopes = new ArrayList<>(config.scopes());
        if (!scopes.contains("offline_access")) scopes.add("offline_access");
        return List.copyOf(scopes);
    }

    private static String safeMessage(McpOAuthService.OAuthException exception) {
        return exception.getMessage() == null ? "OAuth request is invalid" : exception.getMessage();
    }

    private static String htmlEscape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String toJson(Map<String, Object> value) {
        var result = new StringBuilder("{");
        var first = true;
        for (var entry : value.entrySet()) {
            if (!first) result.append(',');
            first = false;
            result.append('"').append(jsonEscape(entry.getKey())).append("\":");
            result.append(jsonValue(entry.getValue()));
        }
        return result.append('}').toString();
    }

    private static String jsonValue(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean || value instanceof Number) return String.valueOf(value);
        if (value instanceof List<?> list) {
            return list.stream().map(item -> jsonValue(item)).collect(java.util.stream.Collectors.joining(",", "[", "]"));
        }
        return "\"" + jsonEscape(String.valueOf(value)) + "\"";
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }
}
