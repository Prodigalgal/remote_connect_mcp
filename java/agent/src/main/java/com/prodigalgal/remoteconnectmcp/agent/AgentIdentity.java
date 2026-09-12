package com.prodigalgal.remoteconnectmcp.agent;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Credentials issued by Center after a one-time enrollment. */
public record AgentIdentity(
        @JsonProperty("machine_id") String machineId,
        String token) {

    public AgentIdentity {
        requireSingleLine(machineId, "machineId");
        requireSingleLine(token, "token");
    }

    private static void requireSingleLine(String value, String field) {
        if (value == null || value.isBlank() || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(field + " is required and must be one line");
        }
    }
}
