package com.prodigalgal.remoteconnectmcp.center;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/** Ensures the optional WebSocket channel is a real Spring MVC endpoint. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "rcm.persistence.mode=memory",
                "rcm.agent-websocket.enabled=true",
                "rcm.version=websocket-context-test"
        })
class AgentWebSocketContextTest {
    @Autowired
    private ApplicationContext context;

    @Test
    void enabledChannelCreatesHandlerAndWakeRegistry() {
        assertNotNull(context.getBean(AgentWakeRegistry.class));
        assertNotNull(context.getBean(AgentWebSocketHandler.class));
    }
}
