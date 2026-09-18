package com.prodigalgal.remoteconnectmcp.center;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Small admission guard for the light-weight multi-principal model.  Quotas
 * are deliberately coarse and low-cardinality: they prevent one conversation
 * from filling the task table or Agent slots, without becoming a second
 * scheduler.  PostgreSQL counts are authoritative; the bounded map is used
 * only when the Center runs in protocol-test memory mode.
 */
@Service
public final class McpQuotaService {
    private static final int DEFAULT_MAX_ACTIVE = 32;
    private static final int DEFAULT_MAX_QUEUED = 256;
    private static final int DEFAULT_MAX_SESSIONS = 64;
    private static final long DEFAULT_MAX_TRANSFER_BYTES = 16L * 1024 * 1024 * 1024;

    private final JdbcTemplate jdbc;
    private final int maxActiveTasks;
    private final int maxQueuedTasks;
    private final int maxSessions;
    private final long maxTransferBytes;
    /** Admission reservations live until the corresponding task is terminal. */
    private final Map<String, Set<String>> memoryReservations = new ConcurrentHashMap<>();
    /** Memory-mode reservations that have crossed from queued into execution. */
    private final Map<String, Set<String>> memoryActiveTasks = new ConcurrentHashMap<>();
    /** Memory-mode execution-session reservations released on close/expiry. */
    private final Map<String, Set<String>> memorySessions = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public McpQuotaService(ObjectProvider<JdbcTemplate> jdbcProvider) {
        this(jdbcProvider == null ? null : jdbcProvider.getIfAvailable());
    }

    McpQuotaService() {
        this((JdbcTemplate) null);
    }

    McpQuotaService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.maxActiveTasks = envInt("REMOTE_CONNECT_MCP_QUOTA_MAX_ACTIVE_TASKS", DEFAULT_MAX_ACTIVE, 1, 10000);
        this.maxQueuedTasks = envInt("REMOTE_CONNECT_MCP_QUOTA_MAX_QUEUED_TASKS", DEFAULT_MAX_QUEUED, 1, 100000);
        this.maxSessions = envInt("REMOTE_CONNECT_MCP_QUOTA_MAX_SESSIONS", DEFAULT_MAX_SESSIONS, 1, 10000);
        this.maxTransferBytes = envLong("REMOTE_CONNECT_MCP_QUOTA_MAX_TRANSFER_BYTES", DEFAULT_MAX_TRANSFER_BYTES,
                1L * 1024 * 1024, 4L * 1024 * 1024 * 1024 * 1024);
    }

    /** Throw before task IO is scheduled when a principal is over quota. */
    public void assertTaskAdmission(TaskOrigin origin) {
        assertTaskAdmission(origin, "reservation_" + java.util.UUID.randomUUID());
    }

    /** Reserve one concrete task so memory-mode quotas can be released exactly once. */
    public void assertTaskAdmission(TaskOrigin origin, String taskId) {
        assertTaskAdmission(origin, taskId, null, null);
    }

    /**
     * Reserve a task unless the request is an idempotent replay already
     * represented in PostgreSQL.  The lookup deliberately happens inside the
     * quota service so a retry remains admissible even when the principal is
     * currently at its active/queued limit; JdbcTaskStore still performs the
     * authoritative same-command check under its transaction.
     */
    public void assertTaskAdmission(TaskOrigin origin, String taskId, String machineId, String idempotencyKey) {
        if (origin == null || origin.isShared()) return;
        var principal = origin.principalId();
        if (jdbc != null) {
            var key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim();
            if (key != null && machineId != null && !machineId.isBlank()) {
                var existing = jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM rcm_task
                                        WHERE agent_id = ? AND principal_id = ? AND idempotency_key = ?)
                        """, Boolean.class, machineId.trim(), principal, key);
                if (Boolean.TRUE.equals(existing)) return;
            }
            var active = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM rcm_task
                     WHERE principal_id = ? AND status IN ('dispatching', 'running', 'cancel_requested')
                    """, Long.class, principal);
            var queued = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM rcm_task
                     WHERE principal_id = ? AND status = 'queued'
                    """, Long.class, principal);
            if ((active == null ? 0 : active) >= maxActiveTasks) {
                throw new IllegalStateException("principal active task quota exceeded");
            }
            if ((queued == null ? 0 : queued) >= maxQueuedTasks) {
                throw new IllegalStateException("principal queued task quota exceeded");
            }
            return;
        }
        var reservations = memoryReservations.computeIfAbsent(principal, ignored -> ConcurrentHashMap.newKeySet());
        synchronized (reservations) {
            var active = memoryActiveTasks.getOrDefault(principal, Set.of());
            var queued = reservations.size() - active.size();
            if (queued >= maxQueuedTasks) throw new IllegalStateException("principal queued task quota exceeded");
            reservations.add(taskId == null || taskId.isBlank() ? "reservation_" + java.util.UUID.randomUUID() : taskId);
        }
    }

    /**
     * Database-backed task admission used from inside JdbcTaskStore's insert
     * transaction.  The advisory lock is scoped to the principal and held
     * until that transaction commits, so the count and the task INSERT cannot
     * be separated by a concurrent Center request.  This deliberately does
     * not create a second quota table: PostgreSQL remains the sole task fact.
     */
    boolean assertTaskAdmissionInTransaction(TaskOrigin origin, String taskId,
                                             String machineId, String idempotencyKey) {
        if (origin == null || origin.isShared() || jdbc == null) return false;
        var principal = origin.principalId();
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                ps -> ps.setString(1, principal), rs -> null);
        var key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim();
        if (key != null && machineId != null && !machineId.isBlank()) {
            var existing = jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM rcm_task
                                    WHERE agent_id = ? AND principal_id = ? AND idempotency_key = ?)
                    """, Boolean.class, machineId.trim(), principal, key);
            if (Boolean.TRUE.equals(existing)) return true;
        }
        var active = jdbc.queryForObject("""
                SELECT COUNT(*) FROM rcm_task
                 WHERE principal_id = ? AND status IN ('dispatching', 'running', 'cancel_requested')
                """, Long.class, principal);
        var queued = jdbc.queryForObject("""
                SELECT COUNT(*) FROM rcm_task
                 WHERE principal_id = ? AND status = 'queued'
                """, Long.class, principal);
        if ((active == null ? 0 : active) >= maxActiveTasks) {
            throw new IllegalStateException("principal active task quota exceeded");
        }
        if ((queued == null ? 0 : queued) >= maxQueuedTasks) {
            throw new IllegalStateException("principal queued task quota exceeded");
        }
        return false;
    }

    /** Move a memory-mode task into the active bucket after dispatch. */
    public void markTaskActive(TaskOrigin origin, String taskId) {
        if (origin == null || origin.isShared() || jdbc != null || taskId == null || taskId.isBlank()) return;
        var reservations = memoryReservations.get(origin.principalId());
        if (reservations == null) return;
        synchronized (reservations) {
            if (!reservations.contains(taskId)) return;
            memoryActiveTasks.computeIfAbsent(origin.principalId(), ignored -> ConcurrentHashMap.newKeySet()).add(taskId);
        }
    }

    /**
     * Admission check for the queued-to-running transition in memory mode.
     * JDBC polling applies the equivalent predicate inside its row-locking
     * query; keeping this method side-effect free lets a denied candidate stay
     * queued without consuming a permit or requiring a retry timer.
     */
    public boolean permitsActivation(TaskOrigin origin, String taskId) {
        if (origin == null || origin.isShared() || jdbc != null || taskId == null || taskId.isBlank()) return true;
        var reservations = memoryReservations.get(origin.principalId());
        if (reservations == null) return true;
        synchronized (reservations) {
            if (!reservations.contains(taskId)) return true;
            var active = memoryActiveTasks.get(origin.principalId());
            return active == null || active.contains(taskId) || active.size() < maxActiveTasks;
        }
    }

    /** Return a memory-mode reservation to the queued bucket after lease recovery. */
    public void markTaskQueued(TaskOrigin origin, String taskId) {
        if (origin == null || origin.isShared() || jdbc != null || taskId == null || taskId.isBlank()) return;
        var reservations = memoryReservations.get(origin.principalId());
        var active = memoryActiveTasks.get(origin.principalId());
        if (reservations == null || active == null) return;
        synchronized (reservations) {
            active.remove(taskId);
            if (active.isEmpty()) memoryActiveTasks.remove(origin.principalId(), active);
        }
    }

    /** Best-effort release for non-JDBC test mode and explicit task teardown. */
    public void releaseTask(TaskOrigin origin) {
        releaseTask(origin, null);
    }

    /** Enforce the active execution-session cap without adding a timer. */
    public void assertSessionAdmission(TaskOrigin origin, String sessionId) {
        if (origin == null || origin.isShared() || sessionId == null || sessionId.isBlank()) return;
        var principal = origin.principalId();
        if (jdbc == null) {
            var sessions = memorySessions.computeIfAbsent(principal, ignored -> ConcurrentHashMap.newKeySet());
            synchronized (sessions) {
                if (sessions.contains(sessionId)) return;
                if (sessions.size() >= maxSessions) throw new IllegalStateException("principal execution-session quota exceeded");
                sessions.add(sessionId);
            }
            return;
        }
        var existing = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM rcm_execution_session
                                WHERE principal_id = ? AND session_id = ?
                                  AND status = 'active' AND expires_at > CURRENT_TIMESTAMP)
                """, Boolean.class, principal, sessionId);
        if (Boolean.TRUE.equals(existing)) return;
        var active = jdbc.queryForObject("""
                SELECT COUNT(*) FROM rcm_execution_session
                 WHERE principal_id = ? AND status = 'active' AND expires_at > CURRENT_TIMESTAMP
                """, Long.class, principal);
        if ((active == null ? 0L : active) >= maxSessions) {
            throw new IllegalStateException("principal execution-session quota exceeded");
        }
    }

    /** Check durable transfer bytes before opening a remote input stream. */
    public void assertTransferAdmission(TaskOrigin origin, long expectedBytes) {
        assertTransferAdmission(origin, expectedBytes, null);
    }

    /** Check a transfer while allowing an idempotent existing transfer replay. */
    public void assertTransferAdmission(TaskOrigin origin, long expectedBytes, String transferId) {
        if (origin == null || origin.isShared() || jdbc == null) return;
        if (expectedBytes < 0 || expectedBytes > maxTransferBytes) {
            throw new IllegalStateException("principal transfer-byte quota exceeded");
        }
        if (transferId != null && !transferId.isBlank()) {
            var existing = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM rcm_file_transfer WHERE transfer_id = ? AND principal_id = ?)",
                    Boolean.class, transferId.trim(), origin.principalId());
            if (Boolean.TRUE.equals(existing)) return;
        }
        var used = jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN expected_bytes = 0 THEN ?
                                         ELSE GREATEST(expected_bytes, bytes_transferred) END), 0)
                  FROM rcm_file_transfer
                 WHERE principal_id = ? AND status IN ('pending','ready','delivering')
                """, Long.class, ArtifactStore.MAX_STREAM_BYTES, origin.principalId());
        var current = used == null ? 0L : Math.max(0L, used);
        if (current > maxTransferBytes - expectedBytes) {
            throw new IllegalStateException("principal transfer-byte quota exceeded");
        }
    }

    /** Authoritative transfer admission used inside the transfer-row transaction. */
    void assertTransferAdmissionInTransaction(TaskOrigin origin, String transferId, long expectedBytes) {
        if (origin == null || origin.isShared() || jdbc == null) return;
        if (expectedBytes < 0 || expectedBytes > maxTransferBytes) {
            throw new IllegalStateException("principal transfer-byte quota exceeded");
        }
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                ps -> ps.setString(1, origin.principalId()), rs -> null);
        if (transferId != null && !transferId.isBlank()) {
            var existing = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM rcm_file_transfer WHERE transfer_id = ? AND principal_id = ?)",
                    Boolean.class, transferId.trim(), origin.principalId());
            if (Boolean.TRUE.equals(existing)) return;
        }
        var used = jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN expected_bytes = 0 THEN 4294967296
                                         ELSE GREATEST(expected_bytes, bytes_transferred) END), 0)
                  FROM rcm_file_transfer
                 WHERE principal_id = ? AND status IN ('pending','ready','delivering')
                """, Long.class, origin.principalId());
        var current = used == null ? 0L : Math.max(0L, used);
        if (current > maxTransferBytes - expectedBytes) {
            throw new IllegalStateException("principal transfer-byte quota exceeded");
        }
    }

    /** Release a concrete reservation; repeated terminal callbacks are harmless. */
    public void releaseTask(TaskOrigin origin, String taskId) {
        if (origin == null || origin.isShared() || jdbc != null) return;
        var reservations = memoryReservations.get(origin.principalId());
        if (reservations == null) return;
        synchronized (reservations) {
            if (taskId == null || taskId.isBlank()) {
                var iterator = reservations.iterator();
                if (iterator.hasNext()) {
                    var removed = iterator.next();
                    iterator.remove();
                    var active = memoryActiveTasks.get(origin.principalId());
                    if (active != null) active.remove(removed);
                }
            } else {
                reservations.remove(taskId);
                var active = memoryActiveTasks.get(origin.principalId());
                if (active != null) {
                    active.remove(taskId);
                    if (active.isEmpty()) memoryActiveTasks.remove(origin.principalId(), active);
                }
            }
            var active = memoryActiveTasks.get(origin.principalId());
            if (active != null && active.isEmpty()) memoryActiveTasks.remove(origin.principalId(), active);
            if (reservations.isEmpty()) memoryReservations.remove(origin.principalId(), reservations);
        }
    }

    /** Release a memory-mode session reservation on explicit close or expiry. */
    public void releaseSession(TaskOrigin origin, String sessionId) {
        if (origin == null || origin.isShared() || jdbc != null || sessionId == null || sessionId.isBlank()) return;
        var sessions = memorySessions.get(origin.principalId());
        if (sessions == null) return;
        synchronized (sessions) {
            sessions.remove(sessionId);
            if (sessions.isEmpty()) memorySessions.remove(origin.principalId(), sessions);
        }
    }

    public QuotaView snapshot(String principalId) {
        var principal = principalId == null ? "" : principalId.trim();
        long active = 0;
        long queued = 0;
        long sessions = 0;
        long transferBytes = 0;
        long reservedTransferBytes = 0;
        long transferredTransferBytes = 0;
        if (jdbc != null && !principal.isBlank()) {
            active = value(jdbc.queryForObject("SELECT COUNT(*) FROM rcm_task WHERE principal_id = ? AND status IN ('dispatching','running','cancel_requested')", Long.class, principal));
            queued = value(jdbc.queryForObject("SELECT COUNT(*) FROM rcm_task WHERE principal_id = ? AND status = 'queued'", Long.class, principal));
            sessions = value(jdbc.queryForObject("SELECT COUNT(*) FROM rcm_execution_session WHERE principal_id = ? AND status = 'active' AND expires_at > CURRENT_TIMESTAMP", Long.class, principal));
            reservedTransferBytes = value(jdbc.queryForObject("""
                    SELECT COALESCE(SUM(CASE WHEN expected_bytes = 0
                                             THEN 4294967296
                                             ELSE GREATEST(expected_bytes, bytes_transferred) END), 0)
                      FROM rcm_file_transfer
                     WHERE principal_id = ? AND status IN ('pending','ready','delivering')
                    """, Long.class, principal));
            transferredTransferBytes = value(jdbc.queryForObject("SELECT COALESCE(SUM(bytes_transferred),0) FROM rcm_file_transfer WHERE principal_id = ? AND status IN ('pending','ready','delivering')", Long.class, principal));
            transferBytes = reservedTransferBytes;
        } else if (!principal.isBlank()) {
            var reservations = memoryReservations.get(principal);
            var activeSet = memoryActiveTasks.get(principal);
            active = activeSet == null ? 0 : activeSet.size();
            queued = reservations == null ? 0 : Math.max(0L, reservations.size() - active);
            var sessionSet = memorySessions.get(principal);
            sessions = sessionSet == null ? 0 : sessionSet.size();
        }
        return new QuotaView(principal, active, maxActiveTasks, queued, maxQueuedTasks,
                sessions, maxSessions, transferBytes, maxTransferBytes,
                reservedTransferBytes, transferredTransferBytes);
    }

    int maxActiveTasks() {
        return maxActiveTasks;
    }

    public record QuotaView(String principalId, long activeTasks, int maxActiveTasks,
                            long queuedTasks, int maxQueuedTasks, long activeSessions,
                            int maxSessions, long transferBytes, long maxTransferBytes,
                            long reservedTransferBytes, long transferredTransferBytes) {
        /** Compatibility shape for older Console clients. */
        public QuotaView(String principalId, long activeTasks, int maxActiveTasks,
                         long queuedTasks, int maxQueuedTasks, long activeSessions,
                         int maxSessions, long transferBytes, long maxTransferBytes) {
            this(principalId, activeTasks, maxActiveTasks, queuedTasks, maxQueuedTasks,
                    activeSessions, maxSessions, transferBytes, maxTransferBytes,
                    transferBytes, transferBytes);
        }
    }

    private static long value(Long value) {
        return value == null ? 0L : Math.max(0L, value);
    }

    private static int envInt(String key, int fallback, int min, int max) {
        var value = System.getenv(key);
        if (value == null || value.isBlank()) return fallback;
        try {
            var parsed = Integer.parseInt(value.trim());
            if (parsed < min || parsed > max) throw new IllegalArgumentException(key + " is outside the allowed range");
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(key + " must be an integer", error);
        }
    }

    private static long envLong(String key, long fallback, long min, long max) {
        var value = System.getenv(key);
        if (value == null || value.isBlank()) return fallback;
        try {
            var parsed = Long.parseLong(value.trim());
            if (parsed < min || parsed > max) throw new IllegalArgumentException(key + " is outside the allowed range");
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(key + " must be an integer", error);
        }
    }
}
