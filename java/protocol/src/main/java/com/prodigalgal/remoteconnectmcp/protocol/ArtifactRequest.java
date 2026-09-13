package com.prodigalgal.remoteconnectmcp.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Bounded binary artifact upload encoded as base64 on the agent channel. */
public record ArtifactRequest(
        @JsonProperty("mime_type") String mimeType,
        String sha256,
        String data) {
}
