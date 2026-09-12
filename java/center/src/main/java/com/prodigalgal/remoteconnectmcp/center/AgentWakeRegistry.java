package com.prodigalgal.remoteconnectmcp.center;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Low-cost wake-up fan-out for the connected Agent sessions. The local map
 * avoids a database round trip for the common single-replica case; when
 * PostgreSQL is enabled, LISTEN/NOTIFY also bridges sessions connected to a
 * different Center replica. It is only an acceleration hint: PostgreSQL task
 * rows remain the source of truth and the HTTPS long-poll deadline repairs a
 * missed notification.
 */
@Component
public final class AgentWakeRegistry implements AutoCloseable {
    private static final String CHANNEL = "rcm_agent_wake";
    private static final Logger LOG = Logger.getLogger(AgentWakeRegistry.class.getName());
    private final Map<String, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();
    private final Map<String, WaitState> waitStates = new ConcurrentHashMap<>();
    private final ExecutorService sender = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService database = Executors.newVirtualThreadPerTaskExecutor();
    private final DataSource dataSource;
    private final String instanceId = UUID.randomUUID().toString();
    private final Set<String> pendingDatabaseSignals = ConcurrentHashMap.newKeySet();
    private final AtomicReference<Connection> listenerConnection = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public AgentWakeRegistry(ObjectProvider<DataSource> dataSourceProvider) {
        this.dataSource = dataSourceProvider == null ? null : dataSourceProvider.getIfAvailable();
        if (this.dataSource != null) {
            database.execute(this::listenLoop);
        }
    }

    public void register(String machineId, WebSocketSession session) {
        if (machineId == null || machineId.isBlank() || session == null) return;
        sessions.computeIfAbsent(machineId.trim(), ignored -> ConcurrentHashMap.newKeySet()).add(session);
    }

    public void unregister(String machineId, WebSocketSession session) {
        if (machineId == null || session == null) return;
        var normalized = machineId.trim();
        var connected = sessions.get(normalized);
        if (connected == null) return;
        connected.remove(session);
        if (connected.isEmpty()) {
            sessions.remove(normalized, connected);
            waitStates.computeIfPresent(normalized, (ignored, state) ->
                    state.references.get() <= 0 ? null : state);
        }
    }

    /** Capture the per-machine wake sequence before a long-poll row read. */
    public long version(String machineId) {
        if (machineId == null || machineId.isBlank()) return 0L;
        var normalized = machineId.trim();
        var state = waitStates.compute(normalized, (ignored, existing) -> {
            var current = existing == null ? new WaitState() : existing;
            current.references.incrementAndGet();
            return current;
        });
        return state.sequence.get();
    }

    /** Release the short-lived reference acquired by {@link #version(String)}. */
    public void release(String machineId) {
        if (machineId == null || machineId.isBlank()) return;
        var normalized = machineId.trim();
        waitStates.computeIfPresent(normalized, (ignored, state) -> {
            if (state.references.decrementAndGet() <= 0 && !hasSessions(normalized)) {
                return null;
            }
            return state;
        });
    }

    /** Suspend an HTTP long-poll waiter until this machine receives a hint. */
    public void awaitChange(String machineId, long observed, long timeoutNanos) throws InterruptedException {
        if (machineId == null || machineId.isBlank() || timeoutNanos <= 0 || closed.get()) return;
        var state = waitStates.computeIfAbsent(machineId.trim(), ignored -> new WaitState());
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

    public void signal(String machineId) {
        if (machineId == null || machineId.isBlank()) return;
        var normalized = machineId.trim();
        signalLocal(normalized);
        if (dataSource == null || closed.get() || !pendingDatabaseSignals.add(normalized)) return;
        database.execute(() -> {
            try {
                publish(normalized);
            } finally {
                pendingDatabaseSignals.remove(normalized);
            }
        });
    }

    private void signalLocal(String machineId) {
        var normalized = machineId == null ? "" : machineId.trim();
        if (normalized.isBlank()) return;
        var state = waitStates.computeIfAbsent(normalized, ignored -> new WaitState());
        state.lock.lock();
        try {
            state.sequence.incrementAndGet();
            state.changed.signalAll();
        } finally {
            state.lock.unlock();
        }
        var connected = sessions.get(normalized);
        if (connected != null && !connected.isEmpty()) {
            for (var session : Set.copyOf(connected)) {
                sender.execute(() -> send(normalized, session));
            }
        }
        // A control event may arrive for a machine with no active HTTP or
        // WebSocket waiter. Do not retain a condition forever just because a
        // historical machine once emitted a wake hint.
        waitStates.computeIfPresent(normalized, (ignored, current) ->
                current.references.get() <= 0 && !hasSessions(normalized) ? null : current);
    }

    private boolean hasSessions(String machineId) {
        var connected = sessions.get(machineId);
        return connected != null && !connected.isEmpty();
    }

    private void send(String machineId, WebSocketSession session) {
        if (!session.isOpen()) {
            unregister(machineId, session);
            return;
        }
        try {
            synchronized (session) {
                if (session.isOpen()) session.sendMessage(new TextMessage("{\"type\":\"wake\"}"));
            }
        } catch (IOException | RuntimeException exception) {
            LOG.log(Level.FINE, "could not send Agent wake hint", exception);
            unregister(machineId, session);
        }
    }

    private void publish(String machineId) {
        // pg_notify is deliberately best-effort. A transient database outage
        // must not make task creation fail; the Agent's HTTPS poll remains the
        // correctness path and will observe the queued row.
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT pg_notify(?, ?)")) {
            statement.setString(1, CHANNEL);
            statement.setString(2, instanceId + "|" + machineId);
            statement.execute();
        } catch (SQLException | RuntimeException exception) {
            LOG.log(Level.FINE, "could not publish cross-replica Agent wake hint", exception);
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
                    // Block on the PostgreSQL socket until a notification is
                    // available instead of waking every second on every
                    // Center replica.
                    // The no-argument driver API is non-blocking.  The zero
                    // timeout overload blocks forever until a notification or
                    // socket error, so idle replicas consume no wake cycles.
                    var notifications = postgres.getNotifications(0);
                    if (notifications == null) continue;
                    for (var notification : notifications) {
                        dispatchNotification(notification.getParameter());
                    }
                }
            } catch (SQLException | RuntimeException exception) {
                if (!closed.get()) {
                    LOG.log(Level.FINE, "PostgreSQL Agent wake listener unavailable; reconnecting with backoff", exception);
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
        var machineId = payload.substring(separator + 1).trim();
        if (!machineId.isBlank() && machineId.length() <= 256) signalLocal(machineId);
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
        waitStates.values().forEach(state -> {
            state.lock.lock();
            try {
                state.changed.signalAll();
            } finally {
                state.lock.unlock();
            }
        });
        var connection = listenerConnection.getAndSet(null);
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // The DataSource owns the connection after the listener exits.
            }
        }
        database.shutdownNow();
        sender.shutdownNow();
        sessions.clear();
        waitStates.clear();
    }

    private static final class WaitState {
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition changed = lock.newCondition();
        private final AtomicLong sequence = new AtomicLong();
        private final AtomicLong references = new AtomicLong();
    }
}
