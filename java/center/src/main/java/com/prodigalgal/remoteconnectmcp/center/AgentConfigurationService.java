package com.prodigalgal.remoteconnectmcp.center;

import com.prodigalgal.remoteconnectmcp.protocol.AgentConfigUpdate;
import com.prodigalgal.remoteconnectmcp.protocol.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stores and versions non-secret Agent runtime settings. The memory adapter
 * keeps protocol tests independent of PostgreSQL; production uses the
 * Liquibase-added columns on rcm_agent and updates them under a row lock. A
 * process selects one adapter and never mirrors settings into both stores.
 */
@Service
final class AgentConfigurationService {
    static final long DEFAULT_POLL_INTERVAL_MS = 5000L;
    static final int DEFAULT_MAX_CONCURRENCY = 1;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Map<String, AgentConfigUpdate> memory = new ConcurrentHashMap<>();

    @Autowired
    AgentConfigurationService(ObjectProvider<JdbcTemplate> jdbcProvider,
                              ObjectProvider<TransactionTemplate> transactionProvider) {
        this(jdbcProvider.getIfAvailable(), transactionProvider.getIfAvailable());
    }

    AgentConfigurationService(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    AgentConfigUpdate current(String machineId) {
        if (machineId == null || machineId.isBlank()) return null;
        if (jdbc == null) return memory.get(machineId.trim());
        return jdbc.query("SELECT config_generation, runtime_config FROM rcm_agent WHERE agent_id = ?",
                ps -> ps.setString(1, machineId.trim()), rs -> {
                    if (!rs.next()) return null;
                    var generation = rs.getLong("config_generation");
                    var json = rs.getString("runtime_config");
                    if (json == null || json.isBlank() || "{}".equals(json.trim())) return null;
                    try {
                        var decoded = JsonCodec.read(json.getBytes(StandardCharsets.UTF_8), AgentConfigUpdate.class);
                        return decoded.generation() == generation ? decoded : null;
                    } catch (RuntimeException ignored) {
                        return null;
                    }
                });
    }

    AgentConfigUpdate update(String machineId, AgentConfigUpdateRequest request) {
        if (machineId == null || machineId.isBlank()) throw new IllegalArgumentException("machineId is required");
        var normalized = machineId.trim();
        if (request == null) request = new AgentConfigUpdateRequest(null, null);
        if (jdbc == null) {
            final var requested = request;
            return memory.compute(normalized, (ignored, previous) -> next(previous, requested));
        }
        final var requested = request;
        return transactions.execute(status -> {
            var current = jdbc.query("SELECT config_generation, runtime_config FROM rcm_agent WHERE agent_id = ? FOR UPDATE",
                    ps -> ps.setString(1, normalized), rs -> {
                        if (!rs.next()) return null;
                        var generation = rs.getLong("config_generation");
                        var json = rs.getString("runtime_config");
                        AgentConfigUpdate decoded = null;
                        if (json != null && !json.isBlank() && !"{}".equals(json.trim())) {
                            try { decoded = JsonCodec.read(json.getBytes(StandardCharsets.UTF_8), AgentConfigUpdate.class); }
                            catch (RuntimeException ignored) { }
                        }
                        return decoded == null || decoded.generation() != generation
                                ? new AgentConfigUpdate(Math.max(0L, generation), 0, 0) : decoded;
                    });
            if (current == null) throw new IllegalArgumentException("machine not found");
            var next = next(current, requested);
            var json = new String(JsonCodec.write(next), StandardCharsets.UTF_8);
            jdbc.update("UPDATE rcm_agent SET config_generation = ?, runtime_config = CAST(? AS jsonb), updated_at = CURRENT_TIMESTAMP WHERE agent_id = ?",
                    next.generation(), json, normalized);
            return next;
        });
    }

    private static AgentConfigUpdate next(AgentConfigUpdate previous, AgentConfigUpdateRequest request) {
        var poll = request.pollIntervalMs() == null
                ? previous == null || previous.generation() == 0 ? DEFAULT_POLL_INTERVAL_MS : previous.pollIntervalMs()
                : request.pollIntervalMs();
        var concurrency = request.maxConcurrency() == null
                ? previous == null || previous.generation() == 0 ? DEFAULT_MAX_CONCURRENCY : previous.maxConcurrency()
                : request.maxConcurrency();
        var generation = previous == null ? 1L : Math.max(0L, previous.generation()) + 1L;
        return new AgentConfigUpdate(generation, poll, concurrency);
    }
}
