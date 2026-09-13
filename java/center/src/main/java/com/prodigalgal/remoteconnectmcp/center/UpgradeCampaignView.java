package com.prodigalgal.remoteconnectmcp.center;

import com.prodigalgal.remoteconnectmcp.protocol.UpgradeArtifact;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Bounded release campaign projection used by the admin API and React console. */
public record UpgradeCampaignView(
        String id,
        String version,
        String status,
        int canaryCount,
        int batchSize,
        int activeLimit,
        Map<String, UpgradeArtifact> artifacts,
        List<UpgradeTargetView> targets,
        Instant createdAt,
        Instant updatedAt,
        Instant finishedAt) {

    public UpgradeCampaignView {
        artifacts = artifacts == null ? Map.of() : Map.copyOf(artifacts);
        targets = targets == null ? List.of() : List.copyOf(targets);
    }
}
