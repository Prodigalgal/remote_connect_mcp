package com.prodigalgal.remoteconnectmcp.center;

import com.prodigalgal.remoteconnectmcp.protocol.JsonCodec;
import com.prodigalgal.remoteconnectmcp.protocol.PollRequest;
import com.prodigalgal.remoteconnectmcp.protocol.UpgradeArtifact;
import com.prodigalgal.remoteconnectmcp.protocol.UpgradePlan;
import com.prodigalgal.remoteconnectmcp.protocol.UpgradeComponentPlan;
import com.prodigalgal.remoteconnectmcp.protocol.UpgradeStatusRequest;
import com.prodigalgal.remoteconnectmcp.protocol.SensitiveValueRedactor;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Center-owned, canary-first Agent release orchestration. The process selects
 * one adapter: memory keeps protocol tests self-contained, while PostgreSQL
 * stores campaign/target rows transactionally in production. There is no
 * dual-write path.
 */
@Service
public final class UpgradeService {
    public static final String RUNNING = "running";
    public static final String PAUSED = "paused";
    public static final String COMPLETED = "completed";
    public static final String CANCELED = "canceled";
    public static final String PENDING = "pending";
    public static final String OFFERED = "offered";
    public static final String DOWNLOADING = "downloading";
    public static final String INSTALLING = "installing";
    public static final String FAILED = "failed";

    private static final Pattern VERSION = Pattern.compile("v[0-9A-Za-z][0-9A-Za-z._+-]{0,127}");
    private static final Pattern SHA256 = Pattern.compile("(?i)[0-9a-f]{64}");
    private static final Pattern SAFE_COMPONENT = Pattern.compile("[A-Za-z0-9._-]{1,180}");
    private static final Duration ONLINE_WINDOW = Duration.ofSeconds(45);
    // A Native Image helper has to stop the current service, swap the bundle,
    // and start the replacement before the next heartbeat.  Thirty seconds is
    // short enough for a normal poll to re-offer the same campaign while the
    // first helper is still restarting, which can launch two helpers and race
    // the service manager.  Keep the lease bounded, but long enough to cover a
    // slow disk/network restart; a failed target can still be resumed from the
    // console after the lease expires.
    private static final Duration OFFER_LEASE = Duration.ofMinutes(5);
    private static final Duration STATUS_LEASE = Duration.ofMinutes(15);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(30);

    private final AgentRegistry agents;
    private final TaskService tasks;
    private final UpgradeConfig config;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final TaskChangeRegistry changes;
    private final AgentWakeRegistry wakes;
    private final AuditService audit;
    private final ReleaseManifestService manifests;
    private final Map<String, Campaign> memory = new ConcurrentHashMap<>();
    private final ReentrantLock memoryLock = new ReentrantLock();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    @Autowired
    public UpgradeService(AgentRegistry agents, TaskService tasks, UpgradeConfig config,
                           ObjectProvider<JdbcTemplate> jdbcProvider,
                           ObjectProvider<TransactionTemplate> transactionProvider,
                           ObjectProvider<TaskChangeRegistry> changeProvider,
                           ObjectProvider<AgentWakeRegistry> wakeProvider,
                           ObjectProvider<AuditService> auditProvider,
                           ObjectProvider<ReleaseManifestService> manifestProvider) {
        this.agents = agents;
        this.tasks = tasks;
        this.config = config;
        this.jdbc = jdbcProvider.getIfAvailable();
        this.transactions = transactionProvider.getIfAvailable();
        this.changes = changeProvider == null ? null : changeProvider.getIfAvailable();
        this.wakes = wakeProvider == null ? null : wakeProvider.getIfAvailable();
        this.audit = auditProvider == null ? null : auditProvider.getIfAvailable();
        this.manifests = manifestProvider == null ? null : manifestProvider.getIfAvailable();
        if (jdbc != null && transactions == null) {
            throw new IllegalStateException("TransactionTemplate is required when PostgreSQL persistence is enabled");
        }
    }

    UpgradeService(AgentRegistry agents, TaskService tasks, UpgradeConfig config) {
        this.agents = agents;
        this.tasks = tasks;
        this.config = config;
        this.jdbc = null;
        this.transactions = null;
        this.changes = null;
        this.wakes = null;
        this.audit = null;
        this.manifests = null;
    }

    public UpgradeCampaignView create(CreateUpgradeCampaignRequest request) {
        if (!config.enabled()) throw new IllegalStateException("Agent upgrades are disabled");
        var version = normalizeVersion(request == null ? null : request.version());
        var machines = agents.listAllMachines(Instant.now());
        var selected = selectMachines(request == null ? List.of() : request.machineIds(), machines,
                request == null || request.includeOffline());
        if (selected.isEmpty()) throw new IllegalArgumentException("at least one machine is required");
        var artifacts = resolveArtifacts(version, selected, request == null ? Map.of() : request.artifacts());
        var componentPlans = resolveComponentPlans(version, selected,
                request == null ? Map.of() : request.componentPlans());
        var canary = normalizePositive(request == null ? null : request.canaryCount(), 1, selected.size());
        var batch = normalizePositive(request == null ? null : request.batchSize(), 3, 200);
        var now = Instant.now();
        var campaign = new Campaign("upgrade_" + UUID.randomUUID().toString().replace("-", ""), version,
                RUNNING, canary, batch, Math.min(canary, selected.size()), artifacts, componentPlans,
                new ArrayList<>(), now, now, null);
        for (var machine : selected) {
            var machinePlatform = platform(machine.os(), machine.arch());
            var hasComponentWork = !componentPlans.getOrDefault(machinePlatform, List.of()).isEmpty();
            var status = version.equals(machine.version()) && !hasComponentWork ? COMPLETED : PENDING;
            var finished = COMPLETED.equals(status) ? now : null;
            campaign.targets.add(new Target(machine.id(), status, "", 0, now, finished, null));
        }
        if (jdbc != null) {
            var result = createJdbc(campaign);
            signalChange();
            signalTargets(result.targets());
            audit("upgrade.created", "admin", null, result.status(), "campaign=" + result.id() + ",targets=" + result.targets().size());
            return result;
        }
        memoryLock.lock();
        try {
            ensureNoActiveMemory();
            reconcile(campaign, machinesById(machines), now);
            memory.put(campaign.id, campaign);
            var result = view(campaign);
            signalChange();
            signalTargets(result.targets());
            audit("upgrade.created", "admin", null, result.status(), "campaign=" + result.id() + ",targets=" + result.targets().size());
            return result;
        } finally {
            memoryLock.unlock();
        }
    }

    public List<UpgradeCampaignView> list(int offset, int limit) {
        if (offset < 0) throw new IllegalArgumentException("offset must be non-negative");
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        if (jdbc != null) {
            return jdbc.query("SELECT campaign_id FROM rcm_upgrade_campaign ORDER BY created_at DESC, campaign_id DESC OFFSET ? LIMIT ?",
                    ps -> { ps.setInt(1, offset); ps.setInt(2, limit); },
                    (rs, row) -> loadJdbc(rs.getString("campaign_id"))).stream().filter(Objects::nonNull).map(UpgradeService::view).toList();
        }
        memoryLock.lock();
        try {
            return memory.values().stream().sorted(Comparator.comparing((Campaign value) -> value.createdAt).reversed())
                    .skip(offset).limit(limit).map(UpgradeService::view).toList();
        } finally {
            memoryLock.unlock();
        }
    }

    /** Total campaign count for the paginated operations view. */
    public int count() {
        if (jdbc != null) {
            var value = jdbc.queryForObject("SELECT COUNT(*) FROM rcm_upgrade_campaign", Long.class);
            return value == null ? 0 : Math.toIntExact(value);
        }
        memoryLock.lock();
        try {
            return memory.size();
        } finally {
            memoryLock.unlock();
        }
    }

    /** Return campaign status counters without exposing versions or artifacts. */
    public Map<String, Long> statusCounts() {
        if (jdbc != null) {
            var counts = new java.util.TreeMap<String, Long>();
            jdbc.query("SELECT status, COUNT(*) AS count FROM rcm_upgrade_campaign GROUP BY status ORDER BY status",
                    (rs, rowNum) -> {
                        counts.put(rs.getString("status"), rs.getLong("count"));
                        return null;
                    });
            return Map.copyOf(counts);
        }
        memoryLock.lock();
        try {
            var counts = new java.util.TreeMap<String, Long>();
            memory.values().forEach(campaign -> counts.merge(campaign.status, 1L, Long::sum));
            return Map.copyOf(counts);
        } finally {
            memoryLock.unlock();
        }
    }

    public UpgradeCampaignView control(String campaignId, String action) {
        var id = requiredId(campaignId);
        var normalizedAction = action == null ? "" : action.trim().toLowerCase();
        if (jdbc != null) {
            var result = transactions.execute(status -> controlJdbc(id, normalizedAction));
            signalChange();
            signalTargets(result.targets());
            audit("upgrade.control", "admin", null, result.status(), "campaign=" + result.id() + ",action=" + normalizedAction);
            return result;
        }
        memoryLock.lock();
        try {
            var campaign = requiredMemory(id);
            var now = Instant.now();
            if ("resume".equals(normalizedAction)) {
                if (COMPLETED.equals(campaign.status) || CANCELED.equals(campaign.status)) {
                    throw new IllegalArgumentException("upgrade campaign is already terminal");
                }
                for (var target : campaign.targets) {
                    if (FAILED.equals(target.status)) {
                        target.status = PENDING;
                        target.error = "";
                        target.componentStatuses = Map.of();
                        target.leaseUntil = null;
                        target.finishedAt = null;
                        target.updatedAt = now;
                    }
                }
                campaign.status = RUNNING;
                campaign.finishedAt = null;
                reconcile(campaign, machinesById(agents.listAllMachines(now)), now);
            } else if ("cancel".equals(normalizedAction)) {
                if (!COMPLETED.equals(campaign.status)) {
                    campaign.status = CANCELED;
                    campaign.finishedAt = now;
                }
            } else {
                throw new IllegalArgumentException("unsupported upgrade action: " + normalizedAction);
            }
            campaign.updatedAt = now;
            var result = view(campaign);
            signalChange();
            signalTargets(result.targets());
            audit("upgrade.control", "admin", null, result.status(), "campaign=" + result.id() + ",action=" + normalizedAction);
            return result;
        } finally {
            memoryLock.unlock();
        }
    }

    /**
     * Re-queue one failed target without resetting successful targets. This
     * keeps a paused campaign auditable and avoids making an operator replay a
     * healthy canary merely because one machine had a transient failure.
     */
    public UpgradeCampaignView retryTarget(String campaignId, String machineId) {
        var id = requiredId(campaignId);
        var targetId = machineId == null ? "" : machineId.trim();
        if (targetId.isBlank() || !SAFE_COMPONENT.matcher(targetId).matches()) {
            throw new IllegalArgumentException("machine id is invalid");
        }
        if (jdbc != null) {
            var result = transactions.execute(tx -> retryTargetJdbc(id, targetId));
            signalChange();
            signalTargets(result.targets());
            audit("upgrade.target.retry", "admin", targetId, result.status(),
                    "campaign=" + result.id());
            return result;
        }
        memoryLock.lock();
        try {
            var campaign = requiredMemory(id);
            if (COMPLETED.equals(campaign.status) || CANCELED.equals(campaign.status)) {
                throw new IllegalArgumentException("upgrade campaign is already terminal");
            }
            var target = target(campaign, targetId);
            if (target == null) throw new IllegalArgumentException("machine is not part of the upgrade campaign");
            if (!FAILED.equals(target.status)) throw new IllegalArgumentException("only a failed target can be requeued");
            var now = Instant.now();
            target.status = PENDING;
            target.error = "";
            target.componentStatuses = Map.of();
            target.leaseUntil = null;
            target.finishedAt = null;
            target.updatedAt = now;
            campaign.status = RUNNING;
            campaign.finishedAt = null;
            campaign.updatedAt = now;
            reconcile(campaign, machinesById(agents.listAllMachines(now)), now);
            var result = view(campaign);
            signalChange();
            signalTargets(result.targets());
            audit("upgrade.target.retry", "admin", targetId, result.status(), "campaign=" + result.id());
            return result;
        } finally {
            memoryLock.unlock();
        }
    }

    private UpgradeCampaignView retryTargetJdbc(String campaignId, String machineId) {
        var campaign = requiredJdbc(campaignId, true);
        if (COMPLETED.equals(campaign.status) || CANCELED.equals(campaign.status)) {
            throw new IllegalArgumentException("upgrade campaign is already terminal");
        }
        var target = target(campaign, machineId);
        if (target == null) throw new IllegalArgumentException("machine is not part of the upgrade campaign");
        if (!FAILED.equals(target.status)) throw new IllegalArgumentException("only a failed target can be requeued");
        var now = Instant.now();
        target.status = PENDING;
        target.error = "";
        target.leaseUntil = null;
        target.finishedAt = null;
        target.updatedAt = now;
        campaign.status = RUNNING;
        campaign.finishedAt = null;
        campaign.updatedAt = now;
        reconcileJdbc(campaign, now);
        return view(campaign);
    }

    public UpgradeCampaignView updateStatus(String machineId, UpgradeStatusRequest request) {
        if (request == null) throw new IllegalArgumentException("upgrade status body is required");
        var id = requiredId(request.campaignId());
        var status = request.status() == null ? "" : request.status().trim().toLowerCase();
        if (!(DOWNLOADING.equals(status) || INSTALLING.equals(status) || FAILED.equals(status) || COMPLETED.equals(status))) {
            throw new IllegalArgumentException("invalid upgrade status: " + status);
        }
        if (jdbc != null) {
            var result = transactions.execute(tx -> updateStatusJdbc(machineId, id, status, request.error(), request.attempt(), request.componentStatuses()));
            signalChange();
            signalTargets(result.targets());
            audit("upgrade.status", "agent", machineId, status, "campaign=" + id);
            return result;
        }
        memoryLock.lock();
        try {
            var campaign = requiredMemory(id);
            if (CANCELED.equals(campaign.status)) return view(campaign);
        var target = campaign.targets.stream().filter(value -> value.machineId.equals(machineId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("machine is not part of the upgrade campaign"));
            if (request.attempt() == null || request.attempt() != target.attempts) {
                // A helper from an expired offer may report after a newer offer
                // has already been issued. Treat the report as a stale
                // observation and leave the newer attempt authoritative.
                return view(campaign);
            }
            var now = Instant.now();
            if (!statusMayAdvance(target, status, now)) return view(campaign);
            applyStatus(campaign, target, status, request.error(), now, request.componentStatuses());
            reconcile(campaign, machinesById(agents.listAllMachines(now)), now);
            var result = view(campaign);
            signalChange();
            signalTargets(result.targets());
            audit("upgrade.status", "agent", machineId, status, "campaign=" + id);
            return result;
        } finally {
            memoryLock.unlock();
        }
    }

    /** Return one offer, or null, without holding an HTTP request thread. */
    public UpgradePlan offer(String machineId, PollRequest request) {
        if (!config.enabled() || request == null || request.availableSlots() == null || request.availableSlots() <= 0) return null;
        var machine = agents.findMachine(machineId, Instant.now()).orElse(null);
        if (machine == null || !safeForUpgrade(machine, request)) return null;
        if (jdbc != null) {
            var result = transactions.execute(tx -> offerJdbc(machine, request));
            if (result != null) signalChange();
            return result;
        }
        memoryLock.lock();
        try {
            var now = Instant.now();
            var byId = machinesById(agents.listAllMachines(now));
            for (var campaign : memory.values().stream().sorted(Comparator.comparing((Campaign value) -> value.createdAt)).toList()) {
                if (!RUNNING.equals(campaign.status)) continue;
                reconcile(campaign, byId, now);
                var plan = offerFromCampaign(campaign, machine, now);
                if (plan != null) {
                    signalChange();
                    return plan;
                }
            }
            return null;
        } finally {
            memoryLock.unlock();
        }
    }

    private UpgradePlan offerJdbc(MachineView machine, PollRequest request) {
        var now = Instant.now();
        // Serialize offer allocation at the campaign row. Without this
        // lock two Agent polls can observe the same pending target and both
        // increment its attempt counter, launching two helpers for one host.
        var campaign = loadActiveJdbc(true);
        if (campaign == null) return null;
        reconcileJdbc(campaign, now);
        var target = target(campaign, machine.id());
        if (target == null) return null;
        if (COMPLETED.equals(target.status)) return null;
        var componentWork = campaign.componentPlans.getOrDefault(
                platform(machine.os(), machine.arch()), List.of());
        if (machine.version() != null && machine.version().equals(campaign.version)
                && componentWork.isEmpty()) {
            applyStatus(campaign, target, COMPLETED, "", now);
            reconcileJdbc(campaign, now);
            return null;
        }
        var plan = offerFromCampaign(campaign, machine, now);
        // Persist both a normal offer and a terminal reconciliation/failure
        // (for example, an unavailable platform artifact) so a poll cannot
        // repeatedly rediscover the same stale campaign state.
        persistJdbc(campaign, target);
        return plan;
    }

    private UpgradePlan offerFromCampaign(Campaign campaign, MachineView machine, Instant now) {
        var index = campaign.targets.indexOf(campaign.targets.stream().filter(value -> value.machineId.equals(machine.id())).findFirst().orElse(null));
        if (index < 0 || index >= campaign.activeLimit) return null;
        var target = campaign.targets.get(index);
        if (COMPLETED.equals(target.status)) return null;
        if (target.leaseUntil != null && now.isBefore(target.leaseUntil) && !PENDING.equals(target.status)) {
            if (!OFFERED.equals(target.status) || Duration.between(target.updatedAt, now).compareTo(OFFER_LEASE) < 0) return null;
        }
        var artifact = campaign.artifacts.get(platform(machine.os(), machine.arch()));
        if (artifact == null) {
            target.status = FAILED;
            target.error = "artifact is unavailable for " + platform(machine.os(), machine.arch());
            target.finishedAt = now;
            target.leaseUntil = null;
            campaign.status = PAUSED;
            campaign.updatedAt = now;
            return null;
        }
        target.status = OFFERED;
        target.attempts++;
        target.updatedAt = now;
        target.leaseUntil = now.plus(OFFER_LEASE);
        campaign.updatedAt = now;
        var components = campaign.componentPlans.getOrDefault(platform(machine.os(), machine.arch()), List.of());
        return new UpgradePlan(campaign.id, campaign.version, artifact.url(), artifact.sha256(), target.attempts, components);
    }

    private boolean safeForUpgrade(MachineView machine, PollRequest request) {
        var running = request.runningTaskIds() == null ? List.<String>of() : request.runningTaskIds();
        if (running.isEmpty()) return true;
        if (!machine.capabilities().contains("durable_tasks")) return false;
        for (var taskId : running) {
            if (!tasks.isDurableTask(machine.id(), taskId)) return false;
        }
        return true;
    }

    private UpgradeCampaignView createJdbc(Campaign campaign) {
        return transactions.execute(status -> {
            ensureNoActiveJdbc();
            var artifacts = new String(JsonCodec.write(campaign.artifacts.values()), StandardCharsets.UTF_8);
            var componentPlans = new String(JsonCodec.write(campaign.componentPlans), StandardCharsets.UTF_8);
            jdbc.update("""
                    INSERT INTO rcm_upgrade_campaign(campaign_id, version, status, canary_count, batch_size, active_limit,
                        artifacts, component_plans, created_at, updated_at, finished_at)
                    VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?)
                    """, campaign.id, campaign.version, campaign.status, campaign.canaryCount, campaign.batchSize,
                    campaign.activeLimit, artifacts, componentPlans, timestamp(campaign.createdAt), timestamp(campaign.updatedAt), timestamp(campaign.finishedAt));
            for (int index = 0; index < campaign.targets.size(); index++) {
                var target = campaign.targets.get(index);
                jdbc.update("""
                        INSERT INTO rcm_upgrade_target(campaign_id, target_index, agent_id, status, error_text, attempts,
                            updated_at, finished_at, lease_until, component_statuses)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                        """, campaign.id, index, target.machineId, target.status, target.error, target.attempts,
                        timestamp(target.updatedAt), timestamp(target.finishedAt), timestamp(target.leaseUntil),
                        new String(JsonCodec.write(target.componentStatuses), StandardCharsets.UTF_8));
            }
            return view(campaign);
        });
    }

    private UpgradeCampaignView controlJdbc(String id, String action) {
        var campaign = requiredJdbc(id, true);
        var now = Instant.now();
        if ("resume".equals(action)) {
            if (COMPLETED.equals(campaign.status) || CANCELED.equals(campaign.status)) throw new IllegalArgumentException("upgrade campaign is already terminal");
            for (var target : campaign.targets) {
                if (FAILED.equals(target.status)) {
                    target.status = PENDING; target.error = ""; target.leaseUntil = null; target.finishedAt = null; target.updatedAt = now;
                    target.componentStatuses = Map.of();
                    updateTargetJdbc(campaign.id, target);
                }
            }
            campaign.status = RUNNING; campaign.finishedAt = null;
            reconcileJdbc(campaign, now);
        } else if ("cancel".equals(action)) {
            if (!COMPLETED.equals(campaign.status)) { campaign.status = CANCELED; campaign.finishedAt = now; }
        } else throw new IllegalArgumentException("unsupported upgrade action: " + action);
        campaign.updatedAt = now;
        updateCampaignJdbc(campaign);
        return view(campaign);
    }

    private UpgradeCampaignView updateStatusJdbc(String machineId, String id, String status, String error, Integer attempt,
                                                 Map<String, String> componentStatuses) {
        var campaign = requiredJdbc(id, true);
        // A late status report from an Agent that was already offered an
        // upgrade must not mutate a campaign the operator canceled while the
        // helper was restarting.  Return the authoritative terminal snapshot;
        // the conditional SQL below also protects against a stale transaction
        // that loaded the campaign before the cancel committed.
        if (CANCELED.equals(campaign.status)) return view(campaign);
        var target = target(campaign, machineId);
        if (target == null) throw new IllegalArgumentException("machine is not part of the upgrade campaign");
        if ((attempt == null && target.attempts > 1)
                || (attempt != null && attempt != target.attempts)) {
            return view(campaign);
        }
        var now = Instant.now();
        if (!statusMayAdvance(target, status, now)) return view(campaign);
        applyStatus(campaign, target, status, error, now, componentStatuses);
        updateTargetJdbc(campaign.id, target);
        reconcileJdbc(campaign, now);
        updateCampaignJdbc(campaign);
        return view(campaign);
    }

    private void reconcileJdbc(Campaign campaign, Instant now) {
        var machines = machinesById(agents.listAllMachines(now));
        reconcile(campaign, machines, now);
        for (var target : campaign.targets) updateTargetJdbc(campaign.id, target);
        updateCampaignJdbc(campaign);
    }

    private void persistJdbc(Campaign campaign, Target target) {
        updateTargetJdbc(campaign.id, target);
        updateCampaignJdbc(campaign);
    }

    private void updateTargetJdbc(String campaignId, Target target) {
        jdbc.update("""
                 UPDATE rcm_upgrade_target SET status = ?, error_text = ?, attempts = ?, updated_at = ?, finished_at = ?, lease_until = ?, component_statuses = CAST(? AS jsonb)
                 WHERE campaign_id = ? AND agent_id = ?
                   AND EXISTS (
                       SELECT 1 FROM rcm_upgrade_campaign c
                        WHERE c.campaign_id = ? AND c.status <> ?
                   )
                """, target.status, target.error, target.attempts, timestamp(target.updatedAt), timestamp(target.finishedAt),
                timestamp(target.leaseUntil), new String(JsonCodec.write(target.componentStatuses), StandardCharsets.UTF_8),
                campaignId, target.machineId, campaignId, CANCELED);
    }

    private void updateCampaignJdbc(Campaign campaign) {
        // Do not let a stale poll/status transaction revive a terminal
        // campaign after an operator action.  The incoming terminal state is
        // still allowed to win (for example, the transaction that completes a
        // campaign), while running/paused snapshots cannot overwrite either
        // completed or canceled rows.
        jdbc.update("""
                UPDATE rcm_upgrade_campaign
                   SET status = ?, active_limit = ?, updated_at = ?, finished_at = ?
                 WHERE campaign_id = ?
                   AND (status NOT IN (?, ?) OR ? IN (?, ?))
                """, campaign.status, campaign.activeLimit, timestamp(campaign.updatedAt), timestamp(campaign.finishedAt), campaign.id,
                COMPLETED, CANCELED, campaign.status, COMPLETED, CANCELED);
    }

    private Campaign loadActiveJdbc(boolean forUpdate) {
        // Agent heartbeats arrive concurrently.  A poll that cannot acquire
        // the campaign allocation lock must not consume a JDBC connection
        // while waiting behind the current allocator; the next event/heartbeat
        // will retry after the short transaction completes.
        var lockClause = forUpdate ? " FOR UPDATE SKIP LOCKED" : "";
        var ids = jdbc.query("SELECT campaign_id FROM rcm_upgrade_campaign WHERE status = ? ORDER BY created_at LIMIT 1" + lockClause,
                ps -> ps.setString(1, RUNNING), (rs, row) -> rs.getString(1));
        return ids.isEmpty() ? null : loadJdbc(ids.get(0), forUpdate);
    }

    private Campaign requiredJdbc(String id) {
        return requiredJdbc(id, false);
    }

    private Campaign requiredJdbc(String id, boolean forUpdate) {
        var value = loadJdbc(id, forUpdate);
        if (value == null) throw new IllegalArgumentException("upgrade campaign not found: " + id);
        return value;
    }

    private Campaign loadJdbc(String id) {
        return loadJdbc(id, false);
    }

    private Campaign loadJdbc(String id, boolean forUpdate) {
        var lock = forUpdate ? " FOR UPDATE" : "";
        var rows = jdbc.query("SELECT campaign_id, version, status, canary_count, batch_size, active_limit, artifacts, component_plans, created_at, updated_at, finished_at FROM rcm_upgrade_campaign WHERE campaign_id = ?" + lock,
                ps -> ps.setString(1, id), (rs, row) -> {
                    var artifacts = readArtifacts(rs.getString("artifacts"));
                    var componentPlans = readComponentPlans(rs.getString("component_plans"));
                    return new Campaign(rs.getString("campaign_id"), rs.getString("version"), rs.getString("status"),
                            rs.getInt("canary_count"), rs.getInt("batch_size"), rs.getInt("active_limit"), artifacts,
                            componentPlans, new ArrayList<>(), instant(rs, "created_at"), instant(rs, "updated_at"), instant(rs, "finished_at"));
                });
        if (rows.isEmpty()) return null;
        var campaign = rows.get(0);
            campaign.targets.addAll(jdbc.query("SELECT agent_id, status, error_text, attempts, updated_at, finished_at, lease_until, component_statuses FROM rcm_upgrade_target WHERE campaign_id = ? ORDER BY target_index" + lock,
                ps -> ps.setString(1, id), (rs, row) -> new Target(rs.getString("agent_id"), rs.getString("status"),
                        rs.getString("error_text"), rs.getInt("attempts"), instant(rs, "updated_at"), instant(rs, "finished_at"), instant(rs, "lease_until"),
                        readStringMap(rs.getString("component_statuses")))));
        return campaign;
    }

    private void ensureNoActiveJdbc() {
        var count = jdbc.queryForObject("SELECT COUNT(*) FROM rcm_upgrade_campaign WHERE status IN (?, ?)", Integer.class, RUNNING, PAUSED);
        if (count != null && count > 0) throw new IllegalArgumentException("another upgrade campaign is still active");
    }

    private void ensureNoActiveMemory() {
        if (memory.values().stream().anyMatch(value -> RUNNING.equals(value.status) || PAUSED.equals(value.status))) {
            throw new IllegalArgumentException("another upgrade campaign is still active");
        }
    }

    private void applyStatus(Campaign campaign, Target target, String status, String error, Instant now) {
        applyStatus(campaign, target, status, error, now, Map.of());
    }

    /** Prevent a discovered release from starting a second fleet campaign after restart. */
    public boolean hasCampaignVersion(String version) {
        if (jdbc != null) {
            var count = jdbc.queryForObject("SELECT COUNT(*) FROM rcm_upgrade_campaign WHERE version = ?", Long.class, version);
            return count != null && count > 0;
        }
        memoryLock.lock();
        try {
            return memory.values().stream().anyMatch(campaign -> campaign.version.equals(version));
        } finally {
            memoryLock.unlock();
        }
    }

    private void applyStatus(Campaign campaign, Target target, String status, String error, Instant now,
                             Map<String, String> componentStatuses) {
        if (componentStatuses != null && !componentStatuses.isEmpty()) {
            target.componentStatuses = componentStatuses.entrySet().stream()
                    .filter(entry -> entry.getKey() != null && entry.getKey().matches("[a-z][a-z0-9-]{1,63}")
                            && entry.getValue() != null)
                    .limit(16)
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,
                            entry -> compactComponentStatus(entry.getValue())));
        }
        if (DOWNLOADING.equals(status) || INSTALLING.equals(status)) {
            target.status = status; target.error = ""; target.leaseUntil = now.plus(STATUS_LEASE);
        } else if (FAILED.equals(status)) {
            target.status = FAILED; target.error = compactError(error); target.leaseUntil = null; target.finishedAt = now; campaign.status = PAUSED;
        } else {
            target.status = COMPLETED; target.error = ""; target.leaseUntil = null; target.finishedAt = now;
        }
        target.updatedAt = now;
        campaign.updatedAt = now;
    }

    private static String compactComponentStatus(String value) {
        var normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.length() <= 32 ? normalized : normalized.substring(0, 32);
    }

    /**
     * Reject out-of-order helper reports. A completed target is immutable for
     * the current attempt, and an expired offer cannot be resurrected by a
     * delayed HTTP response; the next poll will receive a fresh attempt.
     */
    private static boolean statusMayAdvance(Target target, String status, Instant now) {
        if (target == null || status == null || now == null) return false;
        if (COMPLETED.equals(target.status)) return COMPLETED.equals(status);
        if (FAILED.equals(target.status)) return FAILED.equals(status);
        if (target.leaseUntil != null && !now.isBefore(target.leaseUntil)) return false;
        if (Objects.equals(target.status, status)) return true;
        return switch (target.status) {
            case OFFERED -> DOWNLOADING.equals(status) || INSTALLING.equals(status)
                    || COMPLETED.equals(status) || FAILED.equals(status);
            case DOWNLOADING -> INSTALLING.equals(status) || COMPLETED.equals(status) || FAILED.equals(status);
            case INSTALLING -> COMPLETED.equals(status) || FAILED.equals(status);
            case PENDING -> false;
            default -> false;
        };
    }

    private void reconcile(Campaign campaign, Map<String, MachineView> machines, Instant now) {
        if (CANCELED.equals(campaign.status)) return;
        var allCompleted = true;
        for (var target : campaign.targets) {
            var machine = machines.get(target.machineId);
            var componentWork = machine == null ? List.<UpgradeComponentPlan>of()
                    : campaign.componentPlans.getOrDefault(platform(machine.os(), machine.arch()), List.of());
            if (machine != null && campaign.version.equals(machine.version()) && componentWork.isEmpty()
                    && !COMPLETED.equals(target.status)) {
                target.status = COMPLETED; target.error = ""; target.leaseUntil = null; target.updatedAt = now; target.finishedAt = now;
            }
            if (FAILED.equals(target.status)) campaign.status = PAUSED;
            if (!COMPLETED.equals(target.status)) allCompleted = false;
        }
        if (allCompleted) {
            campaign.status = COMPLETED; campaign.activeLimit = campaign.targets.size(); campaign.finishedAt = now; campaign.updatedAt = now; return;
        }
        if (!RUNNING.equals(campaign.status)) return;
        while (campaign.activeLimit < campaign.targets.size()) {
            var completedWave = true;
            for (int index = 0; index < campaign.activeLimit; index++) {
                if (!COMPLETED.equals(campaign.targets.get(index).status)) { completedWave = false; break; }
            }
            if (!completedWave) break;
            campaign.activeLimit = Math.min(campaign.activeLimit + campaign.batchSize, campaign.targets.size());
            campaign.updatedAt = now;
        }
    }

    private Map<String, UpgradeArtifact> resolveArtifacts(String version, List<MachineView> machines, Map<String, UpgradeArtifact> supplied) {
        var result = new LinkedHashMap<String, UpgradeArtifact>();
        var platforms = machines.stream().map(value -> platform(value.os(), value.arch())).distinct().toList();
        for (var key : platforms) {
            var artifact = supplied.get(key);
            if (artifact == null) artifact = supplied.get(key.toLowerCase());
            if (artifact == null) artifact = resolveReleaseArtifact(version, key);
            validateArtifact(key, artifact);
            result.put(key, new UpgradeArtifact(key.substring(0, key.indexOf('/')), key.substring(key.indexOf('/') + 1), artifact.url().trim(), artifact.sha256().trim().toLowerCase()));
        }
        return result;
    }

    private UpgradeArtifact resolveReleaseArtifact(String version, String key) {
        if (config.releaseBaseUrl().isBlank()) throw new IllegalArgumentException("artifact for " + key + " is required");
        var parts = key.split("/", 2);
        var releaseTag = config.releaseTagPrefix() + version;
        var stem = "remote-connect-mcp-agent-" + version + "-" + parts[0] + "-" + parts[1];
        var name = stem + ".zip";
        var url = config.releaseBaseUrl() + "/" + releaseTag + "/" + name;
        try {
            return new UpgradeArtifact(parts[0], parts[1], url, fetchChecksum(url + ".sha256"));
        } catch (Exception exception) {
            throw new IllegalArgumentException("cannot resolve release artifact for " + key + ": "
                    + compactError(exception.getMessage()), exception);
        }
    }

    private String fetchChecksum(String url) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(DOWNLOAD_TIMEOUT).header("Accept", "text/plain").GET().build();
        var future = http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.US_ASCII));
        HttpResponse<String> response;
        try { response = future.get(DOWNLOAD_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS); }
        catch (TimeoutException exception) { future.cancel(true); throw new IOException("checksum request timed out", exception); }
        catch (ExecutionException exception) { throw new IOException("checksum request failed", exception.getCause()); }
        if (response.statusCode() != 200) throw new IOException("checksum endpoint returned HTTP " + response.statusCode());
        if (!"https".equalsIgnoreCase(response.uri().getScheme())) throw new IOException("checksum endpoint redirected to a non-HTTPS URL");
        var value = response.body().trim().split("\\s+", 2)[0].toLowerCase();
        if (!SHA256.matcher(value).matches()) throw new IOException("checksum endpoint returned an invalid SHA-256");
        return value;
    }

    private static void validateArtifact(String key, UpgradeArtifact artifact) {
        if (artifact == null || artifact.url() == null || artifact.sha256() == null) throw new IllegalArgumentException("artifact for " + key + " is incomplete");
        var uri = URI.create(artifact.url().trim());
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) throw new IllegalArgumentException("artifact URL for " + key + " must use HTTPS");
        if (!SHA256.matcher(artifact.sha256().trim()).matches()) throw new IllegalArgumentException("artifact SHA-256 for " + key + " is invalid");
    }

    private static List<MachineView> selectMachines(List<String> requested, List<MachineView> machines,
                                                    boolean includeOffline) {
        if (requested == null || requested.isEmpty()) {
            // Offline Agents are deliberately included in the durable target
            // set by default.  They cannot receive an offer until their next
            // heartbeat, but the campaign remains resumable and the target
            // is not silently lost when a machine is temporarily powered off.
            return includeOffline ? List.copyOf(machines)
                    : machines.stream().filter(MachineView::online).toList();
        }
        var byId = machines.stream().collect(java.util.stream.Collectors.toMap(MachineView::id, value -> value));
        var result = new ArrayList<MachineView>();
        for (var raw : requested) {
            var id = raw == null ? "" : raw.trim();
            if (id.isBlank()) continue;
            var machine = byId.get(id);
            if (machine == null) throw new IllegalArgumentException("machine not found: " + id);
            if (result.stream().noneMatch(value -> value.id().equals(id))) result.add(machine);
        }
        return result;
    }

    private static int normalizePositive(Integer value, int defaultValue, int maximum) {
        var result = value == null || value <= 0 ? defaultValue : value;
        if (result > maximum) result = maximum;
        return result;
    }

    private static String normalizeVersion(String value) {
        var result = value == null ? "" : value.trim();
        if (!VERSION.matcher(result).matches()) throw new IllegalArgumentException("version must be a release tag such as v1.3.0");
        return result;
    }

    private static String platform(String os, String arch) {
        var normalizedOs = os == null ? "" : os.trim().toLowerCase();
        var normalizedArch = arch == null ? "" : arch.trim().toLowerCase();
        if (!("linux".equals(normalizedOs) || "windows".equals(normalizedOs)) || !("amd64".equals(normalizedArch) || "arm64".equals(normalizedArch))) {
            throw new IllegalArgumentException("unsupported Agent platform: " + normalizedOs + "/" + normalizedArch);
        }
        return normalizedOs + "/" + normalizedArch;
    }

    private static Map<String, MachineView> machinesById(List<MachineView> values) {
        return values.stream().collect(java.util.stream.Collectors.toMap(MachineView::id, value -> value));
    }

    private static Target target(Campaign campaign, String machineId) {
        return campaign.targets.stream().filter(value -> value.machineId.equals(machineId)).findFirst().orElse(null);
    }

    private Campaign requiredMemory(String id) {
        var value = memory.get(id);
        if (value == null) throw new IllegalArgumentException("upgrade campaign not found: " + id);
        return value;
    }

    private static String requiredId(String id) {
        var value = id == null ? "" : id.trim();
        if (value.isBlank() || !SAFE_COMPONENT.matcher(value).matches()) throw new IllegalArgumentException("campaign id is invalid");
        return value;
    }

    private static UpgradeCampaignView view(Campaign campaign) {
        var targets = campaign.targets.stream().map(value -> new UpgradeTargetView(value.machineId, value.status,
                value.error == null || value.error.isBlank() ? null : value.error, value.attempts, value.updatedAt,
                value.finishedAt, value.leaseUntil, value.componentStatuses)).toList();
        return new UpgradeCampaignView(campaign.id, campaign.version, campaign.status, campaign.canaryCount, campaign.batchSize,
                campaign.activeLimit, campaign.artifacts, campaign.componentPlans, targets, campaign.createdAt,
                campaign.updatedAt, campaign.finishedAt);
    }

    private static Map<String, UpgradeArtifact> readArtifacts(String value) {
        if (value == null || value.isBlank()) return Map.of();
        var records = JsonCodec.read(value.getBytes(StandardCharsets.UTF_8), UpgradeArtifact[].class);
        var result = new LinkedHashMap<String, UpgradeArtifact>();
        for (var artifact : records) if (artifact != null && artifact.os() != null && artifact.arch() != null) result.put(platform(artifact.os(), artifact.arch()), artifact);
        return result;
    }

    /**
     * Resolve optional desktop/browser plans once at campaign creation. The
     * resulting JSON is immutable, so a later GitHub release change cannot
     * mutate an in-flight campaign or make two machines receive different
     * bytes. Explicit request plans win over the published manifest.
     */
    private Map<String, List<UpgradeComponentPlan>> resolveComponentPlans(
            String version, List<MachineView> machines,
            Map<String, List<UpgradeComponentPlan>> supplied) {
        var result = new LinkedHashMap<String, List<UpgradeComponentPlan>>();
        var platforms = machines.stream().map(value -> platform(value.os(), value.arch())).distinct().toList();
        for (var key : platforms) {
            var plans = supplied == null ? null : supplied.get(key);
            if (plans == null && supplied != null) plans = supplied.get(key.toLowerCase(java.util.Locale.ROOT));
            if (plans == null && manifests != null) {
                var parts = key.split("/", 2);
                plans = manifests.components(version, parts[0], parts[1]);
            }
            if (plans == null || plans.isEmpty()) continue;
            var normalized = new ArrayList<UpgradeComponentPlan>();
            var names = new java.util.HashSet<String>();
            for (var plan : plans) {
                validateComponentPlan(key, plan);
                if (!names.add(plan.component())) throw new IllegalArgumentException("duplicate component plan: " + plan.component());
                normalized.add(plan);
            }
            result.put(key, List.copyOf(normalized));
        }
        return Map.copyOf(result);
    }

    private static void validateComponentPlan(String platform, UpgradeComponentPlan plan) {
        if (plan == null || !platform.equals(plan.os() + "/" + plan.arch())) {
            throw new IllegalArgumentException("component plan platform does not match " + platform);
        }
        if (plan.version().isBlank() || !VERSION.matcher(plan.version()).matches()) {
            throw new IllegalArgumentException("component version is invalid");
        }
        if (plan.url().isBlank() || plan.url().length() > 4096
                || !SHA256.matcher(plan.sha256()).matches()) {
            throw new IllegalArgumentException("component artifact is incomplete");
        }
        var uri = URI.create(plan.url());
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("component artifact URL must use HTTPS");
        }
        var path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(java.util.Locale.ROOT);
        if (!path.endsWith(".zip")) throw new IllegalArgumentException("component artifact must be a ZIP bundle");
        var policy = plan.restartPolicy().toLowerCase(java.util.Locale.ROOT);
        if (!("drain-and-restart".equals(policy) || "restart".equals(policy) || "manual".equals(policy))) {
            throw new IllegalArgumentException("unsupported component restart policy");
        }
        if (plan.bytes() != null && (plan.bytes() < 0 || plan.bytes() > 256L * 1024 * 1024)) {
            throw new IllegalArgumentException("component artifact exceeds 256 MiB");
        }
    }

    private static Map<String, List<UpgradeComponentPlan>> readComponentPlans(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            var raw = JsonCodec.read(value.getBytes(StandardCharsets.UTF_8), Map.class);
            if (raw == null || raw.isEmpty()) return Map.of();
            var result = new LinkedHashMap<String, List<UpgradeComponentPlan>>();
            raw.forEach((key, entry) -> {
                if (!(entry instanceof List<?> values)) return;
                var plans = values.stream()
                        .map(item -> JsonCodec.write(item))
                        .map(bytes -> JsonCodec.read(bytes, UpgradeComponentPlan.class))
                        .toList();
                result.put(String.valueOf(key).toLowerCase(java.util.Locale.ROOT), List.copyOf(plans));
            });
            return Map.copyOf(result);
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    private static Map<String, String> readStringMap(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            var raw = JsonCodec.read(value.getBytes(StandardCharsets.UTF_8), Map.class);
            if (raw == null || raw.isEmpty()) return Map.of();
            var result = new LinkedHashMap<String, String>();
            raw.forEach((key, entry) -> {
                if (key != null && entry != null) result.put(String.valueOf(key), String.valueOf(entry));
            });
            return Map.copyOf(result);
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    private static Instant instant(java.sql.ResultSet rs, String name) throws java.sql.SQLException {
        var value = rs.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }

    private static java.sql.Timestamp timestamp(Instant value) { return value == null ? null : java.sql.Timestamp.from(value); }

    private static String compactError(String value) {
        var result = value == null || value.isBlank() ? "upgrade failed" : SensitiveValueRedactor.redact(value.trim());
        return result.length() <= 4096 ? result : result.substring(0, 4096);
    }

    private void signalChange() {
        if (changes != null) changes.signalGlobal();
    }

    private void audit(String eventType, String actor, String agentId, String outcome, String detail) {
        if (audit != null) audit.record(eventType, actor, agentId, null, null, outcome, detail);
    }

    /** Wake only Agents participating in a campaign; no fleet-wide timer is needed. */
    private void signalTargets(List<UpgradeTargetView> targets) {
        if (wakes == null || targets == null) return;
        targets.stream().map(UpgradeTargetView::machineId).filter(Objects::nonNull)
                .filter(value -> !value.isBlank()).distinct().forEach(wakes::signal);
    }

    private static final class Campaign {
        private final String id;
        private final String version;
        private String status;
        private final int canaryCount;
        private final int batchSize;
        private int activeLimit;
        private final Map<String, UpgradeArtifact> artifacts;
        private final Map<String, List<UpgradeComponentPlan>> componentPlans;
        private final List<Target> targets;
        private final Instant createdAt;
        private Instant updatedAt;
        private Instant finishedAt;

        private Campaign(String id, String version, String status, int canaryCount, int batchSize, int activeLimit,
                         Map<String, UpgradeArtifact> artifacts, Map<String, List<UpgradeComponentPlan>> componentPlans,
                         List<Target> targets, Instant createdAt,
                         Instant updatedAt, Instant finishedAt) {
            this.id = id; this.version = version; this.status = status; this.canaryCount = canaryCount; this.batchSize = batchSize;
            this.activeLimit = activeLimit; this.artifacts = new LinkedHashMap<>(artifacts);
            this.componentPlans = componentPlans == null ? Map.of() : componentPlans.entrySet().stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,
                            entry -> entry.getValue() == null ? List.of() : List.copyOf(entry.getValue())));
            this.targets = targets;
            this.createdAt = createdAt; this.updatedAt = updatedAt; this.finishedAt = finishedAt;
        }
    }

    private static final class Target {
        private final String machineId;
        private String status;
        private String error;
        private int attempts;
        private Instant updatedAt;
        private Instant finishedAt;
        private Instant leaseUntil;
        private Map<String, String> componentStatuses;

        private Target(String machineId, String status, String error, int attempts, Instant updatedAt, Instant finishedAt, Instant leaseUntil) {
            this(machineId, status, error, attempts, updatedAt, finishedAt, leaseUntil, Map.of());
        }

        private Target(String machineId, String status, String error, int attempts, Instant updatedAt, Instant finishedAt, Instant leaseUntil,
                       Map<String, String> componentStatuses) {
            this.machineId = machineId; this.status = status; this.error = error == null ? "" : error; this.attempts = attempts;
            this.updatedAt = updatedAt; this.finishedAt = finishedAt; this.leaseUntil = leaseUntil;
            this.componentStatuses = componentStatuses == null ? Map.of() : Map.copyOf(componentStatuses);
        }
    }
}
