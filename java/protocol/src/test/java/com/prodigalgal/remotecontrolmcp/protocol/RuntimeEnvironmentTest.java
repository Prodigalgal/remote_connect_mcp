package com.prodigalgal.remotecontrolmcp.protocol;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeEnvironmentTest {
    @Test
    void existingServiceEnvironmentStillAuthenticatesAndFindsItsIdentity() {
        var environment = Map.of("REMOTE_CONNECT_MCP_AGENT_CENTER_URL", "https://old.example.invalid",
                "REMOTE_CONNECT_MCP_AGENT_STATE_DIR", "/var/lib/existing-agent",
                "REMOTE_CONNECT_MCP_CENTER_ADMIN_TOKEN", "existing-admin");
        assertEquals("https://old.example.invalid", RuntimeEnvironment.get(environment, "REMOTE_CONTROL_MCP_AGENT_CENTER_URL"));
        assertEquals("/var/lib/existing-agent", RuntimeEnvironment.get(environment, "REMOTE_CONTROL_MCP_AGENT_STATE_DIR"));
        assertEquals("existing-admin", RuntimeEnvironment.get(environment, "REMOTE_CONTROL_MCP_CENTER_ADMIN_TOKEN"));
    }

    @Test
    void explicitlyConfiguredCurrentNameWinsIncludingAnEmptyValue() {
        var environment = Map.of("REMOTE_CONNECT_MCP_AGENT_NAME", "old", "REMOTE_CONTROL_MCP_AGENT_NAME", "current",
                "REMOTE_CONNECT_MCP_AGENT_ENROLLMENT_TOKEN", "obsolete", "REMOTE_CONTROL_MCP_AGENT_ENROLLMENT_TOKEN", "");
        assertEquals("current", RuntimeEnvironment.get(environment, "REMOTE_CONTROL_MCP_AGENT_NAME"));
        assertEquals("", RuntimeEnvironment.get(environment, "REMOTE_CONTROL_MCP_AGENT_ENROLLMENT_TOKEN"));
    }

    @Test
    void unrelatedEnvironmentVariablesAreNotRewritten() {
        assertEquals("display", RuntimeEnvironment.get(Map.of("DISPLAY", "display"), "DISPLAY"));
        assertNull(RuntimeEnvironment.get(Map.of("REMOTE_CONNECT_MCP_AGENT_NAME", "old"), "UNRELATED_AGENT_NAME"));
    }
}
