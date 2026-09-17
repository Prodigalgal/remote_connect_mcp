package com.prodigalgal.remoteconnectmcp.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum TaskKind {
    COMMAND("command"),
    DESKTOP("desktop"),
    BROWSER("browser"),
    FILE_TRANSFER("file_transfer");

    private final String wireValue;

    TaskKind(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static TaskKind fromWireValue(String value) {
        if (value == null || value.isBlank()) {
            return COMMAND;
        }
        for (TaskKind kind : values()) {
            if (kind.wireValue.equalsIgnoreCase(value.trim())) {
                return kind;
            }
        }
        throw new IllegalArgumentException("unsupported task kind: " + value);
    }
}
