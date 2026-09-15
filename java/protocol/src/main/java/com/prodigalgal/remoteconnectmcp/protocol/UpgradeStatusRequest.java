package com.prodigalgal.remoteconnectmcp.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Progress or terminal result reported by an Agent upgrade helper. */
public record UpgradeStatusRequest(
        String campaignId,
        String status,
        String error,
        @JsonProperty("attempt") Integer attempt) {

    /** Compatibility constructor for older Agents that do not send an offer attempt. */
    public UpgradeStatusRequest(String campaignId, String status, String error) {
        this(campaignId, status, error, null);
    }

    public UpgradeStatusRequest {
        if (attempt != null && attempt < 1) throw new IllegalArgumentException("upgrade attempt must be positive");
    }
}
