package com.prodigalgal.remoteconnectmcp.center;

import com.prodigalgal.remoteconnectmcp.protocol.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

/** Small JSON-lines projection for external stdout/journal collectors. */
final class StructuredLog {
    private StructuredLog() {
    }

    static String audit(AuditEventView event) {
        var value = new LinkedHashMap<String, Object>();
        value.put("schema_version", 1);
        value.put("service", "remote-connect-mcp-center");
        value.put("event", "audit");
        put(value, "event_type", event.eventType());
        put(value, "actor", event.actor());
        put(value, "agent_id", event.agentId());
        put(value, "task_id", event.taskId());
        put(value, "scope_mode", event.scopeMode());
        put(value, "risk", event.risk());
        put(value, "outcome", event.outcome());
        put(value, "detail", event.detail());
        put(value, "created_at", event.createdAt() == null ? null : event.createdAt().toString());
        return new String(JsonCodec.write(value), StandardCharsets.UTF_8);
    }

    private static void put(LinkedHashMap<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) target.put(key, value);
    }
}
