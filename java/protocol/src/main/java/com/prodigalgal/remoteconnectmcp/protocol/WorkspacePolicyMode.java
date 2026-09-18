package com.prodigalgal.remoteconnectmcp.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * How a task obtains its workspace identity when multiple conversations share
 * one machine.  The policy is a coordination contract, not an OS sandbox.
 */
public enum WorkspacePolicyMode {
    /** Use the selected project worktree (or a generated session worktree). */
    ISOLATED("isolated"),
    /** Reuse one checkout but serialize mutations through its lane. */
    SHARED_SERIAL("shared_serial"),
    /** Explicit host-level operation; no project/worktree identity is added. */
    HOST("host");

    private final String wireValue;

    WorkspacePolicyMode(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static WorkspacePolicyMode fromWireValue(String value) {
        if (value == null || value.isBlank()) return SHARED_SERIAL;
        var normalized = value.trim().toLowerCase(Locale.ROOT);
        for (var mode : values()) {
            if (mode.wireValue.equals(normalized)) return mode;
        }
        throw new IllegalArgumentException("unsupported workspace policy: " + value);
    }
}
