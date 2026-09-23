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
        List<String> capabilities,
        AgentRuntimeDescriptor runtime) {

    /** Local construction overload using the baseline runtime descriptor. */
    public AgentMetadata(String name, String hostId, String hostname, String os, String arch, String version,
                         String defaultCwd, List<String> capabilities) {
        this(name, hostId, hostname, os, arch, version, defaultCwd, capabilities,
                AgentRuntimeDescriptor.defaults());
    }

    public AgentMetadata {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        if (runtime == null) throw new IllegalArgumentException("runtime is required");
    }

    static AgentMetadata empty() {
        var localRoot = java.nio.file.Path.of(".").toAbsolutePath().normalize().toString();
        return new AgentMetadata("local", "local", "local", "local", "local", "local", localRoot,
                List.of(), AgentRuntimeDescriptor.defaults());
    }
}
