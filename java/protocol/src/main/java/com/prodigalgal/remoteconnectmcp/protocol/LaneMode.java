package com.prodigalgal.remoteconnectmcp.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * Scheduling compatibility for tasks that target the same execution lane.
 * READ tasks may share a lane; WRITE tasks exclude reads and other writes;
 * EXCLUSIVE also documents operations that own the whole host/session.
 */
public enum LaneMode {
    READ("read"),
    WRITE("write"),
    EXCLUSIVE("exclusive");

    private final String wireValue;

    LaneMode(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static LaneMode fromWireValue(String value) {
        if (value == null || value.isBlank()) return WRITE;
        var normalized = value.trim().toLowerCase(Locale.ROOT);
        for (var mode : values()) {
            if (mode.wireValue.equals(normalized)) return mode;
        }
        throw new IllegalArgumentException("unsupported lane mode: " + value);
    }

    public boolean readOnly() {
        return this == READ;
    }
}
