package com.prodigalgal.remoteconnectmcp.protocol;

/** Progress or terminal result reported by an Agent upgrade helper. */
public record UpgradeStatusRequest(
        String campaignId,
        String status,
        String error) {
}
