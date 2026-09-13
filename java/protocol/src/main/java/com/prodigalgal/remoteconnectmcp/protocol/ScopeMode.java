package com.prodigalgal.remoteconnectmcp.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ScopeMode {
    UNRESTRICTED("unrestricted"),
    WORKSPACE("workspace");

    private final String wireValue;

    ScopeMode(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static ScopeMode fromWireValue(String value) {
        if (value == null || value.isBlank()) {
            return UNRESTRICTED;
        }
        for (ScopeMode mode : values()) {
            if (mode.wireValue.equalsIgnoreCase(value.trim())) {
                return mode;
            }
        }
        throw new IllegalArgumentException("unsupported scope mode: " + value);
    }
}
