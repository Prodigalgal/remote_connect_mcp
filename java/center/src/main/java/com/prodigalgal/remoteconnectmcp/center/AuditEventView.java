package com.prodigalgal.remoteconnectmcp.center;

import java.time.Instant;

/** Redacted, bounded audit projection; command text and credentials never appear here. */
public record AuditEventView(
        String id,
        String eventType,
        String actor,
        String agentId,
        String taskId,
        String scopeMode,
        String risk,
        String outcome,
        String detail,
        Instant createdAt) {
}
