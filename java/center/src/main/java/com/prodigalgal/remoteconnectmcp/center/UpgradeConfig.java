package com.prodigalgal.remoteconnectmcp.center;

import org.springframework.stereotype.Component;

/** Center-side release settings. Secrets are deliberately not part of this configuration. */
@Component
public final class UpgradeConfig {
    private final boolean enabled;
    private final String releaseBaseUrl;
    private final String releaseTagPrefix;

    public UpgradeConfig() {
        enabled = Boolean.parseBoolean(firstEnv("RCM_CENTER_AGENT_UPGRADES_ENABLED",
                "REMOTE_CONNECT_MCP_CENTER_AGENT_UPGRADES_ENABLED", "true"));
        releaseBaseUrl = trimTrailingSlash(firstEnv("RCM_CENTER_RELEASE_BASE_URL",
                "REMOTE_CONNECT_MCP_CENTER_RELEASE_BASE_URL",
                "https://github.com/Prodigalgal/remote_connect_mcp/releases/download"));
        releaseTagPrefix = firstEnv("RCM_CENTER_RELEASE_TAG_PREFIX",
                "REMOTE_CONNECT_MCP_CENTER_RELEASE_TAG_PREFIX", "java-");
        validateTagPrefix(releaseTagPrefix);
        if (!releaseBaseUrl.isBlank()) {
            var uri = java.net.URI.create(releaseBaseUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw new IllegalArgumentException("RCM_CENTER_RELEASE_BASE_URL must be an HTTPS URL");
            }
        }
    }

    UpgradeConfig(boolean enabled, String releaseBaseUrl) {
        this(enabled, releaseBaseUrl, "");
    }

    UpgradeConfig(boolean enabled, String releaseBaseUrl, String releaseTagPrefix) {
        this.enabled = enabled;
        this.releaseBaseUrl = trimTrailingSlash(releaseBaseUrl == null ? "" : releaseBaseUrl.trim());
        this.releaseTagPrefix = releaseTagPrefix == null ? "" : releaseTagPrefix.trim();
        validateTagPrefix(this.releaseTagPrefix);
    }

    public boolean enabled() {
        return enabled;
    }

    public String releaseBaseUrl() {
        return releaseBaseUrl;
    }

    /**
     * Prefix used only for the release URL path.  Java releases use tags such
     * as {@code java-v0.2.0}, while the public campaign version remains
     * {@code v0.2.0} so the Agent asset name stays stable.
     */
    public String releaseTagPrefix() {
        return releaseTagPrefix;
    }

    private static String env(String key, String fallback) {
        var value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String firstEnv(String primary, String legacy, String fallback) {
        var value = env(primary, "");
        return value.isBlank() ? env(legacy, fallback) : value;
    }

    private static String trimTrailingSlash(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }

    private static void validateTagPrefix(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{0,32}")) {
            throw new IllegalArgumentException("RCM_CENTER_RELEASE_TAG_PREFIX contains unsupported characters");
        }
    }
}
