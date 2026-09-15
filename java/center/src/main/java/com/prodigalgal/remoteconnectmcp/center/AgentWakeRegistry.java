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
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.web.socket.CloseStatus;

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
    private static final int MAX_TOTAL_SESSIONS = 1024;
    private static final int MAX_SESSIONS_PER_MACHINE = 8;
    // pgjdbc's zero-timeout notification overload is a non-blocking probe,
    // not an infinite wait. A bounded socket wait avoids an idle hot loop
    // while still letting the reconnect path notice a broken connection.
    private static final int NOTIFICATION_WAIT_MILLIS = 30_000;
    private final Map<String, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();
    private final Map<String, WaitState> waitStates = new ConcurrentHashMap<>();
    private final ExecutorService sender = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService database = Executors.newVirtualThreadPerTaskExecutor();
    private final DataSource dataSource;
    private final String instanceId = UUID.randomUUID().toString();
    private final Set<String> pendingDatabaseSignals = ConcurrentHashMap.newKeySet();
    private final AtomicInteger sessionCount = new AtomicInteger();
    private final AtomicReference<Connection> listenerConnection = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public AgentWakeRegistry(ObjectProvider<DataSource> dataSourceProvider) {
        this.dataSource = dataSourceProvider == null ? null : dataSourceProvider.getIfAvailable();
        if (this.dataSource != null) {
            database.execute(this::listenLoop);
        }
    }

    public boolean register(String machineId, WebSocketSession session) {
        if (closed.get() || machineId == null || machineId.isBlank() || session == null) return false;
        var normalized = machineId.trim();
        var connected = sessions.computeIfAbsent(normalized, ignored -> ConcurrentHashMap.newKeySet());
        synchronized (connected) {
            if (connected.contains(session)) return true;
            if (connected.size() >= MAX_SESSIONS_PER_MACHINE || !reserveSession()) {
                if (connected.isEmpty()) sessions.remove(normalized, connected);
                rejectSession(session);
                return false;
            }
            if (!connected.add(session)) {
                releaseSession();
                return false;
            }
            return true;
        }
    }

    public void unregister(String machineId, WebSocketSession session) {
        if (machineId == null || session == null) return;
        var normalized = machineId.trim();
        var connected = sessions.get(normalized);
        if (connected == null) return;
        if (connected.remove(session)) sessionCount.updateAndGet(value -> Math.max(0, value - 1));
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
        long sequence;
        state.lock.lock();
        try {
            sequence = state.sequence.incrementAndGet();
            state.changed.signalAll();
        } finally {
            state.lock.unlock();
        }
        var connected = sessions.get(normalized);
        if (connected != null && !connected.isEmpty()) {
            for (var session : Set.copyOf(connected)) {
                var hintSequence = sequence;
                sender.execute(() -> send(normalized, session, hintSequence));
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

    private void send(String machineId, WebSocketSession session, long sequence) {
        if (!session.isOpen()) {
            unregister(machineId, session);
            return;
        }
        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage("{\"type\":\"wake\",\"sequence\":" + sequence + "}"));
                }
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
                    // Wait in the driver on the PostgreSQL socket. A
                    // notification returns immediately; the bounded timeout
                    // is only a liveness check for a dead socket and is not a
                    // fleet/task polling interval.
                    var notifications = postgres.getNotifications(NOTIFICATION_WAIT_MILLIS);
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
        sessionCount.set(0);
        waitStates.clear();
    }

    private static void rejectSession(WebSocketSession session) {
        try {
            if (session.isOpen()) session.close(CloseStatus.POLICY_VIOLATION);
        } catch (IOException | RuntimeException ignored) {
            // The peer may already have disconnected; the bounded admission
            // decision is still complete and no retry is needed.
        }
    }

    private boolean reserveSession() {
        while (true) {
            var current = sessionCount.get();
            if (current >= MAX_TOTAL_SESSIONS) return false;
            if (sessionCount.compareAndSet(current, current + 1)) return true;
        }
    }

    private void releaseSession() {
        sessionCount.updateAndGet(value -> Math.max(0, value - 1));
    }

    private static final class WaitState {
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition changed = lock.newCondition();
        private final AtomicLong sequence = new AtomicLong();
        private final AtomicLong references = new AtomicLong();
    }
}
