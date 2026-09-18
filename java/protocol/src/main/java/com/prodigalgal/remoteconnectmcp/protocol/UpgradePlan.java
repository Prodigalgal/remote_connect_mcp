package com.prodigalgal.remoteconnectmcp.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** One Center-offered Agent release for the polling machine. */
public record UpgradePlan(
        String campaignId,
        String version,
        String url,
        String sha256,
        @JsonProperty("attempt") Integer attempt,
        @JsonProperty("components") List<UpgradeComponentPlan> components) {

    /** Compatibility constructor for Centers that do not fence upgrade offers. */
    public UpgradePlan(String campaignId, String version, String url, String sha256) {
        this(campaignId, version, url, sha256, null, List.of());
    }

    public UpgradePlan(String campaignId, String version, String url, String sha256, Integer attempt) {
        this(campaignId, version, url, sha256, attempt, List.of());
    }

    public UpgradePlan {
        if (attempt != null && attempt < 1) throw new IllegalArgumentException("upgrade attempt must be positive");
        components = components == null ? List.of() : List.copyOf(components);
    }
}
