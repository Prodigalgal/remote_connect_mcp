package com.prodigalgal.remoteconnectmcp.center;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
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
    private final CenterAsyncExecutor async;

    public MetricsController(CenterTokenConfig tokens, AgentRegistry agents, TaskService tasks,
                             UpgradeService upgrades, CenterAsyncExecutor async) {
        this.tokens = tokens;
        this.agents = agents;
        this.tasks = tasks;
        this.upgrades = upgrades;
        this.async = async;
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
        line(builder, "remote_connect_mcp_machines_total", "Registered machines.", "gauge", agents.size());
        line(builder, "remote_connect_mcp_machines_online", "Online machines according to the heartbeat window.", "gauge", agents.onlineCount(now));
        builder.append("# HELP remote_connect_mcp_tasks_total Persisted tasks by status.\n");
        builder.append("# TYPE remote_connect_mcp_tasks_total gauge\n");
        tasks.statusCounts().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> builder.append("remote_connect_mcp_tasks_total{status=\"")
                        .append(escapeLabel(entry.getKey())).append("\"} ").append(entry.getValue()).append('\n'));
        line(builder, "remote_connect_mcp_task_output_bytes_total", "Persisted task output bytes.", "counter", tasks.outputBytesTotal());
        builder.append("# HELP remote_connect_mcp_upgrades_total Upgrade campaigns by status.\n");
        builder.append("# TYPE remote_connect_mcp_upgrades_total gauge\n");
        upgrades.statusCounts().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> builder.append("remote_connect_mcp_upgrades_total{status=\"")
                        .append(escapeLabel(entry.getKey())).append("\"} ").append(entry.getValue()).append('\n'));
        return builder.toString();
    }

    private static void line(StringBuilder builder, String name, String help, String type, long value) {
        builder.append("# HELP ").append(name).append(' ').append(help).append('\n');
        builder.append("# TYPE ").append(name).append(' ').append(type).append('\n');
        builder.append(name).append(' ').append(value).append('\n');
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
