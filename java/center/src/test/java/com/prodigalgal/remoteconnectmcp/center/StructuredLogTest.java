package com.prodigalgal.remoteconnectmcp.center;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class StructuredLogTest {
    @Test
    void rendersAStableLowCardinalityAuditJsonLine() {
        var event = new AuditEventView("audit_1", "task.state", "agent", "machine_1", "task_1",
                "workspace", "normal", "completed", "attempt=1", Instant.parse("2026-09-15T00:00:00Z"));

        var json = StructuredLog.audit(event);

        assertTrue(json.contains("\"schema_version\":1"));
        assertTrue(json.contains("\"event_type\":\"task.state\""));
        assertTrue(json.contains("\"task_id\":\"task_1\""));
        assertTrue(json.contains("\"detail\":\"attempt=1\""));
    }
}
