package com.prodigalgal.remoteconnectmcp.center;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.prodigalgal.remoteconnectmcp.protocol.UpgradeArtifact;
import com.prodigalgal.remoteconnectmcp.protocol.UpgradeComponentPlan;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;

/** Request to publish one verified Agent release to selected machines. */
public record CreateUpgradeCampaignRequest(
        String version,
        @JsonProperty("canary_count") Integer canaryCount,
        @JsonProperty("batch_size") Integer batchSize,
        @JsonProperty("machine_ids") List<String> machineIds,
        Map<String, UpgradeArtifact> artifacts,
        @JsonProperty("include_offline") Boolean includeOffline,
        @JsonProperty("component_plans") Map<String, List<UpgradeComponentPlan>> componentPlans) {

    /** Compatibility constructor for the original five-field request. */
    public CreateUpgradeCampaignRequest(String version, Integer canaryCount, Integer batchSize,
                                        List<String> machineIds, Map<String, UpgradeArtifact> artifacts) {
        this(version, canaryCount, batchSize, machineIds, artifacts, null, null);
    }

    /** Compatibility constructor for callers that already supplied includeOffline. */
    public CreateUpgradeCampaignRequest(String version, Integer canaryCount, Integer batchSize,
                                        List<String> machineIds, Map<String, UpgradeArtifact> artifacts,
                                        Boolean includeOffline) {
        this(version, canaryCount, batchSize, machineIds, artifacts, includeOffline, null);
    }

    public CreateUpgradeCampaignRequest {
        machineIds = machineIds == null ? List.of() : Collections.unmodifiableList(new java.util.ArrayList<>(machineIds));
        artifacts = artifacts == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(artifacts));
        if (componentPlans == null) {
            componentPlans = Map.of();
        } else {
            var normalized = new LinkedHashMap<String, List<UpgradeComponentPlan>>();
            componentPlans.forEach((key, value) -> normalized.put(key == null ? "" : key.trim().toLowerCase(java.util.Locale.ROOT),
                    value == null ? List.of() : List.copyOf(value)));
            componentPlans = Collections.unmodifiableMap(normalized);
        }
        // A campaign created without an explicit machine list is a fleet
        // campaign.  Keep offline registrations in the target set so an
        // Agent that reconnects later receives the same signed release offer;
        // callers that need the old online-only behavior can opt out.
        includeOffline = includeOffline == null || includeOffline;
    }
}
