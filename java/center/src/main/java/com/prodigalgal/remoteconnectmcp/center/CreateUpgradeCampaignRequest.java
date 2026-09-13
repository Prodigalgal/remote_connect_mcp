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
        Map<String, UpgradeArtifact> artifacts) {

    public CreateUpgradeCampaignRequest {
        machineIds = machineIds == null ? List.of() : Collections.unmodifiableList(new java.util.ArrayList<>(machineIds));
        artifacts = artifacts == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(artifacts));
    }
}
