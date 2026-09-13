package com.prodigalgal.remoteconnectmcp.protocol;

import java.util.List;

public record AgentMetadata(
        String name,
        String hostId,
        String hostname,
        String os,
        String arch,
        String version,
        String defaultCwd,
        ScopeMode scopeMode,
        String workspaceRoot,
        List<String> capabilities) {

    public AgentMetadata {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        scopeMode = scopeMode == null ? ScopeMode.UNRESTRICTED : scopeMode;
    }
}
