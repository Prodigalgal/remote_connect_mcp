package com.prodigalgal.remoteconnectmcp.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RegisterResponse(@JsonProperty("machine_id") String machineId, String token) {
}
