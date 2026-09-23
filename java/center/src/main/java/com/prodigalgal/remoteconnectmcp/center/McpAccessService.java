package com.prodigalgal.remoteconnectmcp.center;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.prodigalgal.remoteconnectmcp.protocol.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Principal-to-machine authorization for MCP calls.
 *
 * <p>The configured MCP principal is deliberately the only implicit global
 * grant. User principals require a machine grant (either for a specific machine
 * or the wildcard '*' for all machines) before they can see or execute on a target.
 * PostgreSQL is authoritative in production; the bounded maps are only used by
 * protocol tests and memory mode.</p>
 */
@Service
public final class McpAccessService {
    private static final int MAX_RESOURCE_ID = 180;
    private static final int MAX_SCOPE = 32;
    private static final Set<String> MACHINE_SCOPES = Set.of("read", "execute", "admin");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Map<String, Grant> machineMemory = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public McpAccessService(ObjectProvider<JdbcTemplate> jdbcProvider,
                            ObjectProvider<TransactionTemplate> transactionProvider) {
        this(jdbcProvider == null ? null : jdbcProvider.getIfAvailable(),
                transactionProvider == null ? null : transactionProvider.getIfAvailable());
    }

    McpAccessService() {
        this((JdbcTemplate) null, (TransactionTemplate) null);
    }

    McpAccessService(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    /** Require a machine grant for a user principal. */
    public void authorizeMachine(TaskOrigin origin, String machineId, String action) {
        var principalId = principalId(origin);
        var resource = requiredResource(machineId, "machine_id");
        var required = requiredMachineScope(action);
        if (TaskOrigin.CONFIGURED_PRINCIPAL.equals(principalId)) return;
        if (!hasMachineGrant(principalId, resource, required)) {
            throw new SecurityException("MCP principal is not granted " + required + " access to machine");
        }
    }

    /** Require the machine execution permission of a task contract. */
    public void authorizeExecution(TaskOrigin origin, String machineId) {
        authorizeMachine(origin, machineId, "execute");
    }

    /** Whether a principal may see a machine in a bounded inventory page. */
    public boolean canReadMachine(TaskOrigin origin, String machineId) {
        var principalId = principalId(origin);
        if (TaskOrigin.CONFIGURED_PRINCIPAL.equals(principalId)) return true;
        return hasMachineGrant(principalId, requiredResource(machineId, "machine_id"), "read");
    }

    /** Grant machine scopes. Supports specific machine ID or '*' for all machines. */
    public MachineGrantView grantMachine(String principalId, String machineId,
                                         Set<String> scopes, Instant expiresAt) {
        var principal = requiredPrincipal(principalId);
        var machine = requiredResource(machineId, "machine_id");
        var normalized = normalizeScopes(scopes, MACHINE_SCOPES, Set.of("read", "execute"));
        var grant = new Grant(principal, machine, normalized, expiresAt, Instant.now());
        if (jdbc == null) {
            machineMemory.put(key(principal, machine), grant);
        } else {
            runInTransaction(() -> jdbc.update("""
                    INSERT INTO rcm_machine_grant(principal_id, agent_id, scope_json, expires_at, created_at, updated_at)
                    VALUES (?, ?, CAST(? AS jsonb), ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    ON CONFLICT (principal_id, agent_id) DO UPDATE SET scope_json = EXCLUDED.scope_json,
                        expires_at = EXCLUDED.expires_at, updated_at = CURRENT_TIMESTAMP
                    """, principal, machine, json(normalized), timestamp(expiresAt)));
        }
        return new MachineGrantView(principal, machine, normalized, expiresAt, grant.createdAt(), grant.createdAt());
    }

    public boolean revokeMachine(String principalId, String machineId) {
        var principal = requiredPrincipal(principalId);
        var machine = requiredResource(machineId, "machine_id");
        var changed = machineMemory.remove(key(principal, machine)) != null;
        if (jdbc != null) {
            changed |= jdbc.update("DELETE FROM rcm_machine_grant WHERE principal_id = ? AND agent_id = ?",
                    principal, machine) > 0;
        }
        return changed;
    }

    public List<MachineGrantView> listMachine(String principalId, int offset, int limit) {
        validatePage(offset, limit);
        var principal = principalId == null || principalId.isBlank() ? "" : requiredPrincipal(principalId);
        if (jdbc == null) {
            return machineMemory.values().stream()
                    .filter(value -> principal.isBlank() || value.principalId().equals(principal))
                    .sorted(java.util.Comparator.comparing(Grant::resourceId))
                    .skip(offset).limit(limit).map(this::machineView).toList();
        }
        var sql = """
                SELECT principal_id, agent_id, scope_json, expires_at, created_at, updated_at
                  FROM rcm_machine_grant %s ORDER BY agent_id, principal_id OFFSET ? LIMIT ?
                """.formatted(principal.isBlank() ? "" : "WHERE principal_id = ?");
        return jdbc.query(sql, ps -> {
            var i = 1;
            if (!principal.isBlank()) ps.setString(i++, principal);
            ps.setInt(i++, offset);
            ps.setInt(i, limit);
        }, (rs, row) -> new MachineGrantView(rs.getString("principal_id"), rs.getString("agent_id"),
                parseScopes(rs.getString("scope_json")), instant(rs.getTimestamp("expires_at")),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at"))));
    }

    public int machineCount(String principalId) {
        return count("rcm_machine_grant", "agent_id", principalId);
    }

    private boolean hasMachineGrant(String principal, String machine, String required) {
        if (jdbc == null) {
            var grant = machineMemory.get(key(principal, machine));
            if (grant != null && grant.active() && grants(grant.scopes(), required, MACHINE_SCOPES)) {
                return true;
            }
            var wildcard = machineMemory.get(key(principal, "*"));
            return wildcard != null && wildcard.active() && grants(wildcard.scopes(), required, MACHINE_SCOPES);
        }
        try {
            var value = jdbc.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM rcm_machine_grant
                         WHERE principal_id = ? AND (agent_id = ? OR agent_id = '*')
                           AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
                           AND (jsonb_exists(scope_json, ?) OR jsonb_exists(scope_json, 'admin'))
                    )
                    """, Boolean.class, principal, machine, required);
            return Boolean.TRUE.equals(value);
        } catch (DataAccessException failure) {
            throw new SecurityException("machine access policy is unavailable", failure);
        }
    }

    private int count(String table, String ignored, String principalId) {
        var principal = principalId == null || principalId.isBlank() ? "" : requiredPrincipal(principalId);
        if (jdbc == null) {
            var values = machineMemory.values();
            return Math.toIntExact(values.stream().filter(value -> principal.isBlank() || value.principalId().equals(principal)).count());
        }
        var sql = principal.isBlank() ? "SELECT COUNT(*) FROM " + table
                : "SELECT COUNT(*) FROM " + table + " WHERE principal_id = ?";
        var value = principal.isBlank() ? jdbc.queryForObject(sql, Long.class)
                : jdbc.queryForObject(sql, Long.class, principal);
        return value == null ? 0 : Math.toIntExact(value);
    }

    private void runInTransaction(Runnable operation) {
        if (transactions == null) {
            operation.run();
        } else {
            transactions.executeWithoutResult(status -> operation.run());
        }
    }

    private static boolean grants(Set<String> values, String required, Set<String> supported) {
        if (values.contains("admin")) return true;
        if ("read".equals(required)) return values.contains("read") || values.contains("write");
        return values.contains(required);
    }

    private static String principalId(TaskOrigin origin) {
        return requiredPrincipal(origin == null ? null : origin.principalId());
    }

    private static String requiredPrincipal(String value) {
        if (value == null || value.isBlank()) throw new SecurityException("MCP principal is missing");
        var normalized = value.trim();
        if (normalized.length() > MAX_RESOURCE_ID || normalized.indexOf('\u0000') >= 0
                || normalized.indexOf('\r') >= 0 || normalized.indexOf('\n') >= 0) {
            throw new SecurityException("invalid MCP principal identifier");
        }
        return normalized;
    }

    private static String requiredResource(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        var normalized = value.trim();
        if (normalized.length() > MAX_RESOURCE_ID || normalized.indexOf('\u0000') >= 0
                || normalized.indexOf('\r') >= 0 || normalized.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("invalid " + name);
        }
        return normalized;
    }

    private static String requiredMachineScope(String action) {
        if (action == null || action.isBlank()) throw new IllegalArgumentException("action is required");
        var normalized = action.trim().toLowerCase();
        if (!MACHINE_SCOPES.contains(normalized)) {
            throw new IllegalArgumentException("unsupported machine action: " + action);
        }
        return normalized;
    }

    private static Set<String> normalizeScopes(Set<String> scopes, Set<String> supported, Set<String> fallback) {
        var values = scopes == null || scopes.isEmpty() ? fallback : scopes;
        var normalized = new LinkedHashSet<String>();
        for (var scope : values) {
            if (scope == null || scope.isBlank()) continue;
            var value = scope.trim().toLowerCase();
            if (value.length() > MAX_SCOPE || !supported.contains(value)) {
                throw new IllegalArgumentException("unsupported scope: " + scope);
            }
            normalized.add(value);
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("at least one valid scope is required");
        }
        return Set.copyOf(normalized);
    }

    private static void validatePage(int offset, int limit) {
        if (offset < 0) throw new IllegalArgumentException("offset must be non-negative");
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
    }

    private static String key(String principal, String resource) {
        return principal + "\u0000" + resource;
    }

    private static String json(Set<String> values) {
        return new String(JsonCodec.write(values), StandardCharsets.UTF_8);
    }

    private static Set<String> parseScopes(String value) {
        if (value == null || value.isBlank()) return Set.of();
        try {
            var list = JsonCodec.read(value.getBytes(StandardCharsets.UTF_8), List.class);
            var result = new LinkedHashSet<String>();
            for (var item : list) if (item != null) result.add(String.valueOf(item));
            return Set.copyOf(result);
        } catch (RuntimeException ignored) {
            return Set.of();
        }
    }

    private static java.sql.Timestamp timestamp(Instant instant) {
        return instant == null ? null : java.sql.Timestamp.from(instant);
    }

    private static Instant instant(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private MachineGrantView machineView(Grant grant) {
        return new MachineGrantView(grant.principalId(), grant.resourceId(), grant.scopes(),
                grant.expiresAt(), grant.createdAt(), grant.updatedAt());
    }

    private record Grant(String principalId, String resourceId, Set<String> scopes,
                         Instant expiresAt, Instant createdAt, Instant updatedAt) {
        Grant(String principalId, String resourceId, Set<String> scopes, Instant expiresAt, Instant createdAt) {
            this(principalId, resourceId, scopes, expiresAt, createdAt, createdAt);
        }

        boolean active() {
            return expiresAt == null || expiresAt.isAfter(Instant.now());
        }
    }

    public record MachineGrantView(@JsonProperty("principal_id") String principalId,
                                   @JsonProperty("machine_id") String machineId,
                                   @JsonProperty("scopes") Set<String> scopes,
                                   @JsonProperty("expires_at") Instant expiresAt,
                                   @JsonProperty("created_at") Instant createdAt,
                                   @JsonProperty("updated_at") Instant updatedAt) {
    }
}
