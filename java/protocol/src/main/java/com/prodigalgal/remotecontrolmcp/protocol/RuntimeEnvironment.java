package com.prodigalgal.remotecontrolmcp.protocol;

import java.util.HashMap;
import java.util.Map;

/** Read the current names while accepting existing installations during a rolling migration. */
public final class RuntimeEnvironment {
    private static final String CURRENT = "REMOTE_CONTROL_MCP_";
    private static final String LEGACY = "REMOTE_CONNECT_MCP_";

    private RuntimeEnvironment() { }

    public static String get(String key) {
        return get(System.getenv(), key);
    }

    static String get(Map<String, String> environment, String key) {
        var value = environment.get(key);
        if (value != null || !key.startsWith(CURRENT)) return value;
        return environment.get(LEGACY + key.substring(CURRENT.length()));
    }

    public static String getOrDefault(String key, String fallback) {
        var value = get(key);
        return value == null ? fallback : value;
    }

    public static Map<String, String> snapshot() {
        var environment = new HashMap<>(System.getenv());
        System.getenv().forEach((key, value) -> {
            if (key.startsWith(LEGACY)) environment.putIfAbsent(CURRENT + key.substring(LEGACY.length()), value);
        });
        return Map.copyOf(environment);
    }

    /** Only a pre-existing desktop task needs this fallback; new installers always set its current name. */
    public static String desktopServiceDefault() {
        return System.getenv().keySet().stream().anyMatch(key -> key.startsWith(LEGACY))
                ? "RemoteConnectMCPDesktopCompanion" : "RemoteControlMCPDesktopCompanion";
    }
}
