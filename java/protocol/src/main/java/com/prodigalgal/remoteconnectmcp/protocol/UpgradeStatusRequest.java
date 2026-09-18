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

    public UpgradeStatusRequest {
        if (attempt == null || attempt < 1) throw new IllegalArgumentException("upgrade attempt is required and must be positive");
        componentStatuses = componentStatuses == null ? Map.of() : Map.copyOf(componentStatuses);
    }
}
