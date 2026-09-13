package com.prodigalgal.remoteconnectmcp.center;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.prodigalgal.remoteconnectmcp.protocol.AgentMetadata;
import com.prodigalgal.remoteconnectmcp.protocol.JsonCodec;
import com.prodigalgal.remoteconnectmcp.protocol.PollRequest;
import com.prodigalgal.remoteconnectmcp.protocol.ScopeMode;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentControllerTest {
    @Test
    void decodesLegacyAgentMetadataHeaderIntoHeartbeatRequest() {
        var metadata = new AgentMetadata("agent", "host", "node", "linux", "arm64", "v1.3.4",
                "/srv", ScopeMode.UNRESTRICTED, null, List.of("command", "durable_tasks"));
        var header = Base64.getUrlEncoder().withoutPadding().encodeToString(JsonCodec.write(metadata));

        var request = AgentController.withHeaderMetadata(new PollRequest(List.of(), 1, List.of("command")), header);

        assertEquals("v1.3.4", request.metadata().version());
        assertEquals(List.of("command", "durable_tasks"), request.metadata().capabilities());
        assertEquals(1, request.availableSlots());
    }

    @Test
    void keepsBodyMetadataWhenBothWireFormsArePresent() {
        var bodyMetadata = new AgentMetadata("body", "host", "node", "linux", "amd64", "body-version",
                "/srv", ScopeMode.UNRESTRICTED, null, List.of("command"));
        var headerMetadata = new AgentMetadata("header", "host", "node", "linux", "amd64", "header-version",
                "/srv", ScopeMode.UNRESTRICTED, null, List.of("command"));
        var header = Base64.getUrlEncoder().withoutPadding().encodeToString(JsonCodec.write(headerMetadata));

        var request = AgentController.withHeaderMetadata(new PollRequest(List.of(), 1, List.of("command"), bodyMetadata), header);

        assertEquals("body", request.metadata().name());
        assertEquals("body-version", request.metadata().version());
    }

    @Test
    void rejectsMalformedOrOversizedMetadataHeader() {
        assertThrows(IllegalArgumentException.class,
                () -> AgentController.withHeaderMetadata(new PollRequest(List.of(), 1, List.of()), "not-base64"));
        assertThrows(IllegalArgumentException.class,
                () -> AgentController.withHeaderMetadata(new PollRequest(List.of(), 1, List.of()), "A".repeat(16 * 1024 + 1)));
    }
}
