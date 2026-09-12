package com.prodigalgal.remoteconnectmcp.center;

import com.prodigalgal.remoteconnectmcp.protocol.AgentMetadata;
import com.prodigalgal.remoteconnectmcp.protocol.JsonCodec;
import com.prodigalgal.remoteconnectmcp.protocol.PollRequest;
import com.prodigalgal.remoteconnectmcp.protocol.PollResponse;
import com.prodigalgal.remoteconnectmcp.protocol.ProtocolValidation;
import com.prodigalgal.remoteconnectmcp.protocol.RegisterRequest;
import com.prodigalgal.remoteconnectmcp.protocol.RegisterResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;

/**
 * Registry facade. Authentication and protocol validation stay independent of
 * the storage adapter. Memory mode is retained for protocol tests; when
 * PostgreSQL mode is enabled, registration and heartbeats are written to the
 * Liquibase-managed agent table.
 */
@Service
public final class AgentRegistry {
    private final CenterTokenConfig tokens;
    private final EnrollmentTokenService enrollments;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, RegisteredAgent> agents = new ConcurrentHashMap<>();

    @Autowired
    public AgentRegistry(CenterTokenConfig tokens, ObjectProvider<JdbcTemplate> jdbcProvider,
                         EnrollmentTokenService enrollments, ObjectProvider<TransactionTemplate> transactionProvider) {
        this(tokens, jdbcProvider.getIfAvailable(), enrollments, transactionProvider.getIfAvailable());
    }

    private AgentRegistry(CenterTokenConfig tokens, JdbcTemplate jdbc, EnrollmentTokenService enrollments,
                          TransactionTemplate transactions) {
        this.tokens = tokens;
        this.jdbc = jdbc;
        this.enrollments = enrollments;
        this.transactions = transactions;
    }

    private AgentRegistry(String enrollmentToken) {
        this(new CenterTokenConfigForTest(enrollmentToken), (JdbcTemplate) null, new EnrollmentTokenService(), null);
    }

    public static AgentRegistry forTest(String enrollmentToken) {
        return new AgentRegistry(enrollmentToken);
    }

    static AgentRegistry forTest(String enrollmentToken, JdbcTemplate jdbc) {
        return new AgentRegistry(new CenterTokenConfigForTest(enrollmentToken), jdbc, new EnrollmentTokenService(), null);
    }

    public RegisterResponse register(RegisterRequest request, String enrollmentToken) {
        if (request == null) {
            throw new IllegalArgumentException("registration body is required");
        }
        AgentMetadata metadata = request.metadata();
        ProtocolValidation.validateMetadata(metadata);
        if (!tokens.acceptsEnrollment(enrollmentToken) && !enrollments.consume(enrollmentToken, metadata.name())) {
            throw new SecurityException("invalid enrollment token");
        }
        var token = randomToken();
        var now = Instant.now();
        var tokenHash = hash(token);
        if (jdbc == null) {
            synchronized (agents) {
                var existing = agents.entrySet().stream()
                        .filter(entry -> entry.getValue().metadata().name().equals(metadata.name()))
                        .findFirst();
                var machineId = existing.map(Map.Entry::getKey)
                        .orElseGet(() -> "machine_" + UUID.randomUUID().toString().replace("-", ""));
                agents.put(machineId, new RegisteredAgent(metadata, tokenHash, now));
                return new RegisterResponse(machineId, token);
            }
        } else {
            java.util.function.Supplier<String> persist = () -> {
                var existing = jdbc.query("SELECT agent_id FROM rcm_agent WHERE machine_name = ? ORDER BY updated_at DESC LIMIT 1 FOR UPDATE",
                        ps -> ps.setString(1, metadata.name()), rs -> rs.next() ? rs.getString(1) : null);
                if (existing == null) {
                    existing = "machine_" + UUID.randomUUID().toString().replace("-", "");
                    insertAgent(existing, metadata, tokenHash, now);
                } else {
                    updateAgent(existing, metadata, tokenHash, now);
                }
                return existing;
            };
            String machineId;
            try {
                machineId = transactions == null ? persist.get() : transactions.execute(status -> persist.get());
            } catch (DuplicateKeyException race) {
                // A unique machine-name index closes the registration race
                // between two Center instances.  Re-read the committed row
                // and rotate that identity's daily token instead of exposing
                // a transient 500 or creating a second machine record.
                java.util.function.Supplier<String> recover = () -> {
                    var existing = jdbc.query("SELECT agent_id FROM rcm_agent WHERE machine_name = ? ORDER BY updated_at DESC LIMIT 1 FOR UPDATE",
                            ps -> ps.setString(1, metadata.name()), rs -> rs.next() ? rs.getString(1) : null);
                    if (existing == null) throw race;
                    updateAgent(existing, metadata, tokenHash, now);
                    return existing;
                };
                machineId = transactions == null ? recover.get() : transactions.execute(status -> recover.get());
            }
            return new RegisterResponse(machineId, token);
        }
    }

    public PollResponse poll(String machineId, String agentToken, PollRequest request) {
        if (machineId == null || machineId.isBlank()) {
            throw new SecurityException("invalid agent credentials");
        }
        if (request != null && request.metadata() != null) {
            ProtocolValidation.validateMetadata(request.metadata());
        }
        if (jdbc == null) {
            var agent = agents.get(machineId);
            if (agent == null || !MessageDigest.isEqual(agent.tokenHash(), hash(agentToken))) {
                throw new SecurityException("invalid agent credentials");
            }
            agent.touch();
            if (request != null && request.metadata() != null) agent.updateMetadata(request.metadata());
        } else {
            var storedHash = storedTokenHash(machineId);
            if (storedHash == null || !MessageDigest.isEqual(storedHash.trim().getBytes(StandardCharsets.US_ASCII), hexHash(agentToken).getBytes(StandardCharsets.US_ASCII))) {
                throw new SecurityException("invalid agent credentials");
            }
            jdbc.update("UPDATE rcm_agent SET last_seen_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE agent_id = ?", machineId);
            if (request != null && request.metadata() != null) updateHeartbeatMetadata(machineId, request.metadata());
        }
        return PollResponse.empty();
    }

    private void updateHeartbeatMetadata(String machineId, AgentMetadata metadata) {
        var capabilities = new String(JsonCodec.write(metadata.capabilities()), StandardCharsets.UTF_8);
        jdbc.update("""
                UPDATE rcm_agent SET machine_name = ?, host_id = ?, hostname = ?, os = ?, arch = ?, version = ?,
                    default_cwd = ?, scope_mode = ?, workspace_root = ?, capabilities = CAST(? AS jsonb),
                    updated_at = CURRENT_TIMESTAMP WHERE agent_id = ?
                """, metadata.name(), metadata.hostId(), metadata.hostname(), metadata.os(), metadata.arch(), metadata.version(),
                metadata.defaultCwd(), metadata.scopeMode().wireValue(), metadata.workspaceRoot(), capabilities, machineId);
    }

    public boolean acceptsAgent(String machineId, String agentToken) {
        if (machineId == null || machineId.isBlank()) {
            return false;
        }
        if (jdbc == null) {
            var agent = agents.get(machineId);
            return agent != null && MessageDigest.isEqual(agent.tokenHash(), hash(agentToken));
        }
        var storedHash = storedTokenHash(machineId);
        return storedHash != null && MessageDigest.isEqual(storedHash.trim().getBytes(StandardCharsets.US_ASCII), hexHash(agentToken).getBytes(StandardCharsets.US_ASCII));
    }

    public Optional<MachineView> findMachine(String machineId, Instant now) {
        if (machineId == null || machineId.isBlank()) {
            return Optional.empty();
        }
        if (jdbc == null) {
            var agent = agents.get(machineId);
            return agent == null ? Optional.empty() : Optional.of(agent.view(machineId, now));
        }
        var rows = jdbc.query("""
                SELECT agent_id, machine_name, host_id, hostname, os, arch, version,
                       default_cwd, scope_mode, workspace_root, capabilities,
                       created_at, last_seen_at
                  FROM rcm_agent WHERE agent_id = ?
                """, ps -> ps.setString(1, machineId), (rs, rowNum) -> machineView(rs, now));
        return rows.stream().findFirst();
    }

    public List<MachineView> listMachines(int offset, int limit, Instant now) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be non-negative");
        }
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
        if (jdbc == null) {
            var result = new ArrayList<MachineView>();
            agents.forEach((id, agent) -> result.add(agent.view(id, now)));
            result.sort(java.util.Comparator.comparing(MachineView::name, String.CASE_INSENSITIVE_ORDER));
            if (offset >= result.size()) {
                return List.of();
            }
            return List.copyOf(result.subList(offset, Math.min(result.size(), offset + limit)));
        }
        return List.copyOf(jdbc.query("""
                SELECT agent_id, machine_name, host_id, hostname, os, arch, version,
                       default_cwd, scope_mode, workspace_root, capabilities,
                       created_at, last_seen_at
                  FROM rcm_agent ORDER BY machine_name, agent_id OFFSET ? LIMIT ?
                """, ps -> {
                    ps.setInt(1, offset);
                    ps.setInt(2, limit);
                }, (rs, rowNum) -> machineView(rs, now)));
    }

    private static MachineView machineView(java.sql.ResultSet rs, Instant now) throws java.sql.SQLException {
        var created = rs.getTimestamp("created_at");
        var lastSeen = rs.getTimestamp("last_seen_at");
        var capabilitiesJson = rs.getString("capabilities");
        List<String> capabilities = List.of();
        if (capabilitiesJson != null && !capabilitiesJson.isBlank()) {
            try {
                capabilities = Arrays.asList(JsonCodec.read(capabilitiesJson.getBytes(StandardCharsets.UTF_8), String[].class));
            } catch (RuntimeException ignored) {
                // A malformed legacy capability value should not break an
                // inventory page; the raw value is not exposed to MCP.
            }
        }
        var last = lastSeen == null ? null : lastSeen.toInstant();
        return new MachineView(
                rs.getString("agent_id"), rs.getString("machine_name"), rs.getString("host_id"),
                rs.getString("hostname"), rs.getString("os"), rs.getString("arch"), rs.getString("version"),
                rs.getString("default_cwd"), rs.getString("scope_mode"), rs.getString("workspace_root"),
                capabilities, created == null ? null : created.toInstant(), last,
                last != null && now.minusSeconds(45).isBefore(last));
    }

    public int size() {
        if (jdbc == null) {
            return agents.size();
        }
        var count = jdbc.queryForObject("SELECT COUNT(*) FROM rcm_agent", Long.class);
        return count == null ? 0 : Math.toIntExact(count);
    }

    /** Count heartbeats without loading machine metadata or credentials. */
    public int onlineCount(Instant now) {
        var reference = now == null ? Instant.now() : now;
        if (jdbc == null) {
            var cutoff = reference.minusSeconds(45);
            return Math.toIntExact(agents.values().stream()
                    .filter(agent -> agent.lastSeen != null && !agent.lastSeen.isBefore(cutoff))
                    .count());
        }
        var count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rcm_agent WHERE last_seen_at >= ?",
                Integer.class, java.sql.Timestamp.from(reference.minusSeconds(45)));
        return count == null ? 0 : count;
    }

    private void insertAgent(String machineId, AgentMetadata metadata, byte[] tokenHash, Instant now) {
        var capabilities = new String(JsonCodec.write(metadata.capabilities()), StandardCharsets.UTF_8);
        jdbc.update("""
                INSERT INTO rcm_agent (
                    agent_id, machine_name, host_id, hostname, os, arch, version,
                    default_cwd, scope_mode, workspace_root, capabilities, token_hash,
                    last_seen_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?)
                """,
                machineId,
                metadata.name(),
                metadata.hostId(),
                metadata.hostname(),
                metadata.os(),
                metadata.arch(),
                metadata.version(),
                metadata.defaultCwd(),
                metadata.scopeMode().wireValue(),
                metadata.workspaceRoot(),
                capabilities,
                hexHash(tokenHash),
                java.sql.Timestamp.from(now),
                java.sql.Timestamp.from(now),
                java.sql.Timestamp.from(now));
    }

    private void updateAgent(String machineId, AgentMetadata metadata, byte[] tokenHash, Instant now) {
        var capabilities = new String(JsonCodec.write(metadata.capabilities()), StandardCharsets.UTF_8);
        jdbc.update("""
                UPDATE rcm_agent SET machine_name = ?, host_id = ?, hostname = ?, os = ?, arch = ?, version = ?,
                    default_cwd = ?, scope_mode = ?, workspace_root = ?, capabilities = CAST(? AS jsonb), token_hash = ?,
                    last_seen_at = ?, updated_at = ? WHERE agent_id = ?
                """, metadata.name(), metadata.hostId(), metadata.hostname(), metadata.os(), metadata.arch(), metadata.version(),
                metadata.defaultCwd(), metadata.scopeMode().wireValue(), metadata.workspaceRoot(), capabilities, hexHash(tokenHash),
                java.sql.Timestamp.from(now), java.sql.Timestamp.from(now), machineId);
    }

    private String storedTokenHash(String machineId) {
        return jdbc.query("SELECT token_hash FROM rcm_agent WHERE agent_id = ?", ps -> ps.setString(1, machineId), rs -> rs.next() ? rs.getString(1) : null);
    }

    private String randomToken() {
        var bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] hash(String value) {
        if (value == null) {
            value = "";
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String hexHash(String value) {
        return hexHash(hash(value));
    }

    private static String hexHash(byte[] value) {
        return java.util.HexFormat.of().formatHex(value);
    }

    private static final class RegisteredAgent {
        private volatile AgentMetadata metadata;
        private final byte[] tokenHash;
        private volatile Instant lastSeen;

        private RegisteredAgent(AgentMetadata metadata, byte[] tokenHash, Instant lastSeen) {
            this.metadata = metadata;
            this.tokenHash = tokenHash;
            this.lastSeen = lastSeen;
        }

        private byte[] tokenHash() {
            return tokenHash;
        }

        private AgentMetadata metadata() {
            return metadata;
        }

        private void touch() {
            lastSeen = Instant.now();
        }

        private void updateMetadata(AgentMetadata value) {
            metadata = value;
        }

        private MachineView view(String id, Instant now) {
            return new MachineView(id, metadata.name(), metadata.hostId(), metadata.hostname(), metadata.os(), metadata.arch(), metadata.version(),
                    metadata.defaultCwd(), metadata.scopeMode().wireValue(), metadata.workspaceRoot(), metadata.capabilities(), lastSeen, lastSeen,
                    lastSeen != null && now.minusSeconds(45).isBefore(lastSeen));
        }
    }

    private static final class CenterTokenConfigForTest extends CenterTokenConfig {
        private final String token;

        private CenterTokenConfigForTest(String token) {
            this.token = token;
        }

        @Override
        public boolean acceptsEnrollment(String candidate) {
            return token != null && token.equals(candidate);
        }
    }
}
