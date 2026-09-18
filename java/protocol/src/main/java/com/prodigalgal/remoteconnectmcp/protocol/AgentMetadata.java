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

    /** Local construction overload using the baseline runtime descriptor. */
    public AgentMetadata(String name, String hostId, String hostname, String os, String arch, String version,
                         String defaultCwd, ScopeMode scopeMode, String workspaceRoot, List<String> capabilities) {
        this(name, hostId, hostname, os, arch, version, defaultCwd, scopeMode, workspaceRoot, capabilities,
                AgentRuntimeDescriptor.defaults());
    }

    public AgentMetadata {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        if (scopeMode == null) throw new IllegalArgumentException("scopeMode is required");
        if (runtime == null) throw new IllegalArgumentException("runtime is required");
    }

    static AgentMetadata empty() {
        return new AgentMetadata("local", "local", "local", "local", "local", "local", ".",
                ScopeMode.WORKSPACE, ".", List.of(), AgentRuntimeDescriptor.defaults());
    }
}
