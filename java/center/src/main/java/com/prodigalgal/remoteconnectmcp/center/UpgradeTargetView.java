package com.prodigalgal.remoteconnectmcp.center;

import java.time.Instant;

/** Compact per-machine projection for the console. */
public record UpgradeTargetView(
        String machineId,
        String status,
        String error,
        int attempts,
        Instant updatedAt,
        Instant finishedAt,
        Instant leaseUntil) {
}
