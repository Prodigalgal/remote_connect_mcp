package com.prodigalgal.remoteconnectmcp.center;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.prodigalgal.remoteconnectmcp.protocol.ExecutionContract;
import com.prodigalgal.remoteconnectmcp.protocol.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Durable lifecycle record for one principal/connection execution session.
 * Tasks still carry their own immutable session and result-channel fields;
 * this service stores the latest bounded capability/contract projection so a
 * reconnect can recover context without replaying a whole conversation.
 */
@Service
public final class ExecutionSessionService {
    private static final int MAX_ID = 256;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final McpQuotaService quota;
    private final Map<String, SessionState> memory = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public ExecutionSessionService(ObjectProvider<JdbcTemplate> jdbcProvider,
                                   ObjectProvider<TransactionTemplate> transactionProvider,
                                   ObjectProvider<McpQuotaService> quotaProvider) {
        this(jdbcProvider == null ? null : jdbcProvider.getIfAvailable(),
                transactionProvider == null ? null : transactionProvider.getIfAvailable(),
                quotaProvider == null ? null : quotaProvider.getIfAvailable());
    }

    ExecutionSessionService() {
        this((JdbcTemplate) null, (TransactionTemplate) null, null);
    }

    ExecutionSessionService(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this(jdbc, transactions, null);
    }

    ExecutionSessionService(JdbcTemplate jdbc, TransactionTemplate transactions, McpQuotaService quota) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.quota = quota;
    }

    /** Create or touch a session atomically with its latest Center contract. */
    public SessionView ensure(TaskOrigin origin, ExecutionContract contract) {
        if (contract == null) throw new IllegalArgumentException("execution contract is required");
        var principal = principal(origin);
        var sessionId = required(contract.sessionId(), "session_id");
        var conversationId = required(origin == null ? null : origin.connectionId(), "conversation_id");
        var now = Instant.now();
        expireStale(now);
        var existingBefore = jdbc == null ? memory.get(key(principal, sessionId)) : null;
        var existingReservation = existingBefore != null && "active".equals(existingBefore.status())
                && existingBefore.expiresAt() != null && now.isBefore(existingBefore.expiresAt());
        if (quota != null) quota.assertSessionAdmission(origin, sessionId);
        var state = new SessionState(principal, sessionId, conversationId, contract.machineId(),
                contract, "active", now, contract.expiresAt(), now, now);
        try {
            if (jdbc == null) {
                var existing = memory.get(key(principal, sessionId));
                if (existing != null && "active".equals(existing.status())
                        && existing.expiresAt() != null && now.isBefore(existing.expiresAt())
                        && !conversationId.equals(existing.conversationId())) {
                    throw new SecurityException("execution session is already owned by another conversation");
                }
                // The compatibility principal represents the pre-session
                // internal API. It intentionally multiplexes legacy tasks;
                // authenticated principals remain contract-pinned.
                if (!TaskOrigin.SHARED_PRINCIPAL.equals(principal)) {
                    assertContractCompatibility(existing == null ? null : existing.contract(), contract);
                }
                memory.put(key(principal, sessionId), state);
            } else {
                var contractJson = new String(JsonCodec.write(contract), StandardCharsets.UTF_8);
                runInTransaction(() -> {
                    var existing = existingSessionForUpdate(principal, sessionId);
                    if (existing != null && "active".equals(existing.status())
                            && existing.expiresAt() != null && now.isBefore(existing.expiresAt())
                            && !conversationId.equals(existing.conversationId())) {
                        throw new SecurityException("execution session is already owned by another conversation");
                    }
                    // See the in-memory path above: only the compatibility
                    // principal may reuse one session across legacy contracts.
                    if (!TaskOrigin.SHARED_PRINCIPAL.equals(principal)) {
                        assertContractCompatibility(existing == null ? null : existing.contract(), contract);
                    }
                    jdbc.update("""
                            INSERT INTO rcm_execution_session(session_id, principal_id, conversation_id, machine_id,
                                                              contract_json, status, last_seen_at, expires_at, created_at, updated_at)
                            VALUES (?, ?, ?, ?, CAST(? AS jsonb), 'active', ?, ?, ?, ?)
                            ON CONFLICT (principal_id, session_id) DO UPDATE SET conversation_id = EXCLUDED.conversation_id,
                                machine_id = EXCLUDED.machine_id, contract_json = EXCLUDED.contract_json,
                                status = 'active', last_seen_at = EXCLUDED.last_seen_at,
                                expires_at = EXCLUDED.expires_at, updated_at = EXCLUDED.updated_at
                            """, sessionId, principal, conversationId, contract.machineId(), contractJson,
                            timestamp(now), timestamp(contract.expiresAt()), timestamp(now), timestamp(now));
                });
            }
        } catch (RuntimeException | Error failure) {
            if (quota != null && !existingReservation) quota.releaseSession(origin, sessionId);
            throw failure;
        }
        return view(state);
    }

    /** Close one session for its owning principal; no timer is involved. */
    public boolean close(TaskOrigin origin, String sessionId) {
        var principal = principal(origin);
        var session = required(sessionId, "session_id");
        var existing = memory.get(key(principal, session));
        var changed = existing != null && !"closed".equals(existing.status());
        if (changed) memory.put(key(principal, session), existing.withStatus("closed", Instant.now()));
        if (jdbc != null) {
            try {
                changed |= jdbc.update("""
                        UPDATE rcm_execution_session SET status = 'closed', updated_at = CURRENT_TIMESTAMP
                         WHERE principal_id = ? AND session_id = ? AND status <> 'closed'
                        """, principal, session) > 0;
            } catch (DataAccessException failure) {
                throw new IllegalStateException("execution session storage is unavailable", failure);
            }
        }
        if (changed && quota != null) quota.releaseSession(origin, session);
        return changed;
    }

    public List<SessionView> list(String principalId, int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 200) throw new IllegalArgumentException("invalid session page");
        var principal = principalId == null || principalId.isBlank() ? "" : required(principalId, "principal_id");
        expireStale(Instant.now());
        if (jdbc == null) {
            return memory.values().stream()
                    .filter(value -> principal.isBlank() || value.principalId().equals(principal))
                    .sorted(java.util.Comparator.comparing(SessionState::lastSeenAt).reversed())
                    .skip(offset).limit(limit).map(this::view).toList();
        }
        var sql = """
                SELECT session_id, principal_id, conversation_id, machine_id,
                       CASE WHEN status = 'active' AND expires_at <= CURRENT_TIMESTAMP
                            THEN 'expired' ELSE status END AS status,
                       last_seen_at, expires_at, created_at, updated_at,
                       contract_json ->> 'capability' AS capability,
                       contract_json ->> 'workspace_policy' AS workspace_policy,
                       contract_json ->> 'lane_mode' AS lane_mode
                  FROM rcm_execution_session %s
                 ORDER BY last_seen_at DESC, session_id OFFSET ? LIMIT ?
                """.formatted(principal.isBlank() ? "" : "WHERE principal_id = ?");
        return jdbc.query(sql, ps -> {
            var index = 1;
            if (!principal.isBlank()) ps.setString(index++, principal);
            ps.setInt(index++, offset);
            ps.setInt(index, limit);
        }, (rs, row) -> new SessionView(rs.getString("principal_id"), rs.getString("session_id"),
                rs.getString("conversation_id"), rs.getString("machine_id"), rs.getString("status"),
                instant(rs.getTimestamp("last_seen_at")), instant(rs.getTimestamp("expires_at")),
                        instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")),
                 rs.getString("capability"), rs.getString("workspace_policy"), rs.getString("lane_mode")));
    }

    public int count(String principalId) {
        var principal = principalId == null || principalId.isBlank() ? "" : required(principalId, "principal_id");
        expireStale(Instant.now());
        if (jdbc == null) {
            return Math.toIntExact(memory.values().stream()
                    .filter(value -> principal.isBlank() || value.principalId().equals(principal)).count());
        }
        var sql = principal.isBlank() ? "SELECT COUNT(*) FROM rcm_execution_session"
                : "SELECT COUNT(*) FROM rcm_execution_session WHERE principal_id = ?";
        var value = principal.isBlank() ? jdbc.queryForObject(sql, Long.class)
                : jdbc.queryForObject(sql, Long.class, principal);
        return value == null ? 0 : Math.toIntExact(value);
    }

    private void runInTransaction(Runnable operation) {
        if (transactions == null) operation.run();
        else transactions.executeWithoutResult(status -> operation.run());
    }

    private static String key(String principal, String session) {
        return principal + '\u0000' + session;
    }

    private static String principal(TaskOrigin origin) {
        return required(origin == null ? null : origin.principalId(), "principal_id");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        var normalized = value.trim();
        if (normalized.length() > MAX_ID || normalized.indexOf('\u0000') >= 0
                || normalized.indexOf('\r') >= 0 || normalized.indexOf('\n') >= 0
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static java.sql.Timestamp timestamp(Instant value) {
        return value == null ? null : java.sql.Timestamp.from(value);
    }

    private static Instant instant(java.sql.Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private SessionView view(SessionState value) {
        return new SessionView(value.principalId(), value.sessionId(), value.conversationId(), value.machineId(),
                value.status(), value.lastSeenAt(), value.expiresAt(), value.createdAt(), value.updatedAt(),
                value.contract() == null ? null : value.contract().capability(),
                value.contract() == null || value.contract().workspacePolicy() == null
                        ? null : value.contract().workspacePolicy().wireValue(),
                value.contract() == null || value.contract().laneMode() == null
                        ? null : value.contract().laneMode().wireValue());
    }

    /**
     * Authorize a task operation against the owning execution session.  This
     * is deliberately an access-time check rather than a timer: expired
     * sessions are reclaimed when a request touches them, so idle hosts do
     * not pay a polling cost.
     */
    public void authorize(TaskOrigin origin, String sessionId, String requiredCapability) {
        var principal = principal(origin);
        if (TaskOrigin.SHARED_PRINCIPAL.equals(principal)) return;
        var session = required(sessionId, "session_id");
        var now = Instant.now();
        expireStale(now);
        if (jdbc == null) {
            var state = memory.get(key(principal, session));
            if (state == null || !"active".equals(state.status())
                    || state.expiresAt() == null || !now.isBefore(state.expiresAt())) {
                throw new SecurityException("execution session is expired or unavailable");
            }
            if (origin != null && !state.conversationId().equals(origin.connectionId())) {
                throw new SecurityException("execution session belongs to another conversation");
            }
            if (requiredCapability != null && state.contract() != null
                    && !requiredCapability.equals(state.contract().capability())) {
                throw new SecurityException("execution session capability does not match task");
            }
            return;
        }
        var allowed = jdbc.query("""
                SELECT EXISTS (
                    SELECT 1 FROM rcm_execution_session
                     WHERE principal_id = ? AND session_id = ? AND status = 'active'
                       AND conversation_id = ?
                       AND expires_at > CURRENT_TIMESTAMP
                       AND (? = '' OR contract_json ->> 'capability' = ?)
                )
                """, ps -> {
            ps.setString(1, principal);
            ps.setString(2, session);
            ps.setString(3, required(origin == null ? null : origin.connectionId(), "conversation_id"));
            var capability = requiredCapability == null ? "" : requiredCapability;
            ps.setString(4, capability);
            ps.setString(5, capability);
        }, rs -> rs.next() && rs.getBoolean(1));
        if (!Boolean.TRUE.equals(allowed)) throw new SecurityException("execution session is expired or unavailable");
    }

    /**
     * Expire in-memory rows on access.  JDBC rows are intentionally not
     * rewritten here: every request that needs authorization already applies
     * the expiry predicate, while list() projects the effective status.  A
     * full-table UPDATE on every MCP request would be a hidden polling loop and
     * would create avoidable write/lock pressure as the session table grows.
     */
    private int expireStale(Instant now) {
        if (jdbc != null) return 0;
        var changed = 0;
        memory.replaceAll((key, value) -> {
            if ("active".equals(value.status()) && value.expiresAt() != null && !now.isBefore(value.expiresAt())) {
                if (quota != null) quota.releaseSession(new TaskOrigin(value.principalId(), "session-expiry", value.conversationId()), value.sessionId());
                return value.withStatus("expired", now);
            }
            return value;
        });
        return changed;
    }

    private ExistingSession existingSessionForUpdate(String principal, String sessionId) {
        var existing = jdbc.query("""
                SELECT conversation_id, status, expires_at, contract_json FROM rcm_execution_session
                 WHERE principal_id = ? AND session_id = ?
                 FOR UPDATE
                """, ps -> { ps.setString(1, principal); ps.setString(2, sessionId); }, (rs, row) -> new ExistingSession(
                rs.getString("conversation_id"), rs.getString("status"), instant(rs.getTimestamp("expires_at")),
                readContract(rs.getString("contract_json"))));
        return existing.isEmpty() ? null : existing.getFirst();
    }

    private static ExecutionContract readContract(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return JsonCodec.read(value.getBytes(StandardCharsets.UTF_8), ExecutionContract.class);
        } catch (RuntimeException exception) {
            // A malformed persisted contract is not equivalent to a legacy
            // row with no contract.  Failing the request here prevents a
            // corrupted session from becoming an implicit authorization
            // grant after a restart.
            throw new IllegalStateException("stored execution session contract is invalid", exception);
        }
    }

    private static void assertContractCompatibility(ExecutionContract existing, ExecutionContract requested) {
        if (existing == null || requested == null) return;
        if (!java.util.Objects.equals(existing.machineId(), requested.machineId())
                || existing.scopeMode() != requested.scopeMode()
                || !java.util.Objects.equals(existing.projectId(), requested.projectId())
                || !java.util.Objects.equals(existing.worktreeId(), requested.worktreeId())
                || !java.util.Objects.equals(existing.scopeRoot(), requested.scopeRoot())
                || existing.workspacePolicy() != requested.workspacePolicy()
                || existing.laneMode() != requested.laneMode()
                || !java.util.Objects.equals(existing.capability(), requested.capability())) {
            throw new SecurityException("execution session contract cannot change while active");
        }
    }

    private record SessionState(String principalId, String sessionId, String conversationId, String machineId,
                                ExecutionContract contract, String status, Instant lastSeenAt,
                                Instant expiresAt, Instant createdAt, Instant updatedAt) {
        SessionState withStatus(String nextStatus, Instant now) {
            return new SessionState(principalId, sessionId, conversationId, machineId, contract,
                    nextStatus, lastSeenAt, expiresAt, createdAt, now);
        }
    }

    public record SessionView(@JsonProperty("principal_id") String principalId,
                              @JsonProperty("session_id") String sessionId,
                              @JsonProperty("conversation_id") String conversationId,
                              @JsonProperty("machine_id") String machineId,
                              String status,
                              @JsonProperty("last_seen_at") Instant lastSeenAt,
                              @JsonProperty("expires_at") Instant expiresAt,
                              @JsonProperty("created_at") Instant createdAt,
                              @JsonProperty("updated_at") Instant updatedAt,
                              String capability,
                              @JsonProperty("workspace_policy") String workspacePolicy,
                              @JsonProperty("lane_mode") String laneMode) {
    }

    private record ExistingSession(String conversationId, String status, Instant expiresAt,
                                   ExecutionContract contract) { }
}
