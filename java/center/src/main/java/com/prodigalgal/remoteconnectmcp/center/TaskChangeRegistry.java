package com.prodigalgal.remoteconnectmcp.center;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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
 * <p>The sequence is intentionally global and carries no task content. A
 * waiter always re-reads its task row after waking, so an unrelated change or
 * a missed notification is harmless. PostgreSQL LISTEN/NOTIFY only removes
 * the fixed-interval database polling in the common multi-replica path; the
 * task row remains the source of truth and callers still use a bounded
 * deadline.</p>
 */
@Component
public final class TaskChangeRegistry implements AutoCloseable {
    private static final String CHANNEL = "rcm_task_change";
    private static final Logger LOG = Logger.getLogger(TaskChangeRegistry.class.getName());

    private final DataSource dataSource;
    private final ExecutorService database = Executors.newVirtualThreadPerTaskExecutor();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicReference<Connection> listenerConnection = new AtomicReference<>();
    private final AtomicBoolean pendingPublish = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final String instanceId = UUID.randomUUID().toString();

    public TaskChangeRegistry(ObjectProvider<DataSource> dataSourceProvider) {
        this.dataSource = dataSourceProvider == null ? null : dataSourceProvider.getIfAvailable();
        if (this.dataSource != null) {
            database.execute(this::listenLoop);
        }
    }

    /** Current local change sequence, captured immediately before a row read. */
    public long version() {
        return sequence.get();
    }

    /**
     * Wake local waiters and best-effort publish a cross-replica hint. The task
     * identifier is accepted for call-site clarity but is not sent to the
     * database; keeping the payload opaque prevents task IDs from becoming
     * observable in database notification logs.
     */
    public void signal(String taskId) {
        if (taskId == null || taskId.isBlank() || closed.get()) return;
        lock.lock();
        try {
            sequence.incrementAndGet();
            changed.signalAll();
        } finally {
            lock.unlock();
        }
        if (dataSource == null || !pendingPublish.compareAndSet(false, true)) return;
        database.execute(() -> {
            try {
                publish();
            } finally {
                pendingPublish.set(false);
            }
        });
    }

    /** Await a sequence change without holding a JDBC connection. */
    public void awaitChange(long observed, long timeoutNanos) throws InterruptedException {
        if (timeoutNanos <= 0 || closed.get()) return;
        var deadline = System.nanoTime() + timeoutNanos;
        lock.lockInterruptibly();
        try {
            while (!closed.get() && sequence.get() == observed) {
                var remaining = deadline - System.nanoTime();
                if (remaining <= 0) return;
                changed.awaitNanos(remaining);
            }
        } finally {
            lock.unlock();
        }
    }

    private void publish() {
        // Notification loss is safe: TaskService re-reads the row and has a
        // deadline-based fallback when this best-effort hint is unavailable.
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT pg_notify(?, ?)")) {
            statement.setString(1, CHANNEL);
            statement.setString(2, instanceId);
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
                    var notifications = postgres.getNotifications(1000);
                    if (notifications == null) continue;
                    for (var notification : notifications) {
                        if (!instanceId.equals(notification.getParameter())) signalLocal();
                    }
                }
            } catch (SQLException | RuntimeException exception) {
                if (!closed.get()) {
                    LOG.log(Level.FINE, "PostgreSQL task change listener unavailable; bounded row checks remain active", exception);
                }
                delayMillis = Math.min(30_000L, delayMillis * 2L);
                sleep(delayMillis);
            } finally {
                listenerConnection.set(null);
            }
        }
    }

    private void signalLocal() {
        if (closed.get()) return;
        lock.lock();
        try {
            sequence.incrementAndGet();
            changed.signalAll();
        } finally {
            lock.unlock();
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
        lock.lock();
        try {
            changed.signalAll();
        } finally {
            lock.unlock();
        }
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
}
