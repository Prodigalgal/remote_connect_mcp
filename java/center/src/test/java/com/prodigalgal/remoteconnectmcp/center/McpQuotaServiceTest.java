package com.prodigalgal.remoteconnectmcp.center;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class McpQuotaServiceTest {
    @Test
    void tracksQueuedActiveAndReleasedTaskReservations() {
        var quota = new McpQuotaService();
        var origin = new TaskOrigin("principal-a", "token-a", "conversation-a");

        quota.assertTaskAdmission(origin, "task-1");
        quota.assertTaskAdmission(origin, "task-2");
        assertEquals(0, quota.snapshot(origin.principalId()).activeTasks());
        assertEquals(2, quota.snapshot(origin.principalId()).queuedTasks());
        assertTrue(quota.permitsActivation(origin, "task-1"));

        quota.markTaskActive(origin, "task-1");
        var active = quota.snapshot(origin.principalId());
        assertEquals(1, active.activeTasks());
        assertEquals(1, active.queuedTasks());

        quota.releaseTask(origin, "task-1");
        active = quota.snapshot(origin.principalId());
        assertEquals(0, active.activeTasks());
        assertEquals(1, active.queuedTasks());
        quota.releaseTask(origin, "task-2");
        assertEquals(0, quota.snapshot(origin.principalId()).queuedTasks());
    }

    @Test
    void sessionAdmissionIsIdempotentAndReleasedWithoutAReaper() {
        var quota = new McpQuotaService();
        var origin = new TaskOrigin("principal-b", "token-b", "conversation-b");

        quota.assertSessionAdmission(origin, "session-1");
        quota.assertSessionAdmission(origin, "session-1");
        assertEquals(1, quota.snapshot(origin.principalId()).activeSessions());

        quota.releaseSession(origin, "session-1");
        assertEquals(0, quota.snapshot(origin.principalId()).activeSessions());
    }
}
