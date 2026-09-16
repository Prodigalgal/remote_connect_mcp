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
    private final Map<String, SessionState> memory = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public ExecutionSessionService(ObjectProvider<JdbcTemplate> jdbcProvider,
                                   ObjectProvider<TransactionTemplate> transactionProvider) {
        this(jdbcProvider == null ? null : jdbcProvider.getIfAvailable(),
                transactionProvider == null ? null : transactionProvider.getIfAvailable());
    }

    ExecutionSessionService() {
        this((JdbcTemplate) null, (TransactionTemplate) null);
    }

    ExecutionSessionService(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    /** Create or touch a session atomically with its latest Center contract. */
    public SessionView ensure(TaskOrigin origin, ExecutionContract contract) {
        if (contract == null) throw new IllegalArgumentException("execution contract is required");
        var principal = principal(origin);
        var sessionId = required(contract.sessionId(), "session_id");
        var conversationId = required(origin == null ? null : origin.connectionId(), "conversation_id");
        var now = Instant.now();
        var state = new SessionState(principal, sessionId, conversationId, contract.machineId(),
                contract, "active", now, contract.expiresAt(), now);
        if (jdbc == null) {
            memory.put(key(principal, sessionId), state);
        } else {
            var contractJson = new String(JsonCodec.write(contract), StandardCharsets.UTF_8);
            runInTransaction(() -> jdbc.update("""
                    INSERT INTO rcm_execution_session(session_id, principal_id, conversation_id, machine_id,
                                                      contract_json, status, last_seen_at, expires_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, CAST(? AS jsonb), 'active', ?, ?, ?, ?)
                    ON CONFLICT (principal_id, session_id) DO UPDATE SET conversation_id = EXCLUDED.conversation_id,
                        machine_id = EXCLUDED.machine_id, contract_json = EXCLUDED.contract_json,
                        status = 'active', last_seen_at = EXCLUDED.last_seen_at,
                        expires_at = EXCLUDED.expires_at, updated_at = EXCLUDED.updated_at
                    """, sessionId, principal, conversationId, contract.machineId(), contractJson,
                    timestamp(now), timestamp(contract.expiresAt()), timestamp(now), timestamp(now)));
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
        return changed;
    }

    public List<SessionView> list(String principalId, int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 200) throw new IllegalArgumentException("invalid session page");
        var principal = principalId == null || principalId.isBlank() ? "" : required(principalId, "principal_id");
        if (jdbc == null) {
            return memory.values().stream()
                    .filter(value -> principal.isBlank() || value.principalId().equals(principal))
                    .sorted(java.util.Comparator.comparing(SessionState::lastSeenAt).reversed())
                    .skip(offset).limit(limit).map(this::view).toList();
        }
        var sql = """
                SELECT session_id, principal_id, conversation_id, machine_id, status,
                       last_seen_at, expires_at, created_at, updated_at
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
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at"))));
    }

    public int count(String principalId) {
        var principal = principalId == null || principalId.isBlank() ? "" : required(principalId, "principal_id");
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
                value.status(), value.lastSeenAt(), value.expiresAt(), value.createdAt(), value.updatedAt());
    }

    private record SessionState(String principalId, String sessionId, String conversationId, String machineId,
                                ExecutionContract contract, String status, Instant lastSeenAt,
                                Instant expiresAt, Instant updatedAt) {
        SessionState withStatus(String nextStatus, Instant now) {
            return new SessionState(principalId, sessionId, conversationId, machineId, contract,
                    nextStatus, lastSeenAt, expiresAt, now);
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
                              @JsonProperty("updated_at") Instant updatedAt) {
    }
}
