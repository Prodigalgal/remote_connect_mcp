package com.prodigalgal.remoteconnectmcp.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Non-secret, versioned runtime capabilities advertised on every heartbeat. */
public record AgentRuntimeDescriptor(
        @JsonProperty("schema_version") int schemaVersion,
        @JsonProperty("config_generation") long configGeneration,
        @JsonProperty("max_concurrency") int maxConcurrency,
        @JsonProperty("max_browser_workers") int maxBrowserWorkers,
        @JsonProperty("max_output_bytes") long maxOutputBytes,
        @JsonProperty("max_aggregate_output_bytes") long maxAggregateOutputBytes,
        @JsonProperty("max_child_processes") int maxChildProcesses,
        @JsonProperty("max_total_child_processes") int maxTotalChildProcesses,
        @JsonProperty("max_task_duration_seconds") long maxTaskDurationSeconds,
        @JsonProperty("max_rss_bytes") long maxRssBytes,
        @JsonProperty("max_cpu_seconds") long maxCpuSeconds,
        @JsonProperty("desktop_enabled") boolean desktopEnabled,
        @JsonProperty("browser_adapter_configured") boolean browserAdapterConfigured,
        @JsonProperty("desktop_session_available") boolean desktopSessionAvailable,
        @JsonProperty("browser_session_available") boolean browserSessionAvailable,
        @JsonProperty("resource_enforcement") String resourceEnforcement) {

    /** Highest runtime descriptor schema understood by this release. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /** Local construction overload for callers that use the baseline descriptor. */
    public AgentRuntimeDescriptor(int schemaVersion, long configGeneration, int maxConcurrency,
                                  int maxBrowserWorkers, long maxOutputBytes, long maxAggregateOutputBytes,
                                  int maxChildProcesses, long maxTaskDurationSeconds, long maxRssBytes,
                                  long maxCpuSeconds, boolean desktopEnabled, boolean browserAdapterConfigured) {
        this(schemaVersion, configGeneration, maxConcurrency, maxBrowserWorkers, maxOutputBytes,
                maxAggregateOutputBytes, maxChildProcesses, Math.min(256, Math.max(32, Math.max(1, maxConcurrency) * 32)),
                maxTaskDurationSeconds, maxRssBytes, maxCpuSeconds, desktopEnabled, browserAdapterConfigured,
                false, false, "process-tree");
    }

    public AgentRuntimeDescriptor {
        if (schemaVersion < 1 || schemaVersion > CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported Agent runtime schema version: " + schemaVersion);
        }
        if (configGeneration < 0) throw new IllegalArgumentException("runtime config generation must be non-negative");
        if (resourceEnforcement == null || resourceEnforcement.isBlank()) {
            throw new IllegalArgumentException("runtime resource enforcement is required");
        }
        resourceEnforcement = resourceEnforcement.trim();
        if (resourceEnforcement.length() > 64 || resourceEnforcement.indexOf('\u0000') >= 0
                || resourceEnforcement.indexOf('\r') >= 0 || resourceEnforcement.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("runtime resource enforcement is invalid");
        }
        if (maxConcurrency < 1 || maxConcurrency > 32) throw new IllegalArgumentException("runtime max concurrency is outside the allowed range");
        if (maxBrowserWorkers < 0 || maxBrowserWorkers > 8) throw new IllegalArgumentException("runtime max browser workers is outside the allowed range");
        if (maxBrowserWorkers > maxConcurrency) throw new IllegalArgumentException("runtime browser workers exceed concurrency");
        if (maxOutputBytes < 1024L * 1024 || maxOutputBytes > 1024L * 1024 * 1024) throw new IllegalArgumentException("runtime max output is outside the allowed range");
        if (maxAggregateOutputBytes < maxOutputBytes || maxAggregateOutputBytes > 4L * 1024 * 1024 * 1024) throw new IllegalArgumentException("runtime aggregate output is outside the allowed range");
        if (maxChildProcesses < 1 || maxChildProcesses > 256) throw new IllegalArgumentException("runtime max child processes is outside the allowed range");
        if (maxTotalChildProcesses < 1 || maxTotalChildProcesses > 4096) throw new IllegalArgumentException("runtime max total child processes is outside the allowed range");
        if (maxTaskDurationSeconds < 0 || maxTaskDurationSeconds > ProtocolValidation.MAX_TIMEOUT_SECONDS) throw new IllegalArgumentException("runtime max duration is outside the allowed range");
        if (maxRssBytes < 0 || maxRssBytes > 16L * 1024 * 1024 * 1024) throw new IllegalArgumentException("runtime max RSS is outside the allowed range");
        if (maxCpuSeconds < 0 || maxCpuSeconds > ProtocolValidation.MAX_TIMEOUT_SECONDS) throw new IllegalArgumentException("runtime max CPU is outside the allowed range");
    }

    public static AgentRuntimeDescriptor defaults() {
        return new AgentRuntimeDescriptor(1, 0, 1, 1, 64L * 1024 * 1024,
                64L * 1024 * 1024, 32, 32, 0, 0, 0, false, false,
                false, false, "process-tree");
    }

}
