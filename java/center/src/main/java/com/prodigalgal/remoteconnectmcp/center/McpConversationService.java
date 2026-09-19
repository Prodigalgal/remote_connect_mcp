package com.prodigalgal.remoteconnectmcp.center;

import com.prodigalgal.remoteconnectmcp.protocol.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Durable correlation metadata for MCP conversations and transport
 * connections.  It is deliberately not an authorization boundary: principal
 * and token checks still happen before a tool handler is entered, while this
 * service only makes reconnect/recovery and audit correlation explicit.
 *
 * <p>No cleanup thread is used.  Rows are touched on demand and an operator
 * may remove expired metadata with the normal database retention policy.</p>
 */
@Service
public final class McpConversationService {
    private static final int MAX_ID = 256;
    private static final Duration DEFAULT_TTL = Duration.ofDays(30);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Map<String, Correlation> memory = new ConcurrentHashMap<>();

    @Autowired
    public McpConversationService(ObjectProvider<JdbcTemplate> jdbcProvider,
                                  ObjectProvider<TransactionTemplate> transactionProvider) {
        this(jdbcProvider == null ? null : jdbcProvider.getIfAvailable(),
                transactionProvider == null ? null : transactionProvider.getIfAvailable());
    }

    McpConversationService() {
        this((JdbcTemplate) null, (TransactionTemplate) null);
    }

    McpConversationService(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    /** Touch the current MCP connection and its parent conversation. */
    public void touch(TaskOrigin origin, String transport) {
        if (origin == null) throw new SecurityException("MCP transport principal is missing");
        var principal = required(origin.principalId(), "principal_id");
        var connection = required(origin.connectionId(), "connection_id");
        var now = Instant.now();
        var expires = now.plus(DEFAULT_TTL);
        var normalizedTransport = normalizeTransport(transport);
        if (jdbc == null) {
            memory.put(key(principal, connection), new Correlation(principal, connection,
                    normalizedTransport, now, now, expires));
            return;
        }
        var metadata = new String(JsonCodec.write(Map.of("transport", normalizedTransport)), StandardCharsets.UTF_8);
        try {
            runInTransaction(() -> {
                jdbc.update("""
                        INSERT INTO rcm_conversation(conversation_id, principal_id, status, metadata_json,
                                                     created_at, last_seen_at, expires_at)
                        VALUES (?, ?, 'active', CAST(? AS jsonb), ?, ?, ?)
                        ON CONFLICT (principal_id, conversation_id) DO UPDATE SET status = 'active',
                            metadata_json = EXCLUDED.metadata_json, last_seen_at = EXCLUDED.last_seen_at,
                            expires_at = EXCLUDED.expires_at
                        """, connection, principal, metadata, timestamp(now), timestamp(now), timestamp(expires));
                jdbc.update("""
                        INSERT INTO rcm_mcp_connection(connection_id, conversation_id, principal_id, transport,
                                                       status, metadata_json, created_at, last_seen_at, closed_at)
                        VALUES (?, ?, ?, ?, 'active', CAST(? AS jsonb), ?, ?, NULL)
                        ON CONFLICT (principal_id, connection_id) DO UPDATE SET conversation_id = EXCLUDED.conversation_id,
                            transport = EXCLUDED.transport, status = 'active', metadata_json = EXCLUDED.metadata_json,
                            last_seen_at = EXCLUDED.last_seen_at, closed_at = NULL
                        """, connection, connection, principal, normalizedTransport, metadata,
                        timestamp(now), timestamp(now));
            });
        } catch (DataAccessException failure) {
            throw new IllegalStateException("MCP conversation metadata is unavailable", failure);
        }
    }

    /** Mark a transport connection closed without changing task ownership. */
    public void close(TaskOrigin origin) {
        if (origin == null || jdbc == null) return;
        try {
            jdbc.update("""
                    UPDATE rcm_mcp_connection SET status = 'closed', closed_at = CURRENT_TIMESTAMP,
                           last_seen_at = CURRENT_TIMESTAMP
                     WHERE principal_id = ? AND connection_id = ?
                    """, required(origin.principalId(), "principal_id"), required(origin.connectionId(), "connection_id"));
        } catch (DataAccessException failure) {
            throw new IllegalStateException("MCP connection metadata is unavailable", failure);
        }
    }

    private void runInTransaction(Runnable action) {
        if (transactions == null) action.run();
        else transactions.executeWithoutResult(status -> action.run());
    }

    private static String normalizeTransport(String value) {
        var normalized = value == null || value.isBlank() ? "streamable-http" : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[a-z0-9._-]{1,32}")) throw new IllegalArgumentException("transport is invalid");
        return normalized;
    }

    private static String key(String principal, String connection) {
        return principal + '\u0000' + connection;
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
        return java.sql.Timestamp.from(value);
    }

    private record Correlation(String principalId, String connectionId, String transport,
                               Instant createdAt, Instant lastSeenAt, Instant expiresAt) {
    }
}
