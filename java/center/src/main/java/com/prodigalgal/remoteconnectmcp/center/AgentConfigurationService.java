package com.prodigalgal.remoteconnectmcp.center;

import com.prodigalgal.remoteconnectmcp.protocol.AgentConfigUpdate;
import com.prodigalgal.remoteconnectmcp.protocol.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
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
    private static final int MAX_HISTORY = 16;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Map<String, AgentConfigUpdate> memory = new ConcurrentHashMap<>();
    private final Map<String, ArrayDeque<AgentConfigUpdate>> memoryHistory = new ConcurrentHashMap<>();

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
                    } catch (RuntimeException invalid) {
                        throw new IllegalStateException("stored Agent runtime configuration is invalid", invalid);
                    }
                });
    }

    AgentConfigUpdate update(String machineId, AgentConfigUpdateRequest request) {
        if (machineId == null || machineId.isBlank()) throw new IllegalArgumentException("machineId is required");
        var normalized = machineId.trim();
        if (request == null) request = new AgentConfigUpdateRequest(null, null);
        if (jdbc == null) {
            final var requested = request;
            return memory.compute(normalized, (ignored, previous) -> {
                assertExpectedGeneration(requested, previous == null ? 0L : previous.generation());
                remember(normalized, previous);
                return next(previous, requested);
            });
        }
        final var requested = request;
        java.util.function.Supplier<AgentConfigUpdate> operation = () -> {
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
                                ? defaultConfig(generation) : decoded;
                    });
            if (current == null) throw new IllegalArgumentException("machine not found");
            assertExpectedGeneration(requested, current.generation());
            rememberJdbc(normalized, current);
            var next = next(current, requested);
            var json = new String(JsonCodec.write(next), StandardCharsets.UTF_8);
            jdbc.update("UPDATE rcm_agent SET config_generation = ?, runtime_config = CAST(? AS jsonb), updated_at = CURRENT_TIMESTAMP WHERE agent_id = ?",
                    next.generation(), json, normalized);
            return next;
        };
        return transactions == null ? operation.get() : transactions.execute(status -> operation.get());
    }

    /**
     * Publish the previous runtime configuration as a new generation.  The
     * Agent only accepts monotonically increasing generations, so rollback is
     * safe while an older heartbeat is in flight and does not require a
     * restart or a second control channel.
     */
    AgentConfigUpdate rollback(String machineId) {
        if (machineId == null || machineId.isBlank()) throw new IllegalArgumentException("machineId is required");
        var normalized = machineId.trim();
        if (jdbc == null) {
            return memory.compute(normalized, (ignored, current) -> {
                if (current == null) throw new IllegalArgumentException("machine has no runtime configuration");
                var history = memoryHistory.get(normalized);
                if (history == null || history.isEmpty()) throw new IllegalArgumentException("machine has no previous runtime configuration");
                var previous = history.removeLast();
                return new AgentConfigUpdate(current.generation() + 1, previous.pollIntervalMs(), previous.maxConcurrency());
            });
        }
        java.util.function.Supplier<AgentConfigUpdate> operation = () -> {
            var current = currentForUpdate(normalized);
            if (current == null) throw new IllegalArgumentException("machine not found");
            var previous = jdbc.query("""
                    SELECT config_generation, poll_interval_ms, max_concurrency
                      FROM rcm_agent_config_history
                     WHERE agent_id = ? ORDER BY config_generation DESC LIMIT 1
                     FOR UPDATE
                    """, ps -> ps.setString(1, normalized), rs -> {
                if (!rs.next()) return null;
                return new HistoryEntry(rs.getLong("config_generation"), rs.getLong("poll_interval_ms"), rs.getInt("max_concurrency"));
            });
            if (previous == null) throw new IllegalArgumentException("machine has no previous runtime configuration");
            jdbc.update("DELETE FROM rcm_agent_config_history WHERE agent_id = ? AND config_generation = ?",
                    normalized, previous.generation());
            var next = new AgentConfigUpdate(current.generation() + 1, previous.pollIntervalMs(), previous.maxConcurrency());
            var json = new String(JsonCodec.write(next), StandardCharsets.UTF_8);
            jdbc.update("UPDATE rcm_agent SET config_generation = ?, runtime_config = CAST(? AS jsonb), updated_at = CURRENT_TIMESTAMP WHERE agent_id = ?",
                    next.generation(), json, normalized);
            return next;
        };
        return transactions == null ? operation.get() : transactions.execute(status -> operation.get());
    }

    private void remember(String machineId, AgentConfigUpdate previous) {
        if (previous == null || previous.generation() <= 0) return;
        var history = memoryHistory.computeIfAbsent(machineId, ignored -> new ArrayDeque<>());
        synchronized (history) {
            history.addLast(previous);
            while (history.size() > MAX_HISTORY) history.removeFirst();
        }
    }

    private void rememberJdbc(String machineId, AgentConfigUpdate previous) {
        if (previous == null || previous.generation() <= 0) return;
        jdbc.update("""
                INSERT INTO rcm_agent_config_history(agent_id, config_generation, poll_interval_ms, max_concurrency)
                VALUES (?, ?, ?, ?) ON CONFLICT (agent_id, config_generation) DO NOTHING
                """, machineId, previous.generation(), previous.pollIntervalMs(), previous.maxConcurrency());
        jdbc.update("""
                DELETE FROM rcm_agent_config_history
                 WHERE agent_id = ? AND config_generation < ?
                """, machineId, Math.max(0L, previous.generation() - MAX_HISTORY + 1));
    }

    private AgentConfigUpdate currentForUpdate(String machineId) {
        return jdbc.query("SELECT config_generation, runtime_config FROM rcm_agent WHERE agent_id = ? FOR UPDATE",
                ps -> ps.setString(1, machineId), rs -> {
                    if (!rs.next()) return null;
                    var generation = rs.getLong("config_generation");
                    var json = rs.getString("runtime_config");
                    if (json == null || json.isBlank() || "{}".equals(json.trim())) {
                        return defaultConfig(generation);
                    }
                    try {
                        var decoded = JsonCodec.read(json.getBytes(StandardCharsets.UTF_8), AgentConfigUpdate.class);
                        return decoded.generation() == generation ? decoded
                                : defaultConfig(generation);
                    } catch (RuntimeException invalid) {
                        throw new IllegalStateException("stored Agent runtime configuration is invalid", invalid);
                    }
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

    private static AgentConfigUpdate defaultConfig(long generation) {
        var normalized = Math.max(0L, generation);
        return normalized == 0L
                ? AgentConfigUpdate.defaults()
                : new AgentConfigUpdate(normalized, DEFAULT_POLL_INTERVAL_MS, DEFAULT_MAX_CONCURRENCY);
    }

    private static void assertExpectedGeneration(AgentConfigUpdateRequest request, long currentGeneration) {
        if (request != null && request.expectedGeneration() != null
                && request.expectedGeneration() != currentGeneration) {
            throw new IllegalArgumentException("configuration changed; expected_generation="
                    + request.expectedGeneration() + ", current_generation=" + currentGeneration);
        }
    }

    private record HistoryEntry(long generation, long pollIntervalMs, int maxConcurrency) {
    }
}
