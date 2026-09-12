package com.prodigalgal.remoteconnectmcp.protocol;

import java.time.Instant;

/**
 * Common framing for polling, WebSocket, and the future QUIC transport.
 * Payload schemas remain transport-independent.
 */
public record Envelope(
        String protocolVersion,
        String messageType,
        String agentId,
        String hostId,
        long sequence,
        long configGeneration,
        Instant sentAt) {

    public Envelope {
        protocolVersion = protocolVersion == null || protocolVersion.isBlank() ? "1" : protocolVersion;
        messageType = messageType == null ? "" : messageType;
        sentAt = sentAt == null ? Instant.EPOCH : sentAt;
    }
}
