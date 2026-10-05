package com.prodigalgal.remotecontrolmcp.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ArtifactResponse(
        @JsonProperty("bytes") long bytes,
        String sha256) {
}
