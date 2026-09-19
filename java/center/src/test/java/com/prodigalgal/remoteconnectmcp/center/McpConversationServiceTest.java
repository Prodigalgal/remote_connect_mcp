package com.prodigalgal.remoteconnectmcp.center;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class McpConversationServiceTest {
    @Test
    void memoryCorrelationIsTouchedWithoutChangingAuthorization() {
        var service = new McpConversationService();
        var origin = new TaskOrigin("principal-a", "token-a", "conversation-a");
        assertDoesNotThrow(() -> service.touch(origin, "streamable-http"));
        assertDoesNotThrow(() -> service.touch(origin, "streamable-http"));
        assertDoesNotThrow(() -> service.close(origin));
    }

    @Test
    void transportNameIsBounded() {
        var service = new McpConversationService();
        assertThrows(IllegalArgumentException.class,
                () -> service.touch(new TaskOrigin("principal-a", "token-a", "conversation-a"), "not valid"));
    }
}
