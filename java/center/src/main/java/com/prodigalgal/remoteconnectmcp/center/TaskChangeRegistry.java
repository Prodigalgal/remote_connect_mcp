package com.prodigalgal.remoteconnectmcp.center;

import java.sql.Connection;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Event-driven wake-up for task waiters.
 *
 * <p>The control-plane sequence is global, while task waiters have their own
 * sequence keyed by a digest of the task ID. A waiter always re-reads its task
 * row after waking, so an unrelated change or a missed notification is
 * harmless. PostgreSQL LISTEN/NOTIFY is consumed with the driver's blocking
 * notification API; the task row remains the source of truth and callers
 * still use a bounded deadline.</p>
 */
@Component
public final class TaskChangeRegistry implements AutoCloseable {
    private static final String CHANNEL = "rcm_task_change";
    private static final Logger LOG = Logger.getLogger(TaskChangeRegistry.class.getName());
    // pgjdbc's zero-timeout notification overload is a non-blocking probe.
    // Use a bounded socket wait so an idle Center replica does not spin while
    // still detecting a dead database connection without a task polling timer.
    private static final int NOTIFICATION_WAIT_MILLIS = 30_000;

    private final DataSource dataSource;
    private final ExecutorService database = Executors.newVirtualThreadPerTaskExecutor();
    private final ChangeState global = new ChangeState();
    private final Map<String, ChangeState> taskStates = new ConcurrentHashMap<>();
    private final Map<String, String> taskHashes = new ConcurrentHashMap<>();
    private final AtomicReference<Connection> listenerConnection = new AtomicReference<>();
    private final Set<String> pendingPayloads = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean publishing = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final String instanceId = UUID.randomUUID().toString();

    public TaskChangeRegistry(ObjectProvider<DataSource> dataSourceProvider) {
        this.dataSource = dataSourceProvider == null ? null : dataSourceProvider.getIfAvailable();
        if (this.dataSource != null) {
            database.execute(this::listenLoop);
        }
    }

    /** Current global control-plane sequence, used by the admin console. */
    public long version() {
        return global.sequence.get();
    }

    /** Current per-task sequence, captured immediately before a row read. */
    public long version(String taskId) {
        var normalized = normalizeTaskId(taskId);
        if (normalized.isBlank()) return global.sequence.get();
        var state = taskStates.compute(normalized, (ignored, existing) -> {
            var current = existing == null ? new ChangeState() : existing;
            current.references.incrementAndGet();
            return current;
        });
        taskHashes.putIfAbsent(hashTaskId(normalized), normalized);
        return state.sequence.get();
    }

    /**
     * Release the short-lived reference acquired by {@link #version(String)}.
     * Task waiters are not an inventory cache: retaining one condition and one
     * hash for every historical task would make a long-lived Center leak
     * memory.  ConcurrentHashMap.computeIfPresent serializes release with a
     * new waiter for the same ID, so an active waiter can never be evicted.
     */
    public void release(String taskId) {
        var normalized = normalizeTaskId(taskId);
        if (normalized.isBlank()) return;
        var hash = hashTaskId(normalized);
        taskStates.computeIfPresent(normalized, (ignored, state) -> {
            if (state.references.decrementAndGet() <= 0) {
                // Remove the routing entry while the task-state map bin is
                // still serialized. A subsequent version() will recreate both
                // entries in the correct order.
                taskHashes.remove(hash, normalized);
                return null;
            }
            return state;
        });
    }

    /**
     * Wake local waiters and best-effort publish a cross-replica hint. The task
     * identifier is routed to a per-task waiter using a SHA-256 digest. The
     * digest keeps task IDs out of database notification payloads while
     * avoiding a global wake/query storm when many tasks are active.
     */
    public void signal(String taskId) {
        var normalized = normalizeTaskId(taskId);
        if (normalized.isBlank()) return;
        signalTaskLocal(normalized);
        signalGlobalLocal();
        enqueue("t:" + hashTaskId(normalized));
    }

    /**
     * Wake only waiters for one task. High-volume output chunks use this path
     * so an interactive task stream cannot invalidate every admin projection
     * or wake every Center replica's console. Terminal/state changes still use
     * {@link #signal(String)} and therefore advance the global control cursor.
     */
    public void signalTaskOnly(String taskId) {
        var normalized = normalizeTaskId(taskId);
        if (normalized.isBlank()) return;
        signalTaskLocal(normalized);
        // Output/artifact updates must also wake a waiter connected to another
        // Center replica, but they must not advance the global admin cursor.
        // Route them with a distinct prefix so the listener can keep the wake
        // strictly task-local on every replica.
        enqueue("o:" + hashTaskId(normalized));
    }

    /** Wake every Center waiter for a non-task control-plane change. */
    public void signalGlobal() {
        if (closed.get()) return;
        signalGlobalLocal();
        enqueue("g");
    }

    /** Await a global sequence change without holding a JDBC connection. */
    public void awaitChange(long observed, long timeoutNanos) throws InterruptedException {
        awaitState(global, observed, timeoutNanos);
    }

    /** Await a per-task sequence without holding a JDBC connection. */
    public void awaitChange(String taskId, long observed, long timeoutNanos) throws InterruptedException {
        var normalized = normalizeTaskId(taskId);
        if (normalized.isBlank()) {
            awaitState(global, observed, timeoutNanos);
            return;
        }
        taskHashes.putIfAbsent(hashTaskId(normalized), normalized);
        awaitState(taskStates.computeIfAbsent(normalized, ignored -> new ChangeState()), observed, timeoutNanos);
    }

    private void awaitState(ChangeState state, long observed, long timeoutNanos) throws InterruptedException {
        if (state == null || timeoutNanos <= 0 || closed.get()) return;
        var deadline = System.nanoTime() + timeoutNanos;
        state.lock.lockInterruptibly();
        try {
            while (!closed.get() && state.sequence.get() == observed) {
                var remaining = deadline - System.nanoTime();
                if (remaining <= 0) return;
                state.changed.awaitNanos(remaining);
            }
        } finally {
            state.lock.unlock();
        }
    }

    private void enqueue(String payload) {
        if (dataSource == null || closed.get() || payload == null || payload.isBlank()) return;
        pendingPayloads.add(payload);
        if (publishing.compareAndSet(false, true)) database.execute(this::publishPending);
    }

    private void publishPending() {
        try {
            while (!closed.get()) {
                var batch = new ArrayList<String>(64);
                for (var payload : pendingPayloads) {
                    if (batch.size() >= 64) break;
                    if (pendingPayloads.remove(payload)) batch.add(payload);
                }
                if (batch.isEmpty()) return;
                publish(batch);
            }
        } finally {
            publishing.set(false);
            if (!pendingPayloads.isEmpty() && publishing.compareAndSet(false, true)) {
                database.execute(this::publishPending);
            }
        }
    }

    private void publish(List<String> payloads) {
        // Notification loss is safe: TaskService re-reads the row and has a
        // deadline-based result when this best-effort hint is unavailable.
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT pg_notify(?, ?)")) {
            statement.setString(1, CHANNEL);
            statement.setString(2, instanceId + "|" + String.join(",", payloads));
            statement.execute();
        } catch (SQLException | RuntimeException exception) {
            LOG.log(Level.FINE, "could not publish cross-replica task change hint", exception);
        }
    }

    private void listenLoop() {
        var delayMillis = 1000L;
        while (!closed.get() && !Thread.currentThread().isInterrupted()) {
            try (var connection = dataSource.getConnection()) {
                var postgres = connection.unwrap(PGConnection.class);
                try (var statement = connection.createStatement()) {
                    statement.execute("LISTEN " + CHANNEL);
                }
                listenerConnection.set(connection);
                delayMillis = 1000L;
                while (!closed.get() && !Thread.currentThread().isInterrupted()) {
                    // Wait in the driver on the PostgreSQL socket. A
                    // notification returns immediately; the bounded timeout
                    // is only a liveness check and is not a task polling
                    // interval.
                    var notifications = postgres.getNotifications(NOTIFICATION_WAIT_MILLIS);
                    if (notifications == null) continue;
                    for (var notification : notifications) dispatchNotification(notification.getParameter());
                }
            } catch (SQLException | RuntimeException exception) {
                if (!closed.get()) {
                    LOG.log(Level.FINE, "PostgreSQL task change listener unavailable; reconnecting with backoff", exception);
                }
                delayMillis = Math.min(30_000L, delayMillis * 2L);
                sleep(delayMillis);
            } finally {
                listenerConnection.set(null);
            }
        }
    }

    private void dispatchNotification(String payload) {
        if (payload == null || payload.isBlank()) return;
        var separator = payload.indexOf('|');
        if (separator <= 0 || separator == payload.length() - 1) return;
        if (instanceId.equals(payload.substring(0, separator))) return;
        for (var item : payload.substring(separator + 1).split(",")) {
            if ("g".equals(item)) {
                signalGlobalLocal();
            } else if (item.startsWith("t:") && item.length() > 2) {
                signalTaskHashLocal(item.substring(2));
                signalGlobalLocal();
            } else if (item.startsWith("o:") && item.length() > 2) {
                signalTaskHashLocal(item.substring(2));
            }
        }
    }

    private void signalTaskLocal(String taskId) {
        var state = taskStates.get(taskId);
        if (state == null) return;
        signalState(state);
        taskStates.computeIfPresent(taskId, (ignored, current) -> {
            if (current == state && current.references.get() <= 0) {
                taskHashes.remove(hashTaskId(taskId), taskId);
                return null;
            }
            return current;
        });
    }

    private void signalTaskHashLocal(String hash) {
        var taskId = taskHashes.get(hash);
        if (taskId != null) signalTaskLocal(taskId);
    }

    private void signalGlobalLocal() {
        if (closed.get()) return;
        signalState(global);
    }

    private static void signalState(ChangeState state) {
        state.lock.lock();
        try {
            state.sequence.incrementAndGet();
            state.changed.signalAll();
        } finally {
            state.lock.unlock();
        }
    }

    private static String normalizeTaskId(String taskId) {
        return taskId == null ? "" : taskId.trim();
    }

    private static String hashTaskId(String taskId) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(taskId.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        signalState(global);
        taskStates.values().forEach(state -> signalState(state));
        var connection = listenerConnection.getAndSet(null);
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // Closing the listener is best effort; the pool owns the
                // connection once the listener loop exits.
            }
        }
        database.shutdownNow();
    }

    private static final class ChangeState {
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition changed = lock.newCondition();
        private final AtomicLong sequence = new AtomicLong();
        private final AtomicInteger references = new AtomicInteger();
    }
}
