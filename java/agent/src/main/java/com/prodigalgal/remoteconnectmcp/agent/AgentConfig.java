package com.prodigalgal.remoteconnectmcp.agent;

import com.prodigalgal.remoteconnectmcp.protocol.AgentCapability;
import com.prodigalgal.remoteconnectmcp.protocol.AgentMetadata;
import com.prodigalgal.remoteconnectmcp.protocol.AgentRuntimeDescriptor;
import com.prodigalgal.remoteconnectmcp.protocol.ProtocolValidation;
import com.prodigalgal.remoteconnectmcp.protocol.RegisterRequest;
import com.prodigalgal.remoteconnectmcp.protocol.ScopeMode;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

public record AgentConfig(
        URI centerUrl,
        String enrollmentToken,
        String name,
        String hostId,
        String defaultCwd,
        ScopeMode scopeMode,
        String workspaceRoot,
        List<String> capabilities,
        boolean desktopEnabled,
        Path stateDir,
        Duration pollInterval,
        int maxConcurrency,
        long maxOutputBytes,
        long maxAggregateOutputBytes,
        String browserAdapter) {

    private static final long DEFAULT_MAX_OUTPUT_BYTES = 64L * 1024 * 1024;
    private static final long DEFAULT_MAX_AGGREGATE_OUTPUT_BYTES = 256L * 1024 * 1024;
    private static final long MAX_AGGREGATE_OUTPUT_BYTES = 4L * 1024 * 1024 * 1024;
    private static final int DEFAULT_MAX_CHILD_PROCESSES = 32;
    private static final long MAX_TASK_DURATION_SECONDS = 30L * 24 * 60 * 60;
    private static final long MAX_TASK_RSS_BYTES = 16L * 1024 * 1024 * 1024;
    private static final long MAX_TASK_CPU_SECONDS = 30L * 24 * 60 * 60;
    private static final int MAX_TOTAL_CHILD_PROCESSES = 4096;

    /** Compatibility constructor for callers written before output limits were configurable. */
    public AgentConfig(URI centerUrl, String enrollmentToken, String name, String hostId,
                       String defaultCwd, ScopeMode scopeMode, String workspaceRoot,
                       List<String> capabilities, boolean desktopEnabled, Path stateDir,
                       Duration pollInterval, int maxConcurrency) {
        this(centerUrl, enrollmentToken, name, hostId, defaultCwd, scopeMode, workspaceRoot,
                capabilities, desktopEnabled, stateDir, pollInterval, maxConcurrency,
                DEFAULT_MAX_OUTPUT_BYTES, defaultAggregateOutputBytes(DEFAULT_MAX_OUTPUT_BYTES, maxConcurrency), "");
    }

    /** Compatibility constructor for callers that already pass a per-task output limit. */
    public AgentConfig(URI centerUrl, String enrollmentToken, String name, String hostId,
                       String defaultCwd, ScopeMode scopeMode, String workspaceRoot,
                       List<String> capabilities, boolean desktopEnabled, Path stateDir,
                       Duration pollInterval, int maxConcurrency, long maxOutputBytes) {
        this(centerUrl, enrollmentToken, name, hostId, defaultCwd, scopeMode, workspaceRoot,
                capabilities, desktopEnabled, stateDir, pollInterval, maxConcurrency,
                maxOutputBytes, defaultAggregateOutputBytes(maxOutputBytes, maxConcurrency), "");
    }

    public AgentConfig {
        if (centerUrl == null || centerUrl.getHost() == null
                || (!"https".equalsIgnoreCase(centerUrl.getScheme()) && !isLoopback(centerUrl.getHost()))) {
            throw new IllegalArgumentException("centerUrl must use HTTPS (HTTP is allowed only for loopback development)");
        }
        if (enrollmentToken != null && (enrollmentToken.indexOf('\r') >= 0 || enrollmentToken.indexOf('\n') >= 0)) {
            throw new IllegalArgumentException("enrollmentToken must be a single line when provided");
        }
        if (name == null || name.isBlank() || hostId == null || hostId.isBlank()) {
            throw new IllegalArgumentException("Agent name and hostId are required");
        }
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        scopeMode = scopeMode == null ? ScopeMode.WORKSPACE : scopeMode;
        if (scopeMode.bounded() && (workspaceRoot == null || workspaceRoot.isBlank())) {
            workspaceRoot = defaultCwd;
        }
        pollInterval = pollInterval == null ? Duration.ofSeconds(5) : pollInterval;
        if (pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("pollInterval must be positive");
        }
        if (maxConcurrency < 1 || maxConcurrency > 32) {
            throw new IllegalArgumentException("maxConcurrency must be between 1 and 32");
        }
        if (maxOutputBytes < 1024L * 1024 || maxOutputBytes > 1024L * 1024 * 1024) {
            throw new IllegalArgumentException("maxOutputBytes must be between 1048576 and 1073741824");
        }
        if (maxAggregateOutputBytes < maxOutputBytes || maxAggregateOutputBytes > MAX_AGGREGATE_OUTPUT_BYTES) {
            throw new IllegalArgumentException("maxAggregateOutputBytes must be at least maxOutputBytes and at most " + MAX_AGGREGATE_OUTPUT_BYTES);
        }
        browserAdapter = browserAdapter == null ? "" : browserAdapter.trim();
        if (browserAdapter.indexOf('\u0000') >= 0 || browserAdapter.indexOf('\r') >= 0
                || browserAdapter.indexOf('\n') >= 0 || browserAdapter.length() > 4096) {
            throw new IllegalArgumentException("browserAdapter must be a single line up to 4096 characters");
        }
    }

    public AgentMetadata metadata() {
        return metadata(0L, maxConcurrency);
    }

    AgentMetadata metadata(long configGeneration, int effectiveConcurrency) {
        // The release pipeline injects the binary version through the
        // environment. Keeping the default as dev preserves local protocol
        // tests while allowing Center to observe the real deployed version.
        var runtime = new AgentRuntimeDescriptor(1, Math.max(0L, configGeneration), effectiveConcurrency,
                Math.min(effectiveConcurrency, maxBrowserWorkers()), maxOutputBytes, maxAggregateOutputBytes,
                maxTaskChildProcesses(), maxTotalChildProcesses(), maxTaskDurationSeconds(), maxTaskRssBytes(), maxTaskCpuSeconds(),
                desktopEnabled, !browserAdapter.isBlank(), scopeMode,
                desktopSessionAvailable(), browserSessionAvailable(), resourceEnforcement());
        var metadata = new AgentMetadata(name, hostId, hostname(), operatingSystem(), architecture(),
                currentVersion(), defaultCwd, scopeMode, workspaceRoot, capabilities, runtime);
        ProtocolValidation.validateMetadata(metadata);
        return metadata;
    }

    private boolean desktopSessionAvailable() {
        return desktopEnabled && DesktopCompanionClient.discover(stateDir) != null;
    }

    private boolean browserSessionAvailable() {
        return !browserAdapter.isBlank()
                && Files.isRegularFile(stateDir.toAbsolutePath().normalize().resolve("browser-session.json"));
    }

    /** Non-secret description of the host-level process containment strategy. */
    public String resourceEnforcement() {
        return isLinux() && !resourceCgroupPath().isBlank() ? "cgroup-v2" : "process-tree";
    }

    /** Optional pre-created cgroup v2 directory for task processes. */
    public String resourceCgroupPath() {
        return optional("REMOTE_CONNECT_MCP_AGENT_CGROUP_PATH");
    }

    /** Optional persistent browser profile kept entirely on the target host. */
    public String browserProfileDir() {
        var value = optional("REMOTE_CONNECT_MCP_AGENT_BROWSER_PROFILE_DIR");
        if (value.isBlank()) return "";
        try {
            var path = Path.of(value);
            if (!path.isAbsolute()) throw new IllegalArgumentException("browser profile path must be absolute");
            return path.normalize().toString();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("REMOTE_CONNECT_MCP_AGENT_BROWSER_PROFILE_DIR is invalid", exception);
        }
    }

    /** Browser adapter engine selected locally; Center never chooses it. */
    public String browserEngine() {
        var value = optional("REMOTE_CONNECT_MCP_AGENT_BROWSER_ENGINE");
        if (value.isBlank()) return "playwright";
        var normalized = value.toLowerCase(java.util.Locale.ROOT);
        if (!List.of("playwright", "patchright", "comoufox").contains(normalized)) {
            throw new IllegalArgumentException("REMOTE_CONNECT_MCP_AGENT_BROWSER_ENGINE must be playwright, patchright, or comoufox");
        }
        return normalized;
    }

    public String browserName() {
        var value = optional("REMOTE_CONNECT_MCP_AGENT_BROWSER");
        if (value.isBlank()) return "chromium";
        var normalized = value.toLowerCase(java.util.Locale.ROOT);
        if (!List.of("chromium", "firefox", "webkit").contains(normalized)) {
            throw new IllegalArgumentException("REMOTE_CONNECT_MCP_AGENT_BROWSER must be chromium, firefox, or webkit");
        }
        return normalized;
    }

    public boolean browserHeadless() {
        var value = optional("REMOTE_CONNECT_MCP_AGENT_BROWSER_HEADLESS");
        if (value.isBlank()) return true;
        var normalized = value.toLowerCase(java.util.Locale.ROOT);
        if (List.of("1", "true", "yes").contains(normalized)) return true;
        if (List.of("0", "false", "no").contains(normalized)) return false;
        throw new IllegalArgumentException("REMOTE_CONNECT_MCP_AGENT_BROWSER_HEADLESS must be 0/1");
    }

    /** Long-poll hold time in seconds; zero explicitly restores legacy polling. */
    public long longPollSeconds() {
        return parseLongEnv("REMOTE_CONNECT_MCP_AGENT_LONG_POLL_SECONDS", 25, 0, 25);
    }

    /**
     * Wall-clock budget for one streamed file transfer.  Ordinary Center
     * requests stay short, but a multi-gigabyte upload/download must not be
     * aborted by the 30-second control-plane timeout.  The bound is still
     * finite so a broken connection cannot retain a transfer worker forever.
     */
    public Duration transferTimeout() {
        return Duration.ofSeconds(parseLongEnv("REMOTE_CONNECT_MCP_AGENT_TRANSFER_TIMEOUT_SECONDS",
                1800, 30, 24L * 60 * 60));
    }

    /** Abort a streamed body that makes no read progress after the response starts. */
    public Duration transferStallTimeout() {
        return Duration.ofSeconds(parseLongEnv("REMOTE_CONNECT_MCP_AGENT_TRANSFER_STALL_TIMEOUT_SECONDS",
                120, 5, 60 * 60));
    }

    /**
     * Browser adapters are heavier than command tasks.  Keep their process
     * pool independently bounded even when a host increases the general task
     * concurrency.  The value is intentionally boot-time/environment based;
     * Center hot configuration can lower general concurrency but cannot
     * silently create more browser processes.
     */
    public int maxBrowserWorkers() {
        var defaultValue = Math.min(2, maxConcurrency);
        var configured = Math.toIntExact(parseLongEnv(
                "REMOTE_CONNECT_MCP_AGENT_MAX_BROWSER_WORKERS", defaultValue, 1, 8));
        return Math.min(maxConcurrency, configured);
    }

    /**
     * Optional host-wide ceiling for one task. Zero preserves the durable
     * no-timeout command contract; a positive value is narrowed further by a
     * Center execution contract. This is read once per task from the process
     * environment, so a service restart is required to change hard limits.
     */
    public long maxTaskDurationSeconds() {
        return parseLongEnv("REMOTE_CONNECT_MCP_AGENT_MAX_TASK_DURATION_SECONDS", 0,
                0, MAX_TASK_DURATION_SECONDS);
    }

    /** Maximum descendants (including the root process) allowed for one task. */
    public int maxTaskChildProcesses() {
        return Math.toIntExact(parseLongEnv("REMOTE_CONNECT_MCP_AGENT_MAX_CHILD_PROCESSES",
                DEFAULT_MAX_CHILD_PROCESSES, 1, 256));
    }

    /**
     * Host-wide process-tree ceiling shared by all concurrently running task
     * supervisors.  The default grows with configured concurrency but stays
     * bounded, so raising the task slot count cannot silently create an
     * unbounded number of child processes.
     */
    public int maxTotalChildProcesses() {
        var defaultValue = Math.min(256, Math.max(DEFAULT_MAX_CHILD_PROCESSES,
                Math.multiplyExact(maxConcurrency, DEFAULT_MAX_CHILD_PROCESSES)));
        return Math.toIntExact(parseLongEnv("REMOTE_CONNECT_MCP_AGENT_MAX_TOTAL_CHILD_PROCESSES",
                defaultValue, 1, MAX_TOTAL_CHILD_PROCESSES));
    }

    /** Optional resident-set ceiling. Zero means the platform probe is disabled. */
    public long maxTaskRssBytes() {
        return parseLongEnv("REMOTE_CONNECT_MCP_AGENT_MAX_RSS_BYTES", 0, 0, MAX_TASK_RSS_BYTES);
    }

    /** Optional aggregate CPU-time ceiling. Zero means the probe is disabled. */
    public long maxTaskCpuSeconds() {
        return parseLongEnv("REMOTE_CONNECT_MCP_AGENT_MAX_CPU_SECONDS", 0, 0, MAX_TASK_CPU_SECONDS);
    }

    /** Low-frequency fallback probe interval for platforms without cgroups. */
    public long resourceSampleIntervalMillis() {
        return parseLongEnv("REMOTE_CONNECT_MCP_AGENT_RESOURCE_SAMPLE_INTERVAL_MS", 1000, 250, 10000);
    }

    public RegisterRequest registerRequest() {
        var metadata = metadata();
        return new RegisterRequest(metadata.name(), metadata.hostId(), metadata.hostname(), metadata.os(), metadata.arch(), metadata.version(), metadata.defaultCwd(), metadata.scopeMode(), metadata.workspaceRoot(), metadata.capabilities(), metadata.runtime());
    }

    public static AgentConfig fromEnvironment() {
        var center = required("REMOTE_CONNECT_MCP_AGENT_CENTER_URL");
        // The one-time Enrollment Token is needed only until identity.json is
        // created. Keeping it optional lets installers remove it from the
        // long-running service environment after the first registration.
        var token = optional("REMOTE_CONNECT_MCP_AGENT_ENROLLMENT_TOKEN");
        var name = env("REMOTE_CONNECT_MCP_AGENT_NAME", System.getenv().getOrDefault("COMPUTERNAME", "agent"));
        var hostId = env("REMOTE_CONNECT_MCP_AGENT_HOST_ID", name);
        var cwd = env("REMOTE_CONNECT_MCP_AGENT_DEFAULT_CWD", Path.of(".").toAbsolutePath().normalize().toString());
        var scope = ScopeMode.fromWireValue(env("REMOTE_CONNECT_MCP_AGENT_SCOPE_MODE", "workspace"));
        var workspace = System.getenv("REMOTE_CONNECT_MCP_AGENT_WORKSPACE_ROOT");
        if (scope.bounded() && (workspace == null || workspace.isBlank())) workspace = cwd;
        var desktop = Boolean.parseBoolean(env("REMOTE_CONNECT_MCP_AGENT_DESKTOP_ENABLED", "false"));
        var capabilities = Arrays.stream(System.getenv().getOrDefault("REMOTE_CONNECT_MCP_AGENT_CAPABILITIES", "command,durable_tasks,file_transfer").split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
        var normalizedCapabilities = new LinkedHashSet<>(capabilities);
        if (desktop) normalizedCapabilities.add(AgentCapability.DESKTOP.wireValue());
        else normalizedCapabilities.remove(AgentCapability.DESKTOP.wireValue());
        var browserAdapter = optional("REMOTE_CONNECT_MCP_AGENT_BROWSER_ADAPTER");
        var browserConfigured = !browserAdapter.isBlank();
        if (normalizedCapabilities.contains(AgentCapability.BROWSER.wireValue()) && !browserConfigured) {
            throw new IllegalArgumentException("browser capability requires REMOTE_CONNECT_MCP_AGENT_BROWSER_ADAPTER");
        }
        capabilities = List.copyOf(normalizedCapabilities);
        var state = Path.of(env("REMOTE_CONNECT_MCP_AGENT_STATE_DIR", defaultStateDir()));
        var pollInterval = Duration.ofMillis(parseLongEnv("REMOTE_CONNECT_MCP_AGENT_POLL_INTERVAL_MS", 5000, 250, 60000));
        var maxConcurrency = Math.toIntExact(parseLongEnv("REMOTE_CONNECT_MCP_AGENT_MAX_CONCURRENCY", 1, 1, 32));
        var maxOutputBytes = parseLongEnv("REMOTE_CONNECT_MCP_AGENT_MAX_OUTPUT_BYTES", DEFAULT_MAX_OUTPUT_BYTES,
                1024L * 1024, 1024L * 1024 * 1024);
        var maxAggregateOutputBytes = parseLongEnv("REMOTE_CONNECT_MCP_AGENT_MAX_AGGREGATE_OUTPUT_BYTES",
                defaultAggregateOutputBytes(maxOutputBytes, maxConcurrency), maxOutputBytes, MAX_AGGREGATE_OUTPUT_BYTES);
        return new AgentConfig(URI.create(center), token, name, hostId, cwd, scope, workspace, capabilities, desktop, state, pollInterval, maxConcurrency, maxOutputBytes, maxAggregateOutputBytes, browserAdapter);
    }

    private static String required(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank() || value.contains("\r") || value.contains("\n")) {
            throw new IllegalArgumentException(name + " is required and must be one line");
        }
        return value.trim();
    }

    private static String optional(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) return "";
        if (value.contains("\r") || value.contains("\n")) {
            throw new IllegalArgumentException(name + " must be one line");
        }
        return value.trim();
    }

    private static String env(String name, String fallback) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String defaultStateDir() {
        return System.getProperty("os.name", "").toLowerCase().contains("win")
                ? Path.of(System.getenv().getOrDefault("ProgramData", "."), "remote-connect-mcp-agent").toString()
                : "/var/lib/remote-connect-mcp-agent";
    }

    private static long parseLongEnv(String name, long fallback, long minimum, long maximum) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            var parsed = Long.parseLong(value.trim());
            if (parsed < minimum || parsed > maximum) {
                throw new IllegalArgumentException(name + " is outside the allowed range");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
    }

    private static long defaultAggregateOutputBytes(long maxOutputBytes, int maxConcurrency) {
        if (maxOutputBytes < 1 || maxConcurrency < 1) return Math.max(1L, maxOutputBytes);
        var multiplied = maxOutputBytes > Long.MAX_VALUE / maxConcurrency
                ? Long.MAX_VALUE : maxOutputBytes * maxConcurrency;
        return Math.max(maxOutputBytes, Math.min(DEFAULT_MAX_AGGREGATE_OUTPUT_BYTES, multiplied));
    }

    private static String operatingSystem() {
        var value = System.getProperty("os.name", "unknown").toLowerCase();
        if (value.contains("win")) return "windows";
        if (value.contains("linux")) return "linux";
        if (value.contains("mac") || value.contains("darwin")) return "macos";
        return value.replaceAll("[^a-z0-9._-]", "-");
    }

    private static String hostname() {
        return System.getenv().getOrDefault("COMPUTERNAME", System.getenv().getOrDefault("HOSTNAME", "unknown-host"));
    }

    private static String architecture() {
        var value = System.getProperty("os.arch", "unknown").toLowerCase();
        if (value.equals("x86_64") || value.equals("amd64") || value.equals("x64")) return "amd64";
        if (value.equals("aarch64") || value.equals("arm64") || value.equals("armv8")) return "arm64";
        return value.replaceAll("[^a-z0-9._-]", "-");
    }

    private static boolean isLoopback(String host) {
        var value = host == null ? "" : host.toLowerCase();
        return "localhost".equals(value) || "127.0.0.1".equals(value) || "::1".equals(value) || "[::1]".equals(value);
    }

    private static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase().contains("linux");
    }

    /**
     * A service environment normally supplies the initial version.  The
     * detached updater writes a private marker after a successful replacement
     * so a restarted Agent reports the new binary even when the service file
     * itself was not edited.
     */
    private String currentVersion() {
        var marker = stateDir.toAbsolutePath().normalize().resolve("agent-version");
        try {
            if (Files.isRegularFile(marker)) {
                var value = Files.readString(marker).trim();
                if (value.matches("[A-Za-z0-9._+\\-]{1,128}")) return value;
            }
        } catch (Exception ignored) {
            // Fall back to the environment value when the marker is unreadable.
        }
        return env("REMOTE_CONNECT_MCP_AGENT_VERSION", "dev");
    }
}
