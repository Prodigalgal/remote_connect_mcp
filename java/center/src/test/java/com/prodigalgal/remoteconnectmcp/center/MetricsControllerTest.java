package com.prodigalgal.remoteconnectmcp.center;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.prodigalgal.remoteconnectmcp.protocol.RegisterRequest;
import com.prodigalgal.remoteconnectmcp.protocol.ScopeMode;
import com.prodigalgal.remoteconnectmcp.protocol.TaskCommand;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MetricsControllerTest {
    @Test
    void exposesOnlyLowCardinalityCountersAndRequiresAdminToken() {
        var registry = AgentRegistry.forTest("enroll");
        var registration = registry.register(new RegisterRequest("metrics-agent", "host-metrics", "host-metrics",
                "linux", "amd64", "test", "/tmp", ScopeMode.UNRESTRICTED, null, List.of("command")), "enroll");
        var tasks = new TaskService(registry);
        var task = tasks.create(new CreateTaskRequest(registration.machineId(),
                new TaskCommand("", null, null, "secret-command", "/private/path", Map.of("SECRET", "hidden"),
                        30, null, null), "metrics"));
        tasks.appendOutput(registration.machineId(), task.id(), 0, "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var upgrades = new UpgradeService(registry, tasks, new UpgradeConfig(true, ""));
        var async = new CenterAsyncExecutor();
        try (var audit = new AuditService(null)) {
            var transfers = new ArtifactTransferService(null, null, null, tasks, new AdminTokens());
            var controller = new MetricsController(new AdminTokens(), registry, tasks, upgrades, audit, async, transfers);
            var unauthorized = controller.metrics("Bearer wrong").join();
            assertEquals(401, unauthorized.getStatusCode().value());
            var authorized = controller.metrics("Bearer admin").join();
            assertEquals(200, authorized.getStatusCode().value());
            var body = authorized.getBody();
            assertTrue(body.contains("remote_connect_mcp_machines_total 1"));
            assertTrue(body.contains("remote_connect_mcp_tasks_total{status=\"queued\"} 1"));
            assertTrue(body.contains("remote_connect_mcp_task_output_bytes_total 5"));
            assertTrue(!body.contains("secret-command"));
            assertTrue(!body.contains("/private/path"));
            assertTrue(!body.contains("hidden"));
            assertTrue(body.contains("remote_connect_mcp_tasks_success_ratio 0.0"));
            assertTrue(body.contains("remote_connect_mcp_tasks_queue_depth 1"));
            assertTrue(body.contains("remote_connect_mcp_file_transfers_active 0"));
            assertTrue(body.contains("remote_connect_mcp_audit_queue_depth"));
        } finally {
            async.close();
        }
    }

    private static final class AdminTokens extends CenterTokenConfig {
        @Override
        public boolean acceptsAdmin(String candidate) {
            return "admin".equals(candidate);
        }
    }
}
