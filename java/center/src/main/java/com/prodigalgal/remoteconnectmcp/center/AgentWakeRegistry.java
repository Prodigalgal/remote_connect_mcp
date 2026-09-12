package com.prodigalgal.remoteconnectmcp.center;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * In-memory wake-up fan-out for one Center instance. It is only an
 * acceleration hint; PostgreSQL remains the source of truth and a reconnect
 * always falls back to normal polling.
 */
@Component
public final class AgentWakeRegistry implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(AgentWakeRegistry.class.getName());
    private final Map<String, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();
    private final ExecutorService sender = Executors.newVirtualThreadPerTaskExecutor();

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

    @Override
    public void close() {
        sender.shutdownNow();
        sessions.clear();
    }
}
