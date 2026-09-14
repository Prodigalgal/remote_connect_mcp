package com.prodigalgal.remoteconnectmcp.agent;

import com.prodigalgal.remoteconnectmcp.protocol.TaskCommand;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bounded process-tree supervision for one Agent task.
 *
 * <p>The normal completion path is {@link ProcessHandle#onExit()}; a bounded
 * timed wait is used only when a resource policy is enabled so the Agent does
 * not run a fleet-wide polling loop. Linux RSS is read from procfs when
 * available, while CPU time and child count use the JDK process API on every
 * platform. If a platform cannot expose RSS, that optional check is skipped
 * and the configured wall-clock/CPU/process-tree limits still apply.</p>
 */
final class ProcessResourceSupervisor implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(ProcessResourceSupervisor.class.getName());
    private static final long DEFAULT_SAMPLE_MILLIS = 1000L;

    private final ProcessHandle root;
    private final AgentResourcePolicy policy;
    private final Runnable terminate;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<String> violation = new AtomicReference<>();
    private final Thread worker;

    private ProcessResourceSupervisor(ProcessHandle root, AgentResourcePolicy policy, Runnable terminate) {
        this.root = root;
        this.policy = policy;
        this.terminate = terminate == null ? () -> { } : terminate;
        this.worker = Thread.startVirtualThread(this::run);
    }

    static ProcessResourceSupervisor start(Process process, AgentConfig config, TaskCommand task,
                                           Runnable terminate) {
        if (process == null) return null;
        return start(process.toHandle(), config, task, terminate);
    }

    static ProcessResourceSupervisor start(ProcessHandle process, AgentConfig config, TaskCommand task,
                                           Runnable terminate) {
        if (process == null) return null;
        var supervisor = new ProcessResourceSupervisor(process, AgentResourcePolicy.forTask(config, task), terminate);
        var cgroupFailure = LinuxCgroupV2.tryAttach(process, config);
        if (cgroupFailure != null) supervisor.failClosed(cgroupFailure);
        return supervisor;
    }

    String violation() {
        return violation.get();
    }

    private void failClosed(String reason) {
        if (!violation.compareAndSet(null, reason)) return;
        try {
            terminate.run();
        } catch (RuntimeException failure) {
            LOG.log(Level.FINE, "could not terminate process after cgroup setup failure", failure);
        }
    }

    private void run() {
        if (!policy.enabled()) return;
        var started = System.nanoTime();
        var completion = root.onExit();
        var interval = Math.max(250L, policy.sampleIntervalMillis());
        try {
            while (!closed.get() && !completion.isDone()) {
                var reason = inspect(started);
                if (reason != null && violation.compareAndSet(null, reason)) {
                    LOG.warning(() -> "resource limit reached for process " + root.pid() + ": " + reason);
                    try {
                        terminate.run();
                    } catch (RuntimeException failure) {
                        LOG.log(Level.FINE, "could not terminate over-budget process", failure);
                    }
                    return;
                }
                if (completion.isDone()) return;
                try {
                    completion.get(interval, TimeUnit.MILLISECONDS);
                    return;
                } catch (TimeoutException ignored) {
                    // A resource check is due; this is task-local and only
                    // exists while a process with a policy is running.
                } catch (ExecutionException ignored) {
                    // The task runner owns the process exit code/error path;
                    // a failed process is already terminal for supervision.
                    return;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            // Monitoring must not turn a healthy task into a failed task due
            // to an OS probe race. The process-tree and wall-clock controls
            // remain authoritative whenever the JDK can observe them.
            LOG.log(Level.FINE, "resource probe stopped", failure);
        }
    }

    private String inspect(long startedNanos) {
        var elapsedSeconds = Duration.ofNanos(Math.max(0L, System.nanoTime() - startedNanos)).toSeconds();
        if (policy.maxDurationSeconds() > 0 && elapsedSeconds >= policy.maxDurationSeconds()) {
            return "task duration exceeded " + policy.maxDurationSeconds() + " seconds";
        }

        var processes = processTree(root);
        if (processes.size() > policy.maxChildProcesses()) {
            return "task process tree exceeded " + policy.maxChildProcesses() + " processes";
        }

        if (policy.maxCpuSeconds() > 0) {
            var cpuNanos = processes.stream().map(ProcessResourceSupervisor::cpuNanos)
                    .reduce(0L, ProcessResourceSupervisor::saturatingAdd);
            if (cpuNanos >= TimeUnit.SECONDS.toNanos(policy.maxCpuSeconds())) {
                return "task CPU time exceeded " + policy.maxCpuSeconds() + " seconds";
            }
        }

        if (policy.maxRssBytes() > 0 && isLinux()) {
            var rss = processes.stream().mapToLong(ProcessResourceSupervisor::rssBytes).sum();
            if (rss > 0 && rss >= policy.maxRssBytes()) {
                return "task RSS exceeded " + policy.maxRssBytes() + " bytes";
            }
        }
        return null;
    }

    private static List<ProcessHandle> processTree(ProcessHandle root) {
        var result = new ArrayList<ProcessHandle>();
        if (root != null && root.isAlive()) result.add(root);
        try {
            root.descendants().filter(ProcessHandle::isAlive).forEach(result::add);
        } catch (RuntimeException ignored) {
            // A child can disappear between discovery and the count. The
            // next event-driven sample observes the remaining live tree.
        }
        return List.copyOf(result);
    }

    private static long cpuNanos(ProcessHandle process) {
        try {
            return process.info().totalCpuDuration().map(Duration::toNanos).orElse(0L);
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private static long rssBytes(ProcessHandle process) {
        var status = Path.of("/proc", Long.toString(process.pid()), "status");
        try {
            for (var line : Files.readAllLines(status, StandardCharsets.US_ASCII)) {
                if (!line.startsWith("VmRSS:")) continue;
                var fields = line.substring("VmRSS:".length()).trim().split("\\s+");
                if (fields.length == 0) return 0L;
                var kilobytes = Long.parseLong(fields[0]);
                return Math.multiplyExact(kilobytes, 1024L);
            }
        } catch (IOException | NumberFormatException | ArithmeticException ignored) {
            // A short-lived process may disappear before status is read.
        }
        return 0L;
    }

    private static long saturatingAdd(long left, long right) {
        if (Long.MAX_VALUE - left < right) return Long.MAX_VALUE;
        return left + right;
    }

    private static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("linux");
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) worker.interrupt();
    }

    /** Immutable effective policy calculated once when the child starts. */
    record AgentResourcePolicy(long maxDurationSeconds, int maxChildProcesses, long maxRssBytes,
                               long maxCpuSeconds, long sampleIntervalMillis) {
        boolean enabled() {
            return maxDurationSeconds > 0 || maxChildProcesses > 0 || maxRssBytes > 0 || maxCpuSeconds > 0;
        }

        static AgentResourcePolicy forTask(AgentConfig config, TaskCommand task) {
            var duration = TaskLimits.durationSeconds(config, task);
            var children = TaskLimits.childProcesses(config, task);
            var rss = TaskLimits.rssBytes(config, task);
            var cpu = TaskLimits.cpuSeconds(config, task);
            var interval = config == null ? DEFAULT_SAMPLE_MILLIS : config.resourceSampleIntervalMillis();
            return new AgentResourcePolicy(duration, children, rss, cpu, interval);
        }
    }
}
