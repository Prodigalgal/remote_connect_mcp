package com.prodigalgal.remoteconnectmcp.center;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Admin-authenticated, low-cardinality Prometheus snapshot. */
@RestController
public final class MetricsController {
    private static final MediaType PROMETHEUS = MediaType.parseMediaType("text/plain; version=0.0.4; charset=utf-8");

    private final CenterTokenConfig tokens;
    private final AgentRegistry agents;
    private final TaskService tasks;
    private final UpgradeService upgrades;
    private final AuditService audit;
    private final CenterAsyncExecutor async;
    private final ArtifactTransferService transfers;

    public MetricsController(CenterTokenConfig tokens, AgentRegistry agents, TaskService tasks,
                             UpgradeService upgrades, AuditService audit, CenterAsyncExecutor async) {
        this(tokens, agents, tasks, upgrades, audit, async, null);
    }

    @Autowired
    public MetricsController(CenterTokenConfig tokens, AgentRegistry agents, TaskService tasks,
                             UpgradeService upgrades, AuditService audit, CenterAsyncExecutor async,
                             ArtifactTransferService transfers) {
        this.tokens = tokens;
        this.agents = agents;
        this.tasks = tasks;
        this.upgrades = upgrades;
        this.audit = audit;
        this.async = async;
        this.transfers = transfers;
    }

    @GetMapping(value = "/metrics", produces = "text/plain")
    public CompletableFuture<ResponseEntity<String>> metrics(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (!tokens.acceptsAdmin(bearerValue(authorization))) {
            return CompletableFuture.completedFuture(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header("WWW-Authenticate", "Bearer")
                    .contentType(PROMETHEUS)
                    .body("# invalid admin credentials\n"));
        }
        return async.submit(() -> ResponseEntity.ok()
                .contentType(PROMETHEUS)
                .header("Cache-Control", "no-store")
                .body(snapshot()));
    }

    private String snapshot() {
        var now = Instant.now();
        var builder = new StringBuilder(2048);
        var machinesTotal = agents.size();
        var machinesOnline = agents.onlineCount(now);
        line(builder, "remote_connect_mcp_machines_total", "Registered machines.", "gauge", machinesTotal);
        line(builder, "remote_connect_mcp_machines_online", "Online machines according to the heartbeat window.", "gauge", machinesOnline);
        lineDouble(builder, "remote_connect_mcp_machines_online_ratio",
                "Ratio of registered machines with a fresh heartbeat.",
                machinesTotal <= 0 ? 0.0 : (double) machinesOnline / machinesTotal);
        builder.append("# HELP remote_connect_mcp_tasks_total Persisted tasks by status.\n");
        builder.append("# TYPE remote_connect_mcp_tasks_total gauge\n");
        tasks.statusCounts().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> builder.append("remote_connect_mcp_tasks_total{status=\"")
                        .append(escapeLabel(entry.getKey())).append("\"} ").append(entry.getValue()).append('\n'));
        line(builder, "remote_connect_mcp_task_output_bytes_total", "Persisted task output bytes.", "counter", tasks.outputBytesTotal());
        var taskSlo = tasks.sloMetrics(now);
        line(builder, "remote_connect_mcp_tasks_queue_depth", "Queued tasks awaiting an Agent slot.", "gauge", taskSlo.queued());
        line(builder, "remote_connect_mcp_tasks_active", "Tasks currently dispatched, running or awaiting cancellation.", "gauge", taskSlo.active());
        line(builder, "remote_connect_mcp_tasks_terminal", "Tasks in a terminal state.", "gauge", taskSlo.terminal());
        line(builder, "remote_connect_mcp_tasks_oldest_queued_age_seconds", "Age of the oldest queued task.", "gauge", taskSlo.oldestQueuedAgeSeconds());
        line(builder, "remote_connect_mcp_tasks_expired_leases", "Active tasks whose lease is past its deadline.", "gauge", taskSlo.expiredLeases());
        lineDouble(builder, "remote_connect_mcp_tasks_success_ratio", "Completed tasks divided by terminal tasks.", ratio(taskSlo.completed(), taskSlo.terminal()));
        lineDouble(builder, "remote_connect_mcp_tasks_failure_ratio", "Failed tasks divided by terminal tasks.", ratio(taskSlo.failed(), taskSlo.terminal()));
        lineDouble(builder, "remote_connect_mcp_tasks_canceled_ratio", "Canceled tasks divided by terminal tasks.", ratio(taskSlo.canceled(), taskSlo.terminal()));
        line(builder, "remote_connect_mcp_artifacts_total", "Persisted task artifact objects.", "gauge", taskSlo.artifactObjects());
        line(builder, "remote_connect_mcp_artifact_bytes", "Bytes represented by persisted task artifact metadata.", "gauge", taskSlo.artifactBytes());
        if (transfers != null) {
            var transfer = transfers.transferMetrics();
            line(builder, "remote_connect_mcp_file_transfers_active", "File transfers currently pending, ready or delivering.", "gauge", transfer.active());
            line(builder, "remote_connect_mcp_file_transfers_delivered", "File transfers in delivered state.", "gauge", transfer.delivered());
            line(builder, "remote_connect_mcp_file_transfers_failed", "File transfers in failed state.", "gauge", transfer.failed());
            line(builder, "remote_connect_mcp_file_transfers_canceled", "File transfers in canceled state.", "gauge", transfer.canceled());
            line(builder, "remote_connect_mcp_file_transfer_bytes_transferred", "Bytes currently recorded as transferred.", "gauge", transfer.bytesTransferred());
            line(builder, "remote_connect_mcp_file_transfer_expected_bytes", "Bytes declared by file transfer metadata.", "gauge", transfer.expectedBytes());
            lineDouble(builder, "remote_connect_mcp_file_transfer_average_duration_seconds", "Average terminal file transfer duration.", transfer.averageDurationSeconds());
            lineDouble(builder, "remote_connect_mcp_file_transfer_max_duration_seconds", "Maximum terminal file transfer duration.", transfer.maxDurationSeconds());
            line(builder, "remote_connect_mcp_file_transfer_resume_count", "Resumable transfer resumes observed by this Center process.", "counter", transfer.resumeCount());
            line(builder, "remote_connect_mcp_file_transfer_partial_spool_bytes", "Bytes currently retained in resumable Center partial spools.", "gauge", transfer.partialSpoolBytes());
            line(builder, "remote_connect_mcp_file_transfer_gc_bytes_total", "Artifact bytes reclaimed by explicit GC in this Center process.", "counter", tasks.artifactGcBytes());
        }
        builder.append("# HELP remote_connect_mcp_upgrades_total Upgrade campaigns by status.\n");
        builder.append("# TYPE remote_connect_mcp_upgrades_total gauge\n");
        var upgradeCounts = upgrades.statusCounts();
        upgradeCounts.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> builder.append("remote_connect_mcp_upgrades_total{status=\"")
                        .append(escapeLabel(entry.getKey())).append("\"} ").append(entry.getValue()).append('\n'));
        var upgradeTerminal = upgradeCounts.getOrDefault("completed", 0L)
                + upgradeCounts.getOrDefault("failed", 0L)
                + upgradeCounts.getOrDefault("canceled", 0L);
        lineDouble(builder, "remote_connect_mcp_upgrades_failure_ratio", "Failed upgrade campaigns divided by terminal campaigns.",
                ratio(upgradeCounts.getOrDefault("failed", 0L), upgradeTerminal));
        if (audit != null) {
            line(builder, "remote_connect_mcp_audit_queue_depth", "Events waiting for the asynchronous audit writer.", "gauge", audit.queueDepth());
            line(builder, "remote_connect_mcp_audit_events_dropped_total", "Audit events dropped at the bounded queue boundary.", "counter", audit.droppedEvents());
            line(builder, "remote_connect_mcp_audit_persist_failures_total", "Audit event persistence failures.", "counter", audit.persistFailures());
        }
        return builder.toString();
    }

    private static void line(StringBuilder builder, String name, String help, String type, long value) {
        builder.append("# HELP ").append(name).append(' ').append(help).append('\n');
        builder.append("# TYPE ").append(name).append(' ').append(type).append('\n');
        builder.append(name).append(' ').append(value).append('\n');
    }

    private static void lineDouble(StringBuilder builder, String name, String help, double value) {
        builder.append("# HELP ").append(name).append(' ').append(help).append('\n');
        builder.append("# TYPE ").append(name).append(" gauge\n");
        builder.append(name).append(' ').append(Double.isFinite(value) ? value : 0.0).append('\n');
    }

    private static double ratio(long numerator, long denominator) {
        return denominator <= 0 ? 0.0 : Math.max(0.0, Math.min(1.0, (double) numerator / denominator));
    }

    private static String escapeLabel(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String bearerValue(String authorization) {
        if (authorization == null) return "";
        var parts = authorization.trim().split("\\s+", 2);
        return parts.length == 2 && "Bearer".equalsIgnoreCase(parts[0]) ? parts[1].trim() : "";
    }
}
