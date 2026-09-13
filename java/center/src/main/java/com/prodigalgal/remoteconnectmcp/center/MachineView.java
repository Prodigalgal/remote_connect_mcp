package com.prodigalgal.remoteconnectmcp.center;

import java.time.Instant;
import java.util.List;

/** Bounded machine projection safe for console and MCP responses. */
public record MachineView(
        String id,
        String name,
        String hostId,
        String hostname,
        String os,
        String arch,
        String version,
        String defaultCwd,
        String scopeMode,
        String workspaceRoot,
        List<String> capabilities,
        Instant createdAt,
        Instant lastSeen,
        boolean online) {

    public MachineView {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }
}
