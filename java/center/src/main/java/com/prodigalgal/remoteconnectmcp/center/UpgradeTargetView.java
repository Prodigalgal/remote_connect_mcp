package com.prodigalgal.remoteconnectmcp.center;

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
}
