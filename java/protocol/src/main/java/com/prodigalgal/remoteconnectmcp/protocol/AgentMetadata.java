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
        List<String> capabilities,
        AgentRuntimeDescriptor runtime) {

    /** Compatibility constructor for the pre-runtime-descriptor heartbeat. */
    public AgentMetadata(String name, String hostId, String hostname, String os, String arch, String version,
                         String defaultCwd, ScopeMode scopeMode, String workspaceRoot, List<String> capabilities) {
        this(name, hostId, hostname, os, arch, version, defaultCwd, scopeMode, workspaceRoot, capabilities,
                AgentRuntimeDescriptor.defaults());
    }

    public AgentMetadata {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        scopeMode = scopeMode == null ? ScopeMode.WORKSPACE : scopeMode;
        runtime = runtime == null ? AgentRuntimeDescriptor.defaults() : runtime;
    }
}
