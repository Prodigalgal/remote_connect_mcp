package com.prodigalgal.remoteconnectmcp.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Non-secret runtime settings that Center may apply between Agent heartbeats.
 * A generation of zero means that no update has been issued yet.
 */
public record AgentConfigUpdate(
        long generation,
        @JsonProperty("poll_interval_ms") long pollIntervalMs,
        @JsonProperty("max_concurrency") int maxConcurrency) {

    public AgentConfigUpdate {
        if (generation < 0) {
            throw new IllegalArgumentException("config generation must not be negative");
        }
        if (generation > 0) {
            if (pollIntervalMs < 250 || pollIntervalMs > 60000) {
                throw new IllegalArgumentException("poll interval must be between 250 and 60000 milliseconds");
            }
            if (maxConcurrency < 1 || maxConcurrency > 32) {
                throw new IllegalArgumentException("max concurrency must be between 1 and 32");
            }
        }
    }

    public static AgentConfigUpdate defaults() {
        return new AgentConfigUpdate(0, 0, 0);
    }
}
