package com.prodigalgal.remoteconnectmcp.center;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.prodigalgal.remoteconnectmcp.protocol.SensitiveValueRedactor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Bounded, asynchronous audit sink. Business paths enqueue a redacted event
 * and never wait on a second database write; one virtual writer persists the
 * event or keeps it in a bounded in-memory projection when PostgreSQL is not
 * configured. The queue is event-driven and never uses a timer or poll loop.
 */
@Service
public final class AuditService implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(AuditService.class.getName());
    private static final int MAX_QUEUE = 4096;
    private static final int MAX_MEMORY_EVENTS = 2048;
    private static final Event STOP = new Event("", "", "", null, null, null, null, null, null, Instant.EPOCH);

    private final JdbcTemplate jdbc;
    private final ArrayBlockingQueue<Event> queue = new ArrayBlockingQueue<>(MAX_QUEUE);
    private final CopyOnWriteArrayList<AuditEventView> memory = new CopyOnWriteArrayList<>();
    private final ExecutorService writer = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final LongAdder droppedEvents = new LongAdder();
    private final LongAdder persistFailures = new LongAdder();
    private final boolean structuredLogging;

    public AuditService(ObjectProvider<JdbcTemplate> jdbcProvider) {
        this(jdbcProvider, structuredLoggingEnabled());
    }

    AuditService(ObjectProvider<JdbcTemplate> jdbcProvider, boolean structuredLogging) {
        this.jdbc = jdbcProvider == null ? null : jdbcProvider.getIfAvailable();
        this.structuredLogging = structuredLogging;
        writer.execute(this::drain);
    }

    /** Queue one already-redacted event. Null/invalid fields are normalized. */
    public void record(String eventType, String actor, String agentId, String taskId,
                       String scopeMode, String risk, String outcome, String detail) {
        if (closed.get()) return;
        var event = new Event(
                id(), normalize(eventType, 64, "unknown"), normalize(actor, 128, "system"),
                normalizeNullable(agentId, 180), normalizeNullable(taskId, 180),
                normalizeNullable(scopeMode, 32), normalizeNullable(risk, 16),
                normalizeNullable(outcome, 32), normalizeNullable(detail, 4096), Instant.now());
        if (!queue.offer(event)) {
            // Keep the newest evidence under pressure. Dropping an old audit
            // row is observable through the metric/log, while task execution
            // remains independent from audit backpressure.
            queue.poll();
            droppedEvents.increment();
            if (!queue.offer(event)) LOG.warning("audit queue is full; dropping one event");
        }
    }

    /** Current queue depth for low-cardinality operational metrics. */
    public int queueDepth() {
        return queue.size();
    }

    /** Number of audit events dropped because the bounded queue was full. */
    public long droppedEvents() {
        return droppedEvents.sum();
    }

    /** Number of PostgreSQL writes which failed after the event was queued. */
    public long persistFailures() {
        return persistFailures.sum();
    }

    public List<AuditEventView> list(String eventType, String agentId, String taskId, int offset, int limit) {
        if (offset < 0) throw new IllegalArgumentException("offset must be non-negative");
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("limit must be between 1 and 200");
        var type = normalizeNullable(eventType, 64);
        var agent = normalizeNullable(agentId, 180);
        var task = normalizeNullable(taskId, 180);
        if (jdbc == null) {
            return memory.stream()
                    .filter(value -> type == null || type.equals(value.eventType()))
                    .filter(value -> agent == null || agent.equals(value.agentId()))
                    .filter(value -> task == null || task.equals(value.taskId()))
                    .skip(offset).limit(limit).toList();
        }
        var sql = new StringBuilder("SELECT audit_id, event_type, actor, agent_id, task_id, scope_mode, risk, outcome, detail_text, created_at FROM rcm_audit_event WHERE 1=1");
        var args = new ArrayList<String>();
        if (type != null) { sql.append(" AND event_type = ?"); args.add(type); }
        if (agent != null) { sql.append(" AND agent_id = ?"); args.add(agent); }
        if (task != null) { sql.append(" AND task_id = ?"); args.add(task); }
        sql.append(" ORDER BY created_at DESC, audit_id DESC OFFSET ? LIMIT ?");
        var params = new ArrayList<Object>(args);
        params.add(offset);
        params.add(limit);
        return List.copyOf(jdbc.query(sql.toString(), params.toArray(), (rs, row) -> new AuditEventView(
                rs.getString("audit_id"), rs.getString("event_type"), rs.getString("actor"),
                rs.getString("agent_id"), rs.getString("task_id"), rs.getString("scope_mode"),
                rs.getString("risk"), rs.getString("outcome"), rs.getString("detail_text"),
                rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant())));
    }

    /** Count the same filtered projection without loading audit details. */
    public int count(String eventType, String agentId, String taskId) {
        var type = normalizeNullable(eventType, 64);
        var agent = normalizeNullable(agentId, 180);
        var task = normalizeNullable(taskId, 180);
        if (jdbc == null) {
            return Math.toIntExact(memory.stream()
                    .filter(value -> type == null || type.equals(value.eventType()))
                    .filter(value -> agent == null || agent.equals(value.agentId()))
                    .filter(value -> task == null || task.equals(value.taskId()))
                    .count());
        }
        var sql = new StringBuilder("SELECT COUNT(*) FROM rcm_audit_event WHERE 1=1");
        var args = new ArrayList<String>();
        if (type != null) { sql.append(" AND event_type = ?"); args.add(type); }
        if (agent != null) { sql.append(" AND agent_id = ?"); args.add(agent); }
        if (task != null) { sql.append(" AND task_id = ?"); args.add(task); }
        var value = jdbc.queryForObject(sql.toString(), args.toArray(), Long.class);
        return value == null ? 0 : Math.toIntExact(value);
    }

    /**
     * Explicit, bounded retention operation.  There is deliberately no
     * scheduled cleanup thread: an administrator or an external maintenance
     * job chooses when to remove old evidence.  The delete is limited so a
     * large audit table never monopolizes the Center's JDBC pool.
     */
    public int purge(int retentionDays, int limit) {
        if (retentionDays < 1 || retentionDays > 3650) {
            throw new IllegalArgumentException("retentionDays must be between 1 and 3650");
        }
        if (limit < 1 || limit > 5000) {
            throw new IllegalArgumentException("limit must be between 1 and 5000");
        }
        var cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        if (jdbc == null) {
            var removed = 0;
            for (var event : List.copyOf(memory).reversed()) {
                if (removed >= limit) break;
                if (event.createdAt() != null && event.createdAt().isBefore(cutoff) && memory.remove(event)) {
                    removed++;
                }
            }
            return removed;
        }
        return jdbc.update("""
                DELETE FROM rcm_audit_event
                 WHERE audit_id IN (
                    SELECT audit_id FROM rcm_audit_event
                     WHERE created_at < ?
                     ORDER BY created_at, audit_id
                     LIMIT ?
                 )
                """, java.sql.Timestamp.from(cutoff), limit);
    }

    private void drain() {
        try {
            while (!closed.get()) {
                var event = queue.take();
                if (event == STOP) return;
                var view = event.view();
                if (jdbc == null) {
                    memory.add(0, view);
                    while (memory.size() > MAX_MEMORY_EVENTS) memory.remove(memory.size() - 1);
                } else {
                    try {
                        jdbc.update("""
                                INSERT INTO rcm_audit_event(audit_id, event_type, actor, agent_id, task_id,
                                    scope_mode, risk, outcome, detail_text, created_at)
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                                """, view.id(), view.eventType(), view.actor(), view.agentId(), view.taskId(),
                                view.scopeMode(), view.risk(), view.outcome(), view.detail(),
                                java.sql.Timestamp.from(view.createdAt()));
                    } catch (RuntimeException failure) {
                        persistFailures.increment();
                        LOG.log(Level.WARNING, "could not persist audit event " + view.eventType(), failure);
                    }
                }
                if (structuredLogging) {
                    try {
                        var line = StructuredLog.audit(view);
                        LOG.info(() -> "rcm.audit " + line);
                    } catch (RuntimeException loggingFailure) {
                        LOG.log(Level.FINE, "could not render structured audit event", loggingFailure);
                    }
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String id() {
        return "audit_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String normalize(String value, int max, String fallback) {
        var normalized = normalizeNullable(value, max);
        return normalized == null ? fallback : normalized;
    }

    private static String normalizeNullable(String value, int max) {
        if (value == null || value.isBlank()) return null;
        var normalized = value.trim();
        if (normalized.length() > max) normalized = normalized.substring(0, max);
        var clean = normalized.chars().mapToObj(code -> {
            var character = (char) code;
            return character == '\r' || character == '\n' || Character.isISOControl(character) ? " " : String.valueOf(character);
        }).collect(java.util.stream.Collectors.joining());
        var redacted = SensitiveValueRedactor.redact(clean);
        return redacted == null || redacted.isBlank() ? null : redacted;
    }

    private static boolean structuredLoggingEnabled() {
        var property = System.getProperty("RCM_CENTER_STRUCTURED_AUDIT_LOG");
        if (property == null) property = System.getenv("RCM_CENTER_STRUCTURED_AUDIT_LOG");
        return property != null && Boolean.parseBoolean(property.trim());
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        queue.offer(STOP);
        writer.shutdownNow();
    }

    private record Event(String id, String eventType, String actor, String agentId, String taskId,
                         String scopeMode, String risk, String outcome, String detail, Instant createdAt) {
        AuditEventView view() {
            return new AuditEventView(id, eventType, actor, agentId, taskId, scopeMode, risk, outcome, detail, createdAt);
        }
    }
}
