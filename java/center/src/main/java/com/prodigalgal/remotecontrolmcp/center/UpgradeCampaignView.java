package com.prodigalgal.remotecontrolmcp.center;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.prodigalgal.remotecontrolmcp.protocol.UpgradeArtifact;
import com.prodigalgal.remotecontrolmcp.protocol.UpgradeComponentPlan;
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

    /** Deferred registrations stay visible without inflating this round's acceptance denominator. */
    @JsonProperty(value = "summary", access = JsonProperty.Access.READ_ONLY)
    public Map<String, Object> summary() {
        var deferred = targets.stream().filter(target -> UpgradeService.DEFERRED.equals(target.status())).count();
        var completed = targets.stream().filter(target -> UpgradeService.COMPLETED.equals(target.status())).count();
        var failed = targets.stream().filter(target -> UpgradeService.FAILED.equals(target.status())).count();
        var eligible = targets.size() - deferred;
        var canaries = targets.stream().filter(target -> !UpgradeService.DEFERRED.equals(target.status()))
                .limit(canaryCount).toList();
        return Map.of("selected", targets.size(), "eligible", eligible, "completed", completed,
                "failed", failed, "deferred", deferred,
                "retrying", targets.stream().filter(target -> UpgradeService.RETRYING.equals(target.status())).count(),
                "canary_verified", !canaries.isEmpty() && canaries.stream().allMatch(target -> UpgradeService.COMPLETED.equals(target.status())),
                "max_auto_attempts", UpgradeService.MAX_AUTO_ATTEMPTS,
                "fully_updated", UpgradeService.COMPLETED.equals(status) && completed == targets.size() && deferred == 0);
    }
}
