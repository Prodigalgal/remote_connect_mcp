package com.prodigalgal.remoteconnectmcp.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/** Progress or terminal result reported by an Agent upgrade helper. */
public record UpgradeStatusRequest(
        String campaignId,
        String status,
        String error,
        @JsonProperty("attempt") Integer attempt,
        @JsonProperty("component_statuses") Map<String, String> componentStatuses) {

    /** Compatibility constructor for older Agents that do not send an offer attempt. */
    public UpgradeStatusRequest(String campaignId, String status, String error) {
        this(campaignId, status, error, null, Map.of());
    }

    public UpgradeStatusRequest(String campaignId, String status, String error, Integer attempt) {
        this(campaignId, status, error, attempt, Map.of());
    }

    public UpgradeStatusRequest {
        if (attempt != null && attempt < 1) throw new IllegalArgumentException("upgrade attempt must be positive");
        componentStatuses = componentStatuses == null ? Map.of() : Map.copyOf(componentStatuses);
    }
}
