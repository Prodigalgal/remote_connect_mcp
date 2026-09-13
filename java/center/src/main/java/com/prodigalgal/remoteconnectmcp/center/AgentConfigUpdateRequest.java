package com.prodigalgal.remoteconnectmcp.center;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Admin-writable subset of an Agent runtime config; generation is Center-owned. */
public record AgentConfigUpdateRequest(
        @JsonProperty("poll_interval_ms") Long pollIntervalMs,
        @JsonProperty("max_concurrency") Integer maxConcurrency) {
}
