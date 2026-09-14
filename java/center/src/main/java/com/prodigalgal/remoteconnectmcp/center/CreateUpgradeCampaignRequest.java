package com.prodigalgal.remoteconnectmcp.center;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.prodigalgal.remoteconnectmcp.protocol.UpgradeArtifact;
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
        @JsonProperty("include_offline") Boolean includeOffline) {

    /** Compatibility constructor for the original five-field request. */
    public CreateUpgradeCampaignRequest(String version, Integer canaryCount, Integer batchSize,
                                        List<String> machineIds, Map<String, UpgradeArtifact> artifacts) {
        this(version, canaryCount, batchSize, machineIds, artifacts, null);
    }

    public CreateUpgradeCampaignRequest {
        machineIds = machineIds == null ? List.of() : Collections.unmodifiableList(new java.util.ArrayList<>(machineIds));
        artifacts = artifacts == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(artifacts));
        // A campaign created without an explicit machine list is a fleet
        // campaign.  Keep offline registrations in the target set so an
        // Agent that reconnects later receives the same signed release offer;
        // callers that need the old online-only behavior can opt out.
        includeOffline = includeOffline == null || includeOffline;
    }
}
