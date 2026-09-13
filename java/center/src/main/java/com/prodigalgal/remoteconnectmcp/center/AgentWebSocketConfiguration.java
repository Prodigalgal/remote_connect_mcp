package com.prodigalgal.remoteconnectmcp.center;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** Optional WebSocket wake channel; HTTPS long-poll remains the default path. */
@Configuration
@EnableWebSocket
public class AgentWebSocketConfiguration implements WebSocketConfigurer {
    private final AgentWebSocketHandler handler;

    public AgentWebSocketConfiguration(AgentWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/agent/v1/ws").setAllowedOrigins();
    }
}
