package com.prodigalgal.remoteconnectmcp.center;

import org.springframework.stereotype.Component;

/** Center-side release settings. Secrets are deliberately not part of this configuration. */
@Component
public final class UpgradeConfig {
    private final boolean enabled;
    private final String releaseBaseUrl;
    private final String releaseTagPrefix;
    private final String releasesApiUrl;
    private final boolean automatic;
    private final boolean includePrerelease;

    public UpgradeConfig() {
        enabled = Boolean.parseBoolean(env("RCM_CENTER_AGENT_UPGRADES_ENABLED", "true"));
        releaseBaseUrl = trimTrailingSlash(env("RCM_CENTER_RELEASE_BASE_URL",
                "https://github.com/Prodigalgal/remote_connect_mcp/releases/download"));
        releaseTagPrefix = env("RCM_CENTER_RELEASE_TAG_PREFIX", "java-");
        validateTagPrefix(releaseTagPrefix);
        validateHttps(releaseBaseUrl, "RCM_CENTER_RELEASE_BASE_URL");
        releasesApiUrl = trimTrailingSlash(env("RCM_CENTER_RELEASES_API_URL", defaultReleasesApi(releaseBaseUrl)));
        validateHttps(releasesApiUrl, "RCM_CENTER_RELEASES_API_URL");
        automatic = Boolean.parseBoolean(env("RCM_CENTER_AGENT_AUTO_UPGRADE_ENABLED", "false"));
        includePrerelease = Boolean.parseBoolean(env("RCM_CENTER_AGENT_AUTO_UPGRADE_INCLUDE_PRERELEASE", "false"));
    }

    UpgradeConfig(boolean enabled, String releaseBaseUrl) {
        this(enabled, releaseBaseUrl, "", defaultReleasesApi(releaseBaseUrl));
    }

    UpgradeConfig(boolean enabled, String releaseBaseUrl, String releaseTagPrefix) {
        this(enabled, releaseBaseUrl, releaseTagPrefix, defaultReleasesApi(releaseBaseUrl));
    }

    UpgradeConfig(boolean enabled, String releaseBaseUrl, String releaseTagPrefix, String releasesApiUrl) {
        this.enabled = enabled;
        this.releaseBaseUrl = trimTrailingSlash(releaseBaseUrl == null ? "" : releaseBaseUrl.trim());
        this.releaseTagPrefix = releaseTagPrefix == null ? "" : releaseTagPrefix.trim();
        this.releasesApiUrl = trimTrailingSlash(releasesApiUrl == null ? "" : releasesApiUrl.trim());
        this.automatic = false;
        this.includePrerelease = false;
        validateTagPrefix(this.releaseTagPrefix);
        validateHttps(this.releaseBaseUrl, "RCM_CENTER_RELEASE_BASE_URL");
        validateHttps(this.releasesApiUrl, "RCM_CENTER_RELEASES_API_URL");
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

    /** GitHub Releases API endpoint used by the admin version catalog. */
    public String releasesApiUrl() {
        return releasesApiUrl;
    }

    public boolean automatic() {
        return automatic;
    }

    public boolean includePrerelease() {
        return includePrerelease;
    }

    private static String env(String key, String fallback) {
        var value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String trimTrailingSlash(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }

    private static void validateTagPrefix(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{0,32}")) {
            throw new IllegalArgumentException("RCM_CENTER_RELEASE_TAG_PREFIX contains unsupported characters");
        }
    }

    private static void validateHttps(String value, String setting) {
        if (value == null || value.isBlank()) return;
        var uri = java.net.URI.create(value);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException(setting + " must be an HTTPS URL without credentials or fragments");
        }
    }

    private static String defaultReleasesApi(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return "";
        try {
            var uri = java.net.URI.create(baseUrl.trim());
            if (!"github.com".equalsIgnoreCase(uri.getHost())) return "";
            var path = uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", "");
            var marker = "/releases/download";
            var index = path.indexOf(marker);
            if (index <= 0) return "";
            var repository = path.substring(0, index);
            var parts = repository.split("/");
            if (parts.length != 3 || parts[1].isBlank() || parts[2].isBlank()) return "";
            return "https://api.github.com/repos/" + parts[1] + "/" + parts[2] + "/releases";
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }
}
