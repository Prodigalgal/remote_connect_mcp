package com.prodigalgal.remoteconnectmcp.center;

import com.prodigalgal.remoteconnectmcp.protocol.AgentRuntimeDescriptor;
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
        boolean online,
        AgentRuntimeDescriptor runtime) {

    /** Construction overload with default runtime projection. */
    public MachineView(String id, String name, String hostId, String hostname, String os, String arch,
                       String version, String defaultCwd, String scopeMode, String workspaceRoot,
                       List<String> capabilities, Instant createdAt, Instant lastSeen, boolean online) {
        this(id, name, hostId, hostname, os, arch, version, defaultCwd, scopeMode, workspaceRoot,
                capabilities, createdAt, lastSeen, online, AgentRuntimeDescriptor.defaults());
    }

    public MachineView {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        runtime = runtime == null ? AgentRuntimeDescriptor.defaults() : runtime;
    }
}
