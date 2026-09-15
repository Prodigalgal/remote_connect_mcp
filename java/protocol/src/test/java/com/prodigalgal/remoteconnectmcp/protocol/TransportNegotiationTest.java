package com.prodigalgal.remoteconnectmcp.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import org.junit.jupiter.api.Test;

class TransportNegotiationTest {
    @Test
    void prefersAnAdvertisedServerCapabilityAndFallsBackToHttps() {
        assertEquals(TransportNegotiation.WEBSOCKET,
                TransportNegotiation.select("https, websocket", "websocket",
                        Set.of(TransportNegotiation.HTTPS, TransportNegotiation.WEBSOCKET)));
        assertEquals(TransportNegotiation.HTTPS,
                TransportNegotiation.select("quic", "quic", Set.of(TransportNegotiation.HTTPS)));
        assertEquals(TransportNegotiation.HTTPS,
                TransportNegotiation.select(null, null, Set.of(TransportNegotiation.HTTPS)));
    }

    @Test
    void normalizesUnknownAndDuplicateCapabilitiesWithoutEchoingThem() {
        assertEquals("https,websocket",
                TransportNegotiation.normalize(" HTTPS, websocket, unknown, https "));
        assertThrows(IllegalArgumentException.class,
                () -> TransportNegotiation.normalize("x".repeat(129)));
    }
}
