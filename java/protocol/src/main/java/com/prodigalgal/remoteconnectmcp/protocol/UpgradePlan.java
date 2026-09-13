package com.prodigalgal.remoteconnectmcp.protocol;

/** One Center-offered Agent release for the polling machine. */
public record UpgradePlan(
        String campaignId,
        String version,
        String url,
        String sha256) {
}
