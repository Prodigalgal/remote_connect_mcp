package com.prodigalgal.remotecontrolmcp.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Bounded path hints, never credentials or a change to OS execution identity. */
public record AgentUserContext(
        @JsonProperty("command_user") String commandUser,
        @JsonProperty("command_home") String commandHome,
        @JsonProperty("interactive_user") String interactiveUser,
        @JsonProperty("desktop_path") String desktopPath) {
    public AgentUserContext {
        commandUser = bounded(commandUser, 256);
        commandHome = bounded(commandHome, 2048);
        interactiveUser = bounded(interactiveUser, 256);
        desktopPath = bounded(desktopPath, 2048);
    }

    private static String bounded(String value, int limit) {
        if (value == null || value.isBlank()) return "";
        var result = value.trim();
        if (result.length() > limit || result.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid user context");
        }
        return result;
    }
}
