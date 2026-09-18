package com.prodigalgal.remoteconnectmcp.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record RegisterRequest(
        String name,
        @JsonProperty("host_id") String hostId,
        String hostname,
        String os,
        String arch,
        String version,
        @JsonProperty("default_cwd") String defaultCwd,
        @JsonProperty("scope_mode") ScopeMode scopeMode,
        @JsonProperty("workspace_root") String workspaceRoot,
        List<String> capabilities,
        AgentRuntimeDescriptor runtime) {

    /** Local construction overload using the baseline runtime descriptor. */
    public RegisterRequest(String name, String hostId, String hostname, String os, String arch, String version,
                           String defaultCwd, ScopeMode scopeMode, String workspaceRoot, List<String> capabilities) {
        this(name, hostId, hostname, os, arch, version, defaultCwd, scopeMode, workspaceRoot, capabilities,
                AgentRuntimeDescriptor.defaults());
    }

    public AgentMetadata metadata() {
        return new AgentMetadata(name, hostId, hostname, os, arch, version, defaultCwd, scopeMode, workspaceRoot, capabilities, runtime);
    }
}
