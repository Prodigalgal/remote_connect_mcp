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

    public UpgradePlan {
        if (attempt == null || attempt < 1) throw new IllegalArgumentException("upgrade attempt is required and must be positive");
        components = components == null ? List.of() : List.copyOf(components);
    }
}
