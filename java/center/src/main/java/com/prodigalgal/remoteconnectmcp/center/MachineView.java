package com.prodigalgal.remoteconnectmcp.center;

import com.prodigalgal.remoteconnectmcp.protocol.AgentRuntimeDescriptor;
import java.time.Instant;
import java.util.List;

/** Machine projection: identity, capabilities and runtime facts only. */
public record MachineView(
        String id,
        String name,
        String hostId,
        String hostname,
        String os,
        String arch,
        String version,
        String defaultCwd,
        List<String> capabilities,
        Instant createdAt,
        Instant lastSeen,
        boolean online,
        AgentRuntimeDescriptor runtime) {

    public MachineView(String id, String name, String hostId, String hostname, String os, String arch,
                       String version, String defaultCwd, List<String> capabilities,
                       Instant createdAt, Instant lastSeen, boolean online) {
        this(id, name, hostId, hostname, os, arch, version, defaultCwd, capabilities,
                createdAt, lastSeen, online, AgentRuntimeDescriptor.defaults());
    }

    public MachineView {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        runtime = runtime == null ? AgentRuntimeDescriptor.defaults() : runtime;
    }

}
