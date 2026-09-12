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
import java.util.concurrent.atomic.AtomicReference;
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
 * rows remain the source of truth and HTTPS polling always repairs a missed
 * notification.
 */
@Component
public final class AgentWakeRegistry implements AutoCloseable {
    private static final String CHANNEL = "rcm_agent_wake";
    private static final Logger LOG = Logger.getLogger(AgentWakeRegistry.class.getName());
    private final Map<String, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();
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
        sessions.computeIfAbsent(machineId, ignored -> ConcurrentHashMap.newKeySet()).add(session);
    }

    public void unregister(String machineId, WebSocketSession session) {
        if (machineId == null || session == null) return;
        var connected = sessions.get(machineId);
        if (connected == null) return;
        connected.remove(session);
        if (connected.isEmpty()) sessions.remove(machineId, connected);
    }

    public void signal(String machineId) {
        if (machineId == null || machineId.isBlank()) return;
        signalLocal(machineId);
        if (dataSource == null || closed.get() || !pendingDatabaseSignals.add(machineId)) return;
        database.execute(() -> {
            try {
                publish(machineId);
            } finally {
                pendingDatabaseSignals.remove(machineId);
            }
        });
    }

    private void signalLocal(String machineId) {
        var connected = sessions.get(machineId);
        if (connected == null || connected.isEmpty()) return;
        for (var session : Set.copyOf(connected)) {
            sender.execute(() -> send(machineId, session));
        }
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
                    var notifications = postgres.getNotifications(1000);
                    if (notifications == null) continue;
                    for (var notification : notifications) {
                        dispatchNotification(notification.getParameter());
                    }
                }
            } catch (SQLException | RuntimeException exception) {
                if (!closed.get()) {
                    LOG.log(Level.FINE, "PostgreSQL Agent wake listener unavailable; HTTPS polling remains active", exception);
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
    }
}
