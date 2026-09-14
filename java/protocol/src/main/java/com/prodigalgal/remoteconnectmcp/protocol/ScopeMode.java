package com.prodigalgal.remoteconnectmcp.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ScopeMode {
    /** Explicit whole-host access. Never use this as a caller-side default. */
    UNRESTRICTED("unrestricted"),
    /** Registered project root, without a worktree identity. */
    PROJECT("project"),
    /** One registered project worktree. */
    WORKTREE("worktree"),
    /** An explicitly supplied path root. */
    PATH("path"),
    /** Backwards-compatible name for a registered workspace root. */
    WORKSPACE("workspace");

    private final String wireValue;

    ScopeMode(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    public boolean bounded() {
        return this != UNRESTRICTED;
    }

    @JsonCreator
    public static ScopeMode fromWireValue(String value) {
        if (value == null || value.isBlank()) {
            // Missing scope data is treated as the safest bounded protocol
            // value. Legacy callers that need unrestricted access must carry
            // the explicit wire value instead of relying on a default.
            return WORKSPACE;
        }
        for (ScopeMode mode : values()) {
            if (mode.wireValue.equalsIgnoreCase(value.trim())) {
                return mode;
            }
        }
        throw new IllegalArgumentException("unsupported scope mode: " + value);
    }
}
