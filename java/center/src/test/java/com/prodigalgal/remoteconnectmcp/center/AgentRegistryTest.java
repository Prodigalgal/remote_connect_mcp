package com.prodigalgal.remoteconnectmcp.center;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.prodigalgal.remoteconnectmcp.protocol.PollRequest;
import com.prodigalgal.remoteconnectmcp.protocol.AgentMetadata;
import com.prodigalgal.remoteconnectmcp.protocol.AgentRuntimeDescriptor;
import com.prodigalgal.remoteconnectmcp.protocol.RegisterRequest;
import com.prodigalgal.remoteconnectmcp.protocol.ScopeMode;
import java.util.ArrayList;
import java.util.HexFormat;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.ResultSetExtractor;

class AgentRegistryTest {
    @Test
    void registersAndPollsWithSeparateDailyToken() {
        var registry = AgentRegistry.forTest("enroll-test");
        var request = new RegisterRequest("command-agent", "host-a", "host-a", "linux", "amd64", "dev", "/srv", ScopeMode.UNRESTRICTED, null, List.of("command", "durable_tasks"));
        var response = registry.register(request, "enroll-test");

        assertEquals(1, registry.size());
        assertNotEquals("enroll-test", response.token());
        assertEquals(List.of(), registry.poll(response.machineId(), response.token(), new PollRequest(List.of(), 1, List.of("command"))).cancelTaskIds());
        assertThrows(SecurityException.class, () -> registry.poll(response.machineId(), "wrong", new PollRequest(List.of(), 1, List.of("command"))));
        assertThrows(SecurityException.class, () -> registry.poll(null, response.token(), new PollRequest(List.of(), 1, List.of("command"))));
    }

    @Test
    void reEnrollmentWithTheSameNameReusesMachineIdentityAndInvalidatesOldToken() {
        var registry = AgentRegistry.forTest("enroll-test");
        var request = new RegisterRequest("command-agent", "host-a", "host-a", "linux", "amd64", "dev", "/srv", ScopeMode.UNRESTRICTED, null, List.of("command"));
        var first = registry.register(request, "enroll-test");
        var second = registry.register(request, "enroll-test");

        assertEquals(first.machineId(), second.machineId());
        assertNotEquals(first.token(), second.token());
        assertThrows(SecurityException.class, () -> registry.poll(first.machineId(), first.token(), new PollRequest(List.of(), 1, List.of("command"))));
        registry.poll(second.machineId(), second.token(), new PollRequest(List.of(), 1, List.of("command")));
    }

    @Test
    void keepsTheLatestRuntimeDescriptorInTheMachineProjection() {
        var registry = AgentRegistry.forTest("enroll-test");
        var request = new RegisterRequest("command-agent", "host-a", "host-a", "linux", "amd64", "dev", "/srv", ScopeMode.UNRESTRICTED, null, List.of("command"));
        var response = registry.register(request, "enroll-test");
        var runtime = new AgentRuntimeDescriptor(1, 3, 2, 1,
                16L * 1024 * 1024, 64L * 1024 * 1024, 8, 600, 0, 0, false, true);
        var metadata = new AgentMetadata("command-agent", "host-a", "host-a", "linux", "amd64", "v2", "/srv",
                ScopeMode.UNRESTRICTED, null, List.of("command", "browser"), runtime);

        registry.poll(response.machineId(), response.token(), new PollRequest(List.of(), 2,
                List.of("command", "browser"), metadata));

        var view = registry.findMachine(response.machineId(), java.time.Instant.now()).orElseThrow();
        assertEquals(3, view.runtime().configGeneration());
        assertEquals(2, view.runtime().maxConcurrency());
        assertTrue(view.runtime().browserAdapterConfigured());
    }

    @Test
    void canBuildACompleteBoundedSnapshotBeyondOneAdminPage() {
        var registry = AgentRegistry.forTest("enroll-test");
        for (var index = 0; index < 201; index++) {
            var name = "machine-" + index;
            registry.register(new RegisterRequest(name, name, name, "linux", "amd64", "dev", "/srv",
                    ScopeMode.UNRESTRICTED, null, List.of("command")), "enroll-test");
        }

        assertEquals(201, registry.listAllMachines(java.time.Instant.now()).size());
    }

    @Test
    void persistsRegistrationAndHeartbeatWhenJdbcIsConfigured() {
        var jdbc = new RecordingJdbcTemplate();
        var registry = AgentRegistry.forTest("enroll-test", jdbc);
        var request = new RegisterRequest("command-agent", "host-a", "host-a", "linux", "amd64", "dev", "/srv", ScopeMode.UNRESTRICTED, null, List.of("command"));
        var response = registry.register(request, "enroll-test");
        jdbc.storedTokenHash = sha256Hex(response.token());

        registry.poll(response.machineId(), response.token(), new PollRequest(List.of(), 1, List.of("command")));

        assertEquals(1, registry.size());
        var insertIndex = jdbc.sqls.indexOf(jdbc.sqls.stream().filter(sql -> sql.contains("INSERT INTO rcm_agent")).findFirst().orElseThrow());
        assertTrue(insertIndex >= 0);
        assertEquals(response.machineId(), jdbc.updateArguments.get(0)[0]);
        assertEquals("[\"command\"]", jdbc.updateArguments.get(0)[10]);
        assertTrue(jdbc.sqls.stream().anyMatch(sql -> sql.startsWith("UPDATE rcm_agent SET last_seen_at")));
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private final List<String> sqls = new ArrayList<>();
        private final List<Object[]> updateArguments = new ArrayList<>();
        private String storedTokenHash;

        @Override
        public int update(String sql, Object... args) {
            sqls.add(sql);
            updateArguments.add(args);
            return 1;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T query(String sql, PreparedStatementSetter pss, ResultSetExtractor<T> rse) {
            sqls.add(sql);
            return (T) storedTokenHash;
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType) {
            sqls.add(sql);
            return requiredType.cast(1L);
        }
    }
}
