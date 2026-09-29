package com.prodigalgal.remoteconnectmcp.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One independently staged component in a machine upgrade campaign.
 *
 * <p>Component plans let Center stage command, Desktop, and Browser bundles
 * without changing the MCP/Agent identity.</p>
 */
public record UpgradeComponentPlan(
        String component,
        String version,
        String os,
        String arch,
        String url,
        String sha256,
        @JsonProperty("bytes") Long bytes,
        @JsonProperty("restart_policy") String restartPolicy,
        @JsonProperty("min_agent_version") String minAgentVersion) {

    public UpgradeComponentPlan {
        component = component == null ? "" : component.trim();
        version = version == null ? "" : version.trim();
        os = os == null ? "" : os.trim().toLowerCase(java.util.Locale.ROOT);
        arch = arch == null ? "" : arch.trim().toLowerCase(java.util.Locale.ROOT);
        url = url == null ? "" : url.trim();
        sha256 = sha256 == null ? "" : sha256.trim().toLowerCase(java.util.Locale.ROOT);
        restartPolicy = restartPolicy == null || restartPolicy.isBlank() ? "drain-and-restart" : restartPolicy.trim();
        minAgentVersion = minAgentVersion == null ? "" : minAgentVersion.trim();
        if (!component.matches("[a-z][a-z0-9-]{1,63}")) throw new IllegalArgumentException("upgrade component is invalid");
        if (version.length() > 128 || os.length() > 32 || arch.length() > 32 || url.length() > 4096
                || sha256.length() > 128 || restartPolicy.length() > 64 || minAgentVersion.length() > 128) {
            throw new IllegalArgumentException("upgrade component plan is too large");
        }
        if (!minAgentVersion.isEmpty() && !minAgentVersion.matches("v[0-9A-Za-z][0-9A-Za-z._+-]{0,127}")) {
            throw new IllegalArgumentException("minimum Agent version is invalid");
        }
        if (bytes != null && bytes < 0) throw new IllegalArgumentException("upgrade component bytes must be non-negative");
    }

    /** Source-compatible constructor for plans without a version gate. */
    public UpgradeComponentPlan(String component, String version, String os, String arch, String url,
                               String sha256, Long bytes, String restartPolicy) {
        this(component, version, os, arch, url, sha256, bytes, restartPolicy, "");
    }
}
