package com.prodigalgal.remoteconnectmcp.center;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Admin-writable subset of an Agent runtime config; generation is Center-owned. */
public record AgentConfigUpdateRequest(
        @JsonProperty("poll_interval_ms") Long pollIntervalMs,
        @JsonProperty("max_concurrency") Integer maxConcurrency,
        /** Optional optimistic-concurrency guard from the last GET. */
        @JsonProperty("expected_generation") Long expectedGeneration) {

    /** Compatibility constructor for clients written before CAS support. */
    public AgentConfigUpdateRequest(Long pollIntervalMs, Integer maxConcurrency) {
        this(pollIntervalMs, maxConcurrency, null);
    }

    public AgentConfigUpdateRequest {
        if (expectedGeneration != null && expectedGeneration < 0) {
            throw new IllegalArgumentException("expected_generation must not be negative");
        }
    }
}
