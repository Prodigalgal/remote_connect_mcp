package com.prodigalgal.remoteconnectmcp.center;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AuditServiceTest {
    @Test
    void memorySinkKeepsNewestRedactedEventsWithinBoundedProjection() throws Exception {
        try (var audit = new AuditService(null)) {
            audit.record("task.state", "agent", "machine-1", "task-1", "low", "completed",
                    "line1\nline2");
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            var events = java.util.List.<AuditEventView>of();
            while (System.nanoTime() < deadline && events.isEmpty()) {
                Thread.sleep(10);
                events = audit.list(null, null, null, 0, 10);
            }
            assertEquals(1, events.size());
            assertEquals("task.state", events.getFirst().eventType());
            assertTrue(events.getFirst().detail().contains("line1 line2"));
        }
    }

    @Test
    void retentionIsExplicitlyBoundedAndRejectsUnboundedRequests() throws Exception {
        try (var audit = new AuditService(null)) {
            audit.record("task.state", "agent", "machine-1", "task-1", "low", "completed", "old");
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < deadline && audit.list(null, null, null, 0, 10).isEmpty()) {
                Thread.sleep(10);
            }
            // A one-day retention window must not remove a freshly written row.
            assertEquals(0, audit.purge(1, 10));
            assertEquals(1, audit.list(null, null, null, 0, 10).size());
            assertThrows(IllegalArgumentException.class, () -> audit.purge(0, 10));
            assertThrows(IllegalArgumentException.class, () -> audit.purge(1, 5001));
        }
    }
}
