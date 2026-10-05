package com.prodigalgal.remotecontrolmcp.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;

public record OutputResponse(@JsonProperty("next_offset") long nextOffset) {
}
