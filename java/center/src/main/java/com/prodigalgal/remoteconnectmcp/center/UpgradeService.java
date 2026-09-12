package com.prodigalgal.remoteconnectmcp.center;

import com.prodigalgal.remoteconnectmcp.protocol.JsonCodec;
import com.prodigalgal.remoteconnectmcp.protocol.PollRequest;
import com.prodigalgal.remoteconnectmcp.protocol.UpgradeArtifact;
import com.prodigalgal.remoteconnectmcp.protocol.UpgradePlan;
import com.prodigalgal.remoteconnectmcp.protocol.UpgradeStatusRequest;
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
 * Center-owned, canary-first Agent release orchestration. The memory adapter
 * keeps protocol tests self-contained; PostgreSQL mode uses the same state
 * machine and stores campaign/target rows transactionally.
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
    private static final Duration OFFER_LEASE = Duration.ofSeconds(30);
    private static final Duration STATUS_LEASE = Duration.ofMinutes(15);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(30);

    private final AgentRegistry agents;
    private final TaskService tasks;
    private final UpgradeConfig config;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final TaskChangeRegistry changes;
    private final AgentWakeRegistry wakes;
    private final Map<String, Campaign> memory = new ConcurrentHashMap<>();
    private final ReentrantLock memoryLock = new ReentrantLock();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    @Autowired
    public UpgradeService(AgentRegistry agents, TaskService tasks, UpgradeConfig config,
                           ObjectProvider<JdbcTemplate> jdbcProvider,
                           ObjectProvider<TransactionTemplate> transactionProvider,
                           ObjectProvider<TaskChangeRegistry> changeProvider,
                           ObjectProvider<AgentWakeRegistry> wakeProvider) {
        this.agents = agents;
        this.tasks = tasks;
        this.config = config;
        this.jdbc = jdbcProvider.getIfAvailable();
        this.transactions = transactionProvider.getIfAvailable();
        this.changes = changeProvider == null ? null : changeProvider.getIfAvailable();
        this.wakes = wakeProvider == null ? null : wakeProvider.getIfAvailable();
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
    }

    public UpgradeCampaignView create(CreateUpgradeCampaignRequest request) {
        if (!config.enabled()) throw new IllegalStateException("Agent upgrades are disabled");
        var version = normalizeVersion(request == null ? null : request.version());
        var machines = agents.listMachines(0, 200, Instant.now());
        var selected = selectMachines(request == null ? List.of() : request.machineIds(), machines);
        if (selected.isEmpty()) throw new IllegalArgumentException("at least one machine is required");
        var artifacts = resolveArtifacts(version, selected, request == null ? Map.of() : request.artifacts());
        var canary = normalizePositive(request == null ? null : request.canaryCount(), 1, selected.size());
        var batch = normalizePositive(request == null ? null : request.batchSize(), 3, 200);
        var now = Instant.now();
        var campaign = new Campaign("upgrade_" + UUID.randomUUID().toString().replace("-", ""), version,
                RUNNING, canary, batch, Math.min(canary, selected.size()), artifacts, new ArrayList<>(), now, now, null);
        for (var machine : selected) {
            var status = version.equals(machine.version()) ? COMPLETED : PENDING;
            var finished = COMPLETED.equals(status) ? now : null;
            campaign.targets.add(new Target(machine.id(), status, "", 0, now, finished, null));
        }
        if (jdbc != null) {
            var result = createJdbc(campaign);
            signalChange();
            signalTargets(result.targets());
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
                        target.leaseUntil = null;
                        target.finishedAt = null;
                        target.updatedAt = now;
                    }
                }
                campaign.status = RUNNING;
                campaign.finishedAt = null;
                reconcile(campaign, machinesById(agents.listMachines(0, 200, now)), now);
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
            return result;
        } finally {
            memoryLock.unlock();
        }
    }

    public UpgradeCampaignView updateStatus(String machineId, UpgradeStatusRequest request) {
        if (request == null) throw new IllegalArgumentException("upgrade status body is required");
        var id = requiredId(request.campaignId());
        var status = request.status() == null ? "" : request.status().trim().toLowerCase();
        if (!(DOWNLOADING.equals(status) || INSTALLING.equals(status) || FAILED.equals(status) || COMPLETED.equals(status))) {
            throw new IllegalArgumentException("invalid upgrade status: " + status);
        }
        if (jdbc != null) {
            var result = transactions.execute(tx -> updateStatusJdbc(machineId, id, status, request.error()));
            signalChange();
            signalTargets(result.targets());
            return result;
        }
        memoryLock.lock();
        try {
            var campaign = requiredMemory(id);
            var target = campaign.targets.stream().filter(value -> value.machineId.equals(machineId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("machine is not part of the upgrade campaign"));
            applyStatus(campaign, target, status, request.error(), Instant.now());
            reconcile(campaign, machinesById(agents.listMachines(0, 200, Instant.now())), Instant.now());
            var result = view(campaign);
            signalChange();
            signalTargets(result.targets());
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
            var byId = machinesById(agents.listMachines(0, 200, now));
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
        var campaign = loadActiveJdbc();
        if (campaign == null) return null;
        reconcileJdbc(campaign, now);
        var target = target(campaign, machine.id());
        if (target == null) return null;
        if (COMPLETED.equals(target.status)) return null;
        if (machine.version() != null && machine.version().equals(campaign.version)) {
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
        return new UpgradePlan(campaign.id, campaign.version, artifact.url(), artifact.sha256());
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
            jdbc.update("""
                    INSERT INTO rcm_upgrade_campaign(campaign_id, version, status, canary_count, batch_size, active_limit,
                        artifacts, created_at, updated_at, finished_at)
                    VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
                    """, campaign.id, campaign.version, campaign.status, campaign.canaryCount, campaign.batchSize,
                    campaign.activeLimit, artifacts, timestamp(campaign.createdAt), timestamp(campaign.updatedAt), timestamp(campaign.finishedAt));
            for (int index = 0; index < campaign.targets.size(); index++) {
                var target = campaign.targets.get(index);
                jdbc.update("""
                        INSERT INTO rcm_upgrade_target(campaign_id, target_index, agent_id, status, error_text, attempts,
                            updated_at, finished_at, lease_until)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, campaign.id, index, target.machineId, target.status, target.error, target.attempts,
                        timestamp(target.updatedAt), timestamp(target.finishedAt), timestamp(target.leaseUntil));
            }
            return view(campaign);
        });
    }

    private UpgradeCampaignView controlJdbc(String id, String action) {
        var campaign = requiredJdbc(id);
        var now = Instant.now();
        if ("resume".equals(action)) {
            if (COMPLETED.equals(campaign.status) || CANCELED.equals(campaign.status)) throw new IllegalArgumentException("upgrade campaign is already terminal");
            for (var target : campaign.targets) {
                if (FAILED.equals(target.status)) {
                    target.status = PENDING; target.error = ""; target.leaseUntil = null; target.finishedAt = null; target.updatedAt = now;
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

    private UpgradeCampaignView updateStatusJdbc(String machineId, String id, String status, String error) {
        var campaign = requiredJdbc(id);
        var target = target(campaign, machineId);
        if (target == null) throw new IllegalArgumentException("machine is not part of the upgrade campaign");
        applyStatus(campaign, target, status, error, Instant.now());
        updateTargetJdbc(campaign.id, target);
        reconcileJdbc(campaign, Instant.now());
        updateCampaignJdbc(campaign);
        return view(campaign);
    }

    private void reconcileJdbc(Campaign campaign, Instant now) {
        var machines = machinesById(agents.listMachines(0, 200, now));
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
                UPDATE rcm_upgrade_target SET status = ?, error_text = ?, attempts = ?, updated_at = ?, finished_at = ?, lease_until = ?
                 WHERE campaign_id = ? AND agent_id = ?
                """, target.status, target.error, target.attempts, timestamp(target.updatedAt), timestamp(target.finishedAt),
                timestamp(target.leaseUntil), campaignId, target.machineId);
    }

    private void updateCampaignJdbc(Campaign campaign) {
        jdbc.update("UPDATE rcm_upgrade_campaign SET status = ?, active_limit = ?, updated_at = ?, finished_at = ? WHERE campaign_id = ?",
                campaign.status, campaign.activeLimit, timestamp(campaign.updatedAt), timestamp(campaign.finishedAt), campaign.id);
    }

    private Campaign loadActiveJdbc() {
        var ids = jdbc.query("SELECT campaign_id FROM rcm_upgrade_campaign WHERE status = ? ORDER BY created_at LIMIT 1",
                ps -> ps.setString(1, RUNNING), (rs, row) -> rs.getString(1));
        return ids.isEmpty() ? null : loadJdbc(ids.get(0));
    }

    private Campaign requiredJdbc(String id) {
        var value = loadJdbc(id);
        if (value == null) throw new IllegalArgumentException("upgrade campaign not found: " + id);
        return value;
    }

    private Campaign loadJdbc(String id) {
        var rows = jdbc.query("SELECT campaign_id, version, status, canary_count, batch_size, active_limit, artifacts, created_at, updated_at, finished_at FROM rcm_upgrade_campaign WHERE campaign_id = ?",
                ps -> ps.setString(1, id), (rs, row) -> {
                    var artifacts = readArtifacts(rs.getString("artifacts"));
                    return new Campaign(rs.getString("campaign_id"), rs.getString("version"), rs.getString("status"),
                            rs.getInt("canary_count"), rs.getInt("batch_size"), rs.getInt("active_limit"), artifacts,
                            new ArrayList<>(), instant(rs, "created_at"), instant(rs, "updated_at"), instant(rs, "finished_at"));
                });
        if (rows.isEmpty()) return null;
        var campaign = rows.get(0);
        campaign.targets.addAll(jdbc.query("SELECT agent_id, status, error_text, attempts, updated_at, finished_at, lease_until FROM rcm_upgrade_target WHERE campaign_id = ? ORDER BY target_index",
                ps -> ps.setString(1, id), (rs, row) -> new Target(rs.getString("agent_id"), rs.getString("status"),
                        rs.getString("error_text"), rs.getInt("attempts"), instant(rs, "updated_at"), instant(rs, "finished_at"), instant(rs, "lease_until"))));
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

    private void reconcile(Campaign campaign, Map<String, MachineView> machines, Instant now) {
        if (CANCELED.equals(campaign.status)) return;
        var allCompleted = true;
        for (var target : campaign.targets) {
            var machine = machines.get(target.machineId);
            if (machine != null && campaign.version.equals(machine.version()) && !COMPLETED.equals(target.status)) {
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
        // Native Image releases are bundles: the executable and generated
        // Windows DLLs/Linux shared objects must be upgraded together. Keep a
        // legacy raw executable fallback so older releases remain upgradeable
        // while new tags use the safer flat ZIP asset.
        var names = List.of(stem + ".zip", stem + ("windows".equals(parts[0]) ? ".exe" : ""));
        Exception last = null;
        for (var name : names) {
            var url = config.releaseBaseUrl() + "/" + releaseTag + "/" + name;
            try {
                var checksum = fetchChecksum(url + ".sha256");
                return new UpgradeArtifact(parts[0], parts[1], url, checksum);
            } catch (Exception exception) {
                last = exception;
            }
        }
        throw new IllegalArgumentException("cannot resolve release artifact for " + key + ": "
                + compactError(last == null ? "release asset is unavailable" : last.getMessage()), last);
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

    private static List<MachineView> selectMachines(List<String> requested, List<MachineView> machines) {
        if (requested == null || requested.isEmpty()) return machines.stream().filter(MachineView::online).toList();
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

    private static int normalizePositive(Integer value, int fallback, int maximum) {
        var result = value == null || value <= 0 ? fallback : value;
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
                value.finishedAt, value.leaseUntil)).toList();
        return new UpgradeCampaignView(campaign.id, campaign.version, campaign.status, campaign.canaryCount, campaign.batchSize,
                campaign.activeLimit, campaign.artifacts, targets, campaign.createdAt, campaign.updatedAt, campaign.finishedAt);
    }

    private static Map<String, UpgradeArtifact> readArtifacts(String value) {
        if (value == null || value.isBlank()) return Map.of();
        var records = JsonCodec.read(value.getBytes(StandardCharsets.UTF_8), UpgradeArtifact[].class);
        var result = new LinkedHashMap<String, UpgradeArtifact>();
        for (var artifact : records) if (artifact != null && artifact.os() != null && artifact.arch() != null) result.put(platform(artifact.os(), artifact.arch()), artifact);
        return result;
    }

    private static Instant instant(java.sql.ResultSet rs, String name) throws java.sql.SQLException {
        var value = rs.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }

    private static java.sql.Timestamp timestamp(Instant value) { return value == null ? null : java.sql.Timestamp.from(value); }

    private static String compactError(String value) {
        var result = value == null || value.isBlank() ? "upgrade failed" : value.trim();
        return result.length() <= 4096 ? result : result.substring(0, 4096);
    }

    private void signalChange() {
        if (changes != null) changes.signalGlobal();
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
        private final List<Target> targets;
        private final Instant createdAt;
        private Instant updatedAt;
        private Instant finishedAt;

        private Campaign(String id, String version, String status, int canaryCount, int batchSize, int activeLimit,
                         Map<String, UpgradeArtifact> artifacts, List<Target> targets, Instant createdAt,
                         Instant updatedAt, Instant finishedAt) {
            this.id = id; this.version = version; this.status = status; this.canaryCount = canaryCount; this.batchSize = batchSize;
            this.activeLimit = activeLimit; this.artifacts = new LinkedHashMap<>(artifacts); this.targets = targets;
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

        private Target(String machineId, String status, String error, int attempts, Instant updatedAt, Instant finishedAt, Instant leaseUntil) {
            this.machineId = machineId; this.status = status; this.error = error == null ? "" : error; this.attempts = attempts;
            this.updatedAt = updatedAt; this.finishedAt = finishedAt; this.leaseUntil = leaseUntil;
        }
    }
}
