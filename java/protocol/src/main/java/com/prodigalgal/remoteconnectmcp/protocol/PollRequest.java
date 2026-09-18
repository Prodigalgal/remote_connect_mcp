package com.prodigalgal.remoteconnectmcp.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record PollRequest(
        @JsonProperty("running_task_ids") List<String> runningTaskIds,
        @JsonProperty("available_slots") Integer availableSlots,
        @JsonProperty("available_capabilities") List<String> availableCapabilities,
        AgentMetadata metadata,
        @JsonProperty("config_generation") Long configGeneration) {

    /** Local construction overload for a heartbeat without metadata. */
    public PollRequest(List<String> runningTaskIds, Integer availableSlots, List<String> availableCapabilities) {
        this(runningTaskIds, availableSlots, availableCapabilities, AgentMetadata.empty(), 0L);
    }

    /** Local construction overload with explicit heartbeat metadata. */
    public PollRequest(List<String> runningTaskIds, Integer availableSlots, List<String> availableCapabilities,
                       AgentMetadata metadata) {
        this(runningTaskIds, availableSlots, availableCapabilities, metadata, 0L);
    }

    public PollRequest {
        runningTaskIds = runningTaskIds == null ? List.of() : List.copyOf(runningTaskIds);
        if (availableSlots == null) throw new IllegalArgumentException("availableSlots is required");
        availableCapabilities = availableCapabilities == null ? List.of() : List.copyOf(availableCapabilities);
        if (metadata == null) throw new IllegalArgumentException("metadata is required");
        if (configGeneration == null) throw new IllegalArgumentException("configGeneration is required");
        if (configGeneration < 0) throw new IllegalArgumentException("configGeneration must be non-negative");
    }
}
