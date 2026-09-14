package com.prodigalgal.remoteconnectmcp.center;

import com.prodigalgal.remoteconnectmcp.protocol.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

/** Authenticated, hint-only Agent WebSocket endpoint. */
@Component
final class AgentWebSocketHandler extends TextWebSocketHandler {
    private static final String MACHINE_ATTRIBUTE = AgentWebSocketHandler.class.getName() + ".machineId";
    private static final int MAX_MESSAGE_BYTES = 4096;

    private final AgentRegistry registry;
    private final AgentWakeRegistry wakes;
    private final boolean enabled;

    AgentWebSocketHandler(AgentRegistry registry, AgentWakeRegistry wakes,
                         @Value("${rcm.agent-websocket.enabled:false}") boolean enabled) {
        this.registry = registry;
        this.wakes = wakes;
        this.enabled = enabled;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if (!enabled) {
            session.close(CloseStatus.SERVICE_RESTARTED);
            return;
        }
        var machineId = session.getHandshakeHeaders().getFirst("X-Machine-ID");
        var authorization = session.getHandshakeHeaders().getFirst("Authorization");
        if (!registry.acceptsAgent(machineId, bearerValue(authorization))) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        session.getAttributes().put(MACHINE_ATTRIBUTE, machineId);
        if (wakes.register(machineId, session) && session.isOpen()) {
            session.sendMessage(new TextMessage("{\"type\":\"ready\"}"));
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        var payload = message.getPayload();
        if (payload == null || payload.getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES) {
            session.close(CloseStatus.TOO_BIG_TO_PROCESS);
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            var value = JsonCodec.read(payload.getBytes(StandardCharsets.UTF_8), Map.class);
            var type = value.get("type") == null ? "" : String.valueOf(value.get("type"));
            if ("ping".equals(type)) {
                session.sendMessage(new TextMessage("{\"type\":\"pong\"}"));
            } else if (!"ack".equals(type)) {
                session.close(CloseStatus.POLICY_VIOLATION);
            }
        } catch (RuntimeException exception) {
            session.close(CloseStatus.BAD_DATA);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        var machineId = session.getAttributes().get(MACHINE_ATTRIBUTE);
        if (machineId != null) wakes.unregister(String.valueOf(machineId), session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        session.close(CloseStatus.SERVER_ERROR);
    }

    private static String bearerValue(String authorization) {
        if (authorization == null) return "";
        var parts = authorization.trim().split("\\s+", 2);
        return parts.length == 2 && "Bearer".equalsIgnoreCase(parts[0]) ? parts[1].trim() : "";
    }
}
