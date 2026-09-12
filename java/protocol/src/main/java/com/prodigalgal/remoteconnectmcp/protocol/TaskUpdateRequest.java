package com.prodigalgal.remoteconnectmcp.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record TaskUpdateRequest(
        String status,
        @JsonProperty("exit_code") Integer exitCode,
        String error,
        @JsonProperty("started_at") Instant startedAt,
        @JsonProperty("finished_at") Instant finishedAt,
        @JsonProperty("output_truncated") Boolean outputTruncated) {

    public TaskUpdateRequest {
        outputTruncated = outputTruncated == null ? Boolean.FALSE : outputTruncated;
    }
}
