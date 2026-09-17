package com.prodigalgal.remoteconnectmcp.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Compact response for a completed or accepted binary transfer. */
public record FileTransferResponse(
        @JsonProperty("transfer_id") String transferId,
        @JsonProperty("artifact_id") String artifactId,
        String status,
        long bytes,
        String sha256,
        String error) {
}
