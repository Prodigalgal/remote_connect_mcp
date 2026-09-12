package com.prodigalgal.remoteconnectmcp.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record PollRequest(
        @JsonProperty("running_task_ids") List<String> runningTaskIds,
        @JsonProperty("available_slots") Integer availableSlots,
        @JsonProperty("available_capabilities") List<String> availableCapabilities,
        AgentMetadata metadata) {

    /** Compatibility constructor for older Center/Agent callers. */
    public PollRequest(List<String> runningTaskIds, Integer availableSlots, List<String> availableCapabilities) {
        this(runningTaskIds, availableSlots, availableCapabilities, null);
    }

    public PollRequest {
        runningTaskIds = runningTaskIds == null ? List.of() : List.copyOf(runningTaskIds);
        availableSlots = availableSlots == null ? 0 : availableSlots;
        availableCapabilities = availableCapabilities == null ? List.of() : List.copyOf(availableCapabilities);
    }
}
