package com.prodigalgal.remoteconnectmcp.agent;

import com.prodigalgal.remoteconnectmcp.protocol.PollRequest;
import com.prodigalgal.remoteconnectmcp.protocol.JsonCodec;
import com.prodigalgal.remoteconnectmcp.protocol.UpgradeStatusRequest;
import java.nio.file.Files;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Restart-safe registration, heartbeat and virtual-thread task dispatcher. */
public final class AgentRuntime {
    private static final Logger LOG = Logger.getLogger(AgentRuntime.class.getName());
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(2);

    private final AgentConfig config;
    private final AgentTransport transport;
    private final AgentIdentityStore identities;
    private final Map<String, Future<?>> running = new ConcurrentHashMap<>();
    private final Map<String, DurableCommandRunner> durableRunning = new ConcurrentHashMap<>();
    private final AtomicBoolean upgrading = new AtomicBoolean();
    private final AtomicBoolean stopRequested = new AtomicBoolean();

    public AgentRuntime(AgentConfig config, AgentTransport transport) {
        this(config, transport, new AgentIdentityStore(config.stateDir()));
    }

    AgentRuntime(AgentConfig config, AgentTransport transport, AgentIdentityStore identities) {
        this.config = config;
        this.transport = transport;
        this.identities = identities;
    }

    public void run() throws IOException, InterruptedException {
        // A service restart can briefly overlap the old process (especially
        // while a long-poll request is being closed).  Keep one command Agent
        // and one child-process pool per state directory.
        try (var ignored = AgentLock.acquire(config.stateDir())) {
            runLocked();
        }
    }

    private void runLocked() throws IOException, InterruptedException {
        TaskOutputSpool.cleanupOrphans(config.stateDir(), Duration.ofDays(7));
        var durableStore = new DurableTaskStore(config.stateDir());
        var identity = loadOrRegister();
        // Metadata is immutable for the lifetime of this process. Cache it
        // instead of rereading the version marker and rebuilding JSON on every
        // long-poll request; a successful self-upgrade restarts the Agent and
        // refreshes the marker in the new process.
        var metadata = config.metadata();
        var settings = new AgentRuntimeSettings(config);
        var backoff = settings.pollInterval();
        var resourceBudget = new AgentResourceBudget(config.maxAggregateOutputBytes());
        var desktopProcessBudget = new DesktopProcessBudget();
        var maxBrowserWorkers = config.maxBrowserWorkers();
        var browserRunning = new AtomicInteger();
        var wakeSignal = new AgentWakeSignal();
        var wakeClient = AgentWakeClient.startIfEnabled(config, identity, wakeSignal::signal);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            reportPendingUpgradeResult(identity);
            recoverDurable(identity, durableStore, executor, settings);
            while (!Thread.currentThread().isInterrupted() && !stopRequested.get()) {
                try {
                    var availableSlots = upgrading.get() ? 0 : Math.max(0, settings.maxConcurrency() - running.size());
                    var runningTaskIds = running.keySet().stream().sorted().toList();
                    // The Center chooses a task from this capability list. By
                    // withdrawing `browser` when its dedicated cap is full,
                    // command/desktop slots remain usable without queuing an
                    // unbounded number of browser workers in the Agent.
                    var availableCapabilities = availableCapabilities(config.capabilities(),
                            browserRunning.get(), maxBrowserWorkers);
                    var poll = transport.poll(identity.machineId(), identity.token(),
                            new PollRequest(runningTaskIds, availableSlots, availableCapabilities, metadata,
                                    settings.generation()));
                    if (poll.config() != null && settings.apply(poll.config())) {
                        LOG.info(() -> "applied Center runtime config generation " + settings.generation()
                                + ": pollIntervalMs=" + settings.pollInterval().toMillis()
                                + ", maxConcurrency=" + settings.maxConcurrency());
                        backoff = settings.pollInterval();
                    }
                    for (var taskId : poll.cancelTaskIds()) {
                        var durable = durableRunning.get(taskId);
                        if (durable != null) {
                            durable.requestCancel();
                        }
                        var future = running.get(taskId);
                        if (future != null) {
                            future.cancel(true);
                        }
                    }
                    if (poll.upgrade() != null && availableSlots > 0 && startUpgrade(poll.upgrade(), identity, executor, metadata)) {
                        backoff = settings.pollInterval();
                    } else if (poll.task() != null && availableSlots > 0 && !upgrading.get()) {
                        var task = poll.task();
                        if (task.kind() == com.prodigalgal.remoteconnectmcp.protocol.TaskKind.BROWSER
                                && browserRunning.get() >= maxBrowserWorkers) {
                            // This can only happen if a legacy Center ignores
                            // available_capabilities.  Leave the lease for
                            // its normal expiry instead of starting a process
                            // beyond the local cap.
                            LOG.warning("Center returned a browser task while the local browser worker cap is full; deferring it");
                            backoff = settings.pollInterval();
                            wakeSignal.await(backoff);
                            continue;
                        }
                        var browserSlotAcquired = task.kind() == com.prodigalgal.remoteconnectmcp.protocol.TaskKind.BROWSER;
                        if (browserSlotAcquired) browserRunning.incrementAndGet();
                        var taskIdentity = identity;
                        var durableRecord = task.kind() == com.prodigalgal.remoteconnectmcp.protocol.TaskKind.COMMAND
                                && task.timeoutSeconds() <= 0 ? durableStore.find(task.id()).orElse(null) : null;
                        var runner = switch (task.kind()) {
                            case COMMAND -> task.timeoutSeconds() <= 0
                                    ? new DurableCommandRunner(config, taskIdentity,
                                    durableRecord == null ? task : durableRecord.taskCommand(), transport, durableStore, durableRecord)
                                    : new CommandRunner(config, taskIdentity, task, transport, resourceBudget);
                            case DESKTOP -> new DesktopTaskRunner(config, taskIdentity, task, transport, desktopProcessBudget);
                            case BROWSER -> new BrowserTaskRunner(config, taskIdentity, task, transport, resourceBudget);
                        };
                        // FutureTask removes itself from the running map in its
                        // completion callback; no second waiter thread is needed
                        // just to call Future.get(). Putting it in the map before
                        // execute also closes the immediate-completion race.
                        var future = new FutureTask<Void>(() -> {
                            runner.run();
                            return null;
                        }) {
                            @Override
                            protected void done() {
                                running.remove(task.id(), this);
                                if (browserSlotAcquired) browserRunning.decrementAndGet();
                                if (runner instanceof DurableCommandRunner durable) {
                                    durableRunning.remove(task.id(), durable);
                                }
                            }
                        };
                        running.put(task.id(), future);
                        if (runner instanceof DurableCommandRunner durable) {
                            durableRunning.put(task.id(), durable);
                        }
                        executor.execute(future);
                        backoff = settings.pollInterval();
                    } else {
                        // Keep the effective delay in sync with the mutable
                        // Center configuration even when this poll returned no
                        // task.  Falling back to config.pollInterval() here
                        // silently ignored a hot-reloaded interval on every
                        // idle cycle.
                        backoff = settings.pollInterval();
                        // A Java Center that advertises long-polling has
                        // already held the request until a change or the
                        // server deadline. Do not add another fixed sleep;
                        // immediately issue the next long-poll request. Old
                        // Go/HTTP Centers do not send the header and retain
                        // the legacy backoff behavior.
                        if (!transport.longPollHonored()) wakeSignal.await(backoff);
                    }
                } catch (CenterTransportException exception) {
                    if ((exception.statusCode() == 401 || exception.statusCode() == 403) && running.isEmpty()) {
                        if (config.enrollmentToken() != null && !config.enrollmentToken().isBlank()) {
                            // Re-enrollment is only safe when an explicit
                            // one-time token is still available.  Installed
                            // services deliberately remove that token after
                            // the first registration; never delete the last
                            // known-good identity before a replacement has
                            // actually been issued.
                            LOG.warning("Agent credentials were rejected; requesting a fresh identity with the enrollment token");
                            try {
                                identity = register();
                                if (wakeClient != null) wakeClient.updateIdentity(identity);
                                backoff = config.pollInterval();
                            } catch (InterruptedException registrationFailure) {
                                // Preserve the service shutdown signal; do
                                // not turn an interrupted registration into a
                                // fresh retry loop.
                                Thread.currentThread().interrupt();
                                throw registrationFailure;
                            } catch (IOException registrationFailure) {
                                LOG.log(Level.WARNING, "Agent re-enrollment failed; retaining the existing identity and retrying", registrationFailure);
                                sleepWithJitter(backoff, wakeSignal);
                                backoff = nextBackoff(backoff);
                            }
                        } else {
                            LOG.warning("Agent credentials were rejected, but no enrollment token is configured; retaining identity and retrying");
                            sleepWithJitter(backoff, wakeSignal);
                            backoff = nextBackoff(backoff);
                        }
                    } else {
                        LOG.log(Level.WARNING, "Center poll failed; retrying with backoff", exception);
                        sleepWithJitter(backoff, wakeSignal);
                        backoff = nextBackoff(backoff);
                    }
                } catch (IOException exception) {
                    LOG.log(Level.WARNING, "Center connection failed; retrying with backoff", exception);
                    sleepWithJitter(backoff, wakeSignal);
                    backoff = nextBackoff(backoff);
                }
            }
        } finally {
            if (wakeClient != null) wakeClient.close();
            // Do not use ExecutorService.close() here: it waits indefinitely for
            // an unattended command. Interrupt the command/output virtual
            // threads and give them a bounded window to report cancellation.
            running.values().forEach(future -> future.cancel(true));
            executor.shutdownNow();
            try {
                executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            running.clear();
        }
    }

    private static java.util.List<String> availableCapabilities(java.util.List<String> configured,
                                                                 int browserRunning,
                                                                 int maxBrowserWorkers) {
        if (browserRunning < maxBrowserWorkers || !configured.contains("browser")) return configured;
        return configured.stream().filter(value -> !"browser".equals(value)).toList();
    }

    private void recoverDurable(AgentIdentity identity, DurableTaskStore store, ExecutorService executor,
                                AgentRuntimeSettings settings) {
        for (var record : store.load()) {
            if (running.size() >= settings.maxConcurrency()) {
                LOG.warning("durable task recovery reached the configured concurrency limit");
                return;
            }
            if (running.containsKey(record.taskId()) || !config.capabilities().contains("command")) {
                continue;
            }
            var task = record.taskCommand();
            var runner = new DurableCommandRunner(config, identity, task, transport, store, record);
            var future = new FutureTask<Void>(() -> {
                runner.run();
                return null;
            }) {
                @Override
                protected void done() {
                    running.remove(task.id(), this);
                    durableRunning.remove(task.id(), runner);
                }
            };
            running.put(task.id(), future);
            durableRunning.put(task.id(), runner);
            executor.execute(future);
            LOG.info(() -> "recovered durable task " + task.id());
        }
    }

    private boolean startUpgrade(com.prodigalgal.remoteconnectmcp.protocol.UpgradePlan plan,
                                 AgentIdentity identity, ExecutorService executor, com.prodigalgal.remoteconnectmcp.protocol.AgentMetadata metadata) {
        if (plan == null || plan.version() == null || plan.version().isBlank()
                || plan.version().equals(metadata.version())) {
            return false;
        }
        // A timed command is attached to the current Agent process and cannot
        // survive a binary replacement. Durable commands are safe because
        // their PID/log record is recovered by the next Agent instance.
        if (running.size() != durableRunning.size() || !upgrading.compareAndSet(false, true)) {
            return false;
        }
        executor.execute(() -> {
            try {
                new AgentUpgradeRunner(config, identity, transport, plan, () -> stopRequested.set(true)).run();
            } catch (Exception exception) {
                java.util.logging.Logger.getLogger(AgentRuntime.class.getName())
                        .log(java.util.logging.Level.WARNING, "Agent upgrade failed", exception);
            } finally {
                upgrading.set(false);
            }
        });
        return true;
    }

    private AgentIdentity loadOrRegister() throws IOException, InterruptedException {
        var existing = identities.load();
        if (existing.isPresent()) {
            return existing.get();
        }
        return register();
    }

    private AgentIdentity register() throws IOException, InterruptedException {
        if (config.enrollmentToken() == null || config.enrollmentToken().isBlank()) {
            throw new IOException("Agent is not registered and REMOTE_CONNECT_MCP_AGENT_ENROLLMENT_TOKEN is missing");
        }
        var response = transport.register(config);
        var identity = new AgentIdentity(response.machineId(), response.token());
        identities.save(identity);
        LOG.info(() -> "Agent registered successfully: machineId=" + identity.machineId());
        return identity;
    }

    /** Report the helper's terminal result before the first heartbeat. */
    private void reportPendingUpgradeResult(AgentIdentity identity) {
        var resultFile = config.stateDir().toAbsolutePath().normalize().resolve("upgrade-result.json");
        if (!Files.isRegularFile(resultFile)) return;
        try {
            var result = JsonCodec.read(Files.readAllBytes(resultFile), AgentUpgradeHelper.Result.class);
            AgentRetry.call(LOG, "upgrade result " + result.campaignId(), () -> {
                transport.reportUpgrade(identity.machineId(), identity.token(),
                        new UpgradeStatusRequest(result.campaignId(), result.status(), result.error()));
                return null;
            });
            Files.deleteIfExists(resultFile);
        } catch (CenterTransportException exception) {
            // A rolling upgrade can briefly restart against an older Center.
            // A 404 means it has no campaign record; retaining the result would
            // otherwise replay it forever on every service restart.
            if (exception.statusCode() == 404) {
                try { Files.deleteIfExists(resultFile); } catch (IOException ignored) { }
            } else {
                LOG.log(Level.WARNING, "could not report pending Agent upgrade result", exception);
            }
        } catch (Exception exception) {
            LOG.log(Level.WARNING, "could not report pending Agent upgrade result", exception);
        }
    }

    private static Duration nextBackoff(Duration current) {
        var nextMillis = Math.min(MAX_BACKOFF.toMillis(), Math.max(250, current.toMillis() * 2));
        return Duration.ofMillis(nextMillis);
    }

    private static void sleepWithJitter(Duration duration, AgentWakeSignal wakeSignal) throws InterruptedException {
        var base = Math.max(250, duration.toMillis());
        var jitter = ThreadLocalRandom.current().nextLong(Math.max(1, base / 5));
        wakeSignal.await(Duration.ofMillis(Math.min(MAX_BACKOFF.toMillis(), base + jitter)));
    }
}
