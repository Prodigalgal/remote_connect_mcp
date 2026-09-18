package com.prodigalgal.remoteconnectmcp.center;

import com.prodigalgal.remoteconnectmcp.protocol.UpgradeArtifact;
import com.prodigalgal.remoteconnectmcp.protocol.UpgradeComponentPlan;
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
        Map<String, List<UpgradeComponentPlan>> componentPlans,
        List<UpgradeTargetView> targets,
        Instant createdAt,
        Instant updatedAt,
        Instant finishedAt) {

    /** Construction overload with no component plan projection. */
    public UpgradeCampaignView(String id, String version, String status, int canaryCount, int batchSize,
                                int activeLimit, Map<String, UpgradeArtifact> artifacts,
                                List<UpgradeTargetView> targets, Instant createdAt, Instant updatedAt,
                                Instant finishedAt) {
        this(id, version, status, canaryCount, batchSize, activeLimit, artifacts, Map.of(), targets,
                createdAt, updatedAt, finishedAt);
    }

    public UpgradeCampaignView {
        artifacts = artifacts == null ? Map.of() : Map.copyOf(artifacts);
        componentPlans = componentPlans == null ? Map.of() : componentPlans.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,
                        entry -> entry.getValue() == null ? List.of() : List.copyOf(entry.getValue())));
        targets = targets == null ? List.of() : List.copyOf(targets);
    }
}
