package com.prodigalgal.remotecontrolmcp.center;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Map;

/** Compact per-machine projection for the console. */
public record UpgradeTargetView(
        String machineId,
        String status,
        String error,
        int attempts,
        Instant updatedAt,
        Instant finishedAt,
        Instant leaseUntil,
        Map<String, String> componentStatuses) {
    public UpgradeTargetView(String machineId, String status, String error, int attempts,
                             Instant updatedAt, Instant finishedAt, Instant leaseUntil) {
        this(machineId, status, error, attempts, updatedAt, finishedAt, leaseUntil, Map.of());
    }

    public UpgradeTargetView {
        componentStatuses = componentStatuses == null ? Map.of() : Map.copyOf(componentStatuses);
    }

    @JsonProperty(value = "retry_at", access = JsonProperty.Access.READ_ONLY)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Instant retryAt() {
        return UpgradeService.RETRYING.equals(status) ? leaseUntil : null;
    }
}
