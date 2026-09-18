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
 * Principal-to-machine/project authorization for MCP calls.
 *
 * <p>The configured MCP principal is deliberately the only implicit global
 * grant.  Every user principal must have an explicit machine grant before it
 * can see or use a target, and a project-scoped task additionally needs an
 * explicit project membership.  PostgreSQL is authoritative in production;
 * the bounded maps are only used by protocol tests and memory mode.</p>
 */
@Service
public final class McpAccessService {
    private static final int MAX_RESOURCE_ID = 180;
    private static final int MAX_SCOPE = 32;
    private static final Set<String> MACHINE_SCOPES = Set.of("read", "execute", "admin");
    private static final Set<String> PROJECT_SCOPES = Set.of("read", "write", "admin");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Map<String, Grant> machineMemory = new ConcurrentHashMap<>();
    private final Map<String, Grant> projectMemory = new ConcurrentHashMap<>();

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

    /** Require a project membership for a user principal. */
    public void authorizeProject(TaskOrigin origin, String projectId, String action) {
        var principalId = principalId(origin);
        var resource = requiredResource(projectId, "project_id");
        var required = requiredProjectScope(action);
        if (TaskOrigin.CONFIGURED_PRINCIPAL.equals(principalId)) return;
        if (!hasProjectGrant(principalId, resource, required)) {
            throw new SecurityException("MCP principal is not a project member with " + required + " access");
        }
    }

    /** Require the machine and, when present, project part of a task contract. */
    public void authorizeExecution(TaskOrigin origin, String machineId, String projectId) {
        authorizeMachine(origin, machineId, "execute");
        if (projectId != null && !projectId.isBlank()) {
            // A task can mutate files even when the model describes it as a
            // read-like command.  Treat all project execution as write access
            // so the grant is explicit and predictable.
            authorizeProject(origin, projectId, "write");
        }
    }

    /** Whether a principal may see a machine in a bounded inventory page. */
    public boolean canReadMachine(TaskOrigin origin, String machineId) {
        var principalId = principalId(origin);
        if (TaskOrigin.CONFIGURED_PRINCIPAL.equals(principalId)) return true;
        return hasMachineGrant(principalId, requiredResource(machineId, "machine_id"), "read");
    }

    /** Whether a principal may see a project in a bounded inventory page. */
    public boolean canReadProject(TaskOrigin origin, String projectId) {
        var principalId = principalId(origin);
        if (TaskOrigin.CONFIGURED_PRINCIPAL.equals(principalId)) return true;
        return hasProjectGrant(principalId, requiredResource(projectId, "project_id"), "read");
    }

    /** Grant machine scopes. Plaintext tokens never enter this service. */
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

    /** Grant project membership. Plaintext tokens never enter this service. */
    public ProjectMemberView grantProject(String principalId, String projectId,
                                          Set<String> scopes, Instant expiresAt) {
        var principal = requiredPrincipal(principalId);
        var project = requiredResource(projectId, "project_id");
        var normalized = normalizeScopes(scopes, PROJECT_SCOPES, Set.of("read"));
        var grant = new Grant(principal, project, normalized, expiresAt, Instant.now());
        if (jdbc == null) {
            projectMemory.put(key(principal, project), grant);
        } else {
            runInTransaction(() -> jdbc.update("""
                    INSERT INTO rcm_project_member(project_id, principal_id, role, scope_json, expires_at, created_at, updated_at)
                    VALUES (?, ?, ?, CAST(? AS jsonb), ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    ON CONFLICT (project_id, principal_id) DO UPDATE SET role = EXCLUDED.role,
                        scope_json = EXCLUDED.scope_json, expires_at = EXCLUDED.expires_at,
                        updated_at = CURRENT_TIMESTAMP
                    """, project, principal, projectRole(normalized), json(normalized), timestamp(expiresAt)));
        }
        return new ProjectMemberView(project, principal, projectRole(normalized), normalized,
                expiresAt, grant.createdAt(), grant.createdAt());
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

    public boolean revokeProject(String principalId, String projectId) {
        var principal = requiredPrincipal(principalId);
        var project = requiredResource(projectId, "project_id");
        var changed = projectMemory.remove(key(principal, project)) != null;
        if (jdbc != null) {
            changed |= jdbc.update("DELETE FROM rcm_project_member WHERE principal_id = ? AND project_id = ?",
                    principal, project) > 0;
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

    public List<ProjectMemberView> listProject(String principalId, int offset, int limit) {
        validatePage(offset, limit);
        var principal = principalId == null || principalId.isBlank() ? "" : requiredPrincipal(principalId);
        if (jdbc == null) {
            return projectMemory.values().stream()
                    .filter(value -> principal.isBlank() || value.principalId().equals(principal))
                    .sorted(java.util.Comparator.comparing(Grant::resourceId))
                    .skip(offset).limit(limit).map(this::projectView).toList();
        }
        var sql = """
                SELECT project_id, principal_id, role, scope_json, expires_at, created_at, updated_at
                  FROM rcm_project_member %s ORDER BY project_id, principal_id OFFSET ? LIMIT ?
                """.formatted(principal.isBlank() ? "" : "WHERE principal_id = ?");
        return jdbc.query(sql, ps -> {
            var i = 1;
            if (!principal.isBlank()) ps.setString(i++, principal);
            ps.setInt(i++, offset);
            ps.setInt(i, limit);
        }, (rs, row) -> new ProjectMemberView(rs.getString("project_id"), rs.getString("principal_id"),
                rs.getString("role"), parseScopes(rs.getString("scope_json")), instant(rs.getTimestamp("expires_at")),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at"))));
    }

    public int machineCount(String principalId) {
        return count("rcm_machine_grant", "agent_id", principalId);
    }

    public int projectCount(String principalId) {
        return count("rcm_project_member", "project_id", principalId);
    }

    private boolean hasMachineGrant(String principal, String machine, String required) {
        if (jdbc == null) {
            var grant = machineMemory.get(key(principal, machine));
            return grant != null && grant.active() && grants(grant.scopes(), required, MACHINE_SCOPES);
        }
        try {
            var value = jdbc.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM rcm_machine_grant
                         WHERE principal_id = ? AND agent_id = ?
                           AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
                           AND (jsonb_exists(scope_json, ?) OR jsonb_exists(scope_json, 'admin'))
                    )
                    """, Boolean.class, principal, machine, required);
            return Boolean.TRUE.equals(value);
        } catch (DataAccessException failure) {
            // Fail closed when the ACL migration has not reached this Center.
            throw new SecurityException("machine access policy is unavailable", failure);
        }
    }

    private boolean hasProjectGrant(String principal, String project, String required) {
        if (jdbc == null) {
            var grant = projectMemory.get(key(principal, project));
            return grant != null && grant.active() && grants(grant.scopes(), required, PROJECT_SCOPES);
        }
        try {
            var value = jdbc.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM rcm_project_member
                         WHERE principal_id = ? AND project_id = ?
                           AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
                           AND (jsonb_exists(scope_json, ?) OR jsonb_exists(scope_json, 'admin'))
                    )
                    """, Boolean.class, principal, project, required);
            return Boolean.TRUE.equals(value);
        } catch (DataAccessException failure) {
            throw new SecurityException("project access policy is unavailable", failure);
        }
    }

    private int count(String table, String ignored, String principalId) {
        var principal = principalId == null || principalId.isBlank() ? "" : requiredPrincipal(principalId);
        if (jdbc == null) {
            var values = table.equals("rcm_machine_grant") ? machineMemory.values() : projectMemory.values();
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

    private static String projectRole(Set<String> scopes) {
        return scopes.contains("admin") ? "admin" : scopes.contains("write") ? "write" : "read";
    }

    private static String principalId(TaskOrigin origin) {
        return requiredPrincipal(origin == null ? null : origin.principalId());
    }

    private static String requiredPrincipal(String value) {
        if (value == null || value.isBlank()) throw new SecurityException("MCP principal is missing");
        var normalized = value.trim();
        if (normalized.length() > MAX_RESOURCE_ID || normalized.indexOf('\u0000') >= 0
                || normalized.indexOf('\r') >= 0 || normalized.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("principal_id is invalid");
        }
        return normalized;
    }

    private static String requiredResource(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        var normalized = value.trim();
        if (normalized.length() > MAX_RESOURCE_ID || normalized.indexOf('\u0000') >= 0
                || normalized.indexOf('\r') >= 0 || normalized.indexOf('\n') >= 0
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static String requiredMachineScope(String value) {
        return requiredScope(value, MACHINE_SCOPES, "machine");
    }

    private static String requiredProjectScope(String value) {
        return requiredScope(value, PROJECT_SCOPES, "project");
    }

    private static String requiredScope(String value, Set<String> supported, String kind) {
        var normalized = value == null || value.isBlank() ? "read" : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!supported.contains(normalized)) throw new IllegalArgumentException(kind + " action is invalid");
        return normalized;
    }

    private static Set<String> normalizeScopes(Set<String> values, Set<String> supported, Set<String> fallback) {
        var result = new LinkedHashSet<String>();
        if (values != null) {
            for (var value : values) {
                if (value == null || value.isBlank()) continue;
                var normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
                if (normalized.length() > MAX_SCOPE || !supported.contains(normalized)) {
                    throw new IllegalArgumentException("invalid access scope");
                }
                result.add(normalized);
            }
        }
        if (result.isEmpty()) result.addAll(fallback);
        // Higher privileges imply read visibility.  Persist the implied
        // element as well so the PostgreSQL and memory authorization paths
        // have exactly the same semantics without expression-heavy queries.
        if (supported == MACHINE_SCOPES && result.contains("execute")) result.add("read");
        if (supported == PROJECT_SCOPES && result.contains("write")) result.add("read");
        return Set.copyOf(result);
    }

    private static void validatePage(int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 200) throw new IllegalArgumentException("invalid access page");
    }

    private static String key(String principal, String resource) {
        return principal + '\u0000' + resource;
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

    private static java.sql.Timestamp timestamp(Instant value) {
        return value == null ? null : java.sql.Timestamp.from(value);
    }

    private static Instant instant(java.sql.Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private MachineGrantView machineView(Grant grant) {
        return new MachineGrantView(grant.principalId(), grant.resourceId(), grant.scopes(), grant.expiresAt(),
                grant.createdAt(), grant.createdAt());
    }

    private ProjectMemberView projectView(Grant grant) {
        return new ProjectMemberView(grant.resourceId(), grant.principalId(), projectRole(grant.scopes()),
                grant.scopes(), grant.expiresAt(), grant.createdAt(), grant.createdAt());
    }

    private record Grant(String principalId, String resourceId, Set<String> scopes,
                         Instant expiresAt, Instant createdAt) {
        boolean active() {
            return expiresAt == null || Instant.now().isBefore(expiresAt);
        }
    }

    public record MachineGrantView(@JsonProperty("principal_id") String principalId,
                                   @JsonProperty("machine_id") String machineId,
                                   Set<String> scopes,
                                   @JsonProperty("expires_at") Instant expiresAt,
                                   @JsonProperty("created_at") Instant createdAt,
                                   @JsonProperty("updated_at") Instant updatedAt) {
        public MachineGrantView {
            scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
        }
    }

    public record ProjectMemberView(@JsonProperty("project_id") String projectId,
                                    @JsonProperty("principal_id") String principalId,
                                    String role, Set<String> scopes,
                                    @JsonProperty("expires_at") Instant expiresAt,
                                    @JsonProperty("created_at") Instant createdAt,
                                    @JsonProperty("updated_at") Instant updatedAt) {
        public ProjectMemberView {
            scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
        }
    }
}
