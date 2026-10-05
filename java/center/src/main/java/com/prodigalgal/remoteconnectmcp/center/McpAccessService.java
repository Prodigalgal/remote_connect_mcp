package com.prodigalgal.remoteconnectmcp.center;

import com.prodigalgal.remoteconnectmcp.protocol.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Per-credential machine and MCP-tool authorization. */
@Service
public final class McpAccessService {
    private static final int MAX_ID = 180;
    private static final Set<String> TOOLS = Set.of(
            "command", "desktop", "browser", "artifact", "task_read", "task_cancel");

    private final JdbcTemplate jdbc;
    private final Map<String, Grant> memory = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public McpAccessService(ObjectProvider<JdbcTemplate> jdbcProvider) {
        this(jdbcProvider == null ? null : jdbcProvider.getIfAvailable());
    }

    McpAccessService() {
        this((JdbcTemplate) null);
    }

    McpAccessService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Persist the selected permissions in the same transaction as token issuance. */
    void grantTokenMachines(String tokenId, Map<String, Set<String>> permissions) {
        var token = requiredId(tokenId, "token_id");
        var normalized = normalizeMachinePermissions(permissions, true);
        if (jdbc == null) {
            normalized.forEach((machine, tools) -> memory.put(key(token, machine),
                    new Grant(token, machine, tools, null)));
            return;
        }
        normalized.forEach((machine, tools) -> jdbc.update("""
                INSERT INTO rcm_mcp_token_machine_grant(token_id, agent_id, tools_json, expires_at, created_at, updated_at)
                VALUES (?, ?, CAST(? AS jsonb), NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (token_id, agent_id) DO UPDATE SET tools_json = EXCLUDED.tools_json,
                    expires_at = EXCLUDED.expires_at, updated_at = CURRENT_TIMESTAMP
                """, token, machine, json(tools)));
    }

    /** Enforce one selected tool on one machine for the authenticated credential. */
    public void authorizeTool(TaskOrigin origin, String machineId, String tool) {
        if (origin != null && origin.isConfigured()) return; // server-created/admin tasks only
        var tokenId = requiredId(origin == null ? null : origin.tokenId(), "token_id");
        var machine = requiredId(machineId, "machine_id");
        var requestedTool = requiredTool(tool);
        if (!hasGrant(tokenId, machine, requestedTool)) {
            throw new SecurityException("MCP credential is not granted tool " + requestedTool + " on this machine");
        }
    }

    /** Machine discovery is available for machines with at least one selected tool. */
    public boolean canReadMachine(TaskOrigin origin, String machineId) {
        if (origin != null && origin.isConfigured()) return true;
        var tokenId = requiredId(origin == null ? null : origin.tokenId(), "token_id");
        var machine = requiredId(machineId, "machine_id");
        return hasGrant(tokenId, machine, "machines");
    }

    /** Non-secret permission summaries for one bounded Console credential page. */
    Map<String, Map<String, Set<String>>> listTokenMachinePermissions(List<String> tokenIds) {
        if (tokenIds == null || tokenIds.isEmpty()) return Map.of();
        var tokens = tokenIds.stream().map(token -> requiredId(token, "token_id")).distinct().toList();
        if (jdbc == null) {
            var result = new LinkedHashMap<String, Map<String, Set<String>>>();
            memory.values().stream().filter(value -> tokens.contains(value.tokenId()))
                    .sorted(java.util.Comparator.comparing(Grant::machineId))
                    .forEach(value -> result.computeIfAbsent(value.tokenId(), ignored -> new LinkedHashMap<>())
                            .put(value.machineId(), value.tools()));
            return immutablePermissions(result);
        }
        try {
            var placeholders = String.join(",", java.util.Collections.nCopies(tokens.size(), "?"));
            var rows = jdbc.query("""
                    SELECT token_id, agent_id, tools_json
                      FROM rcm_mcp_token_machine_grant
                     WHERE token_id IN (%s) ORDER BY token_id, agent_id
                    """.formatted(placeholders), tokens.toArray(), (rs, row) -> new PermissionRow(
                    rs.getString("token_id"), rs.getString("agent_id"), parseTools(rs.getString("tools_json"))));
            var byToken = new LinkedHashMap<String, Map<String, Set<String>>>();
            rows.forEach(row -> byToken.computeIfAbsent(row.tokenId(), ignored -> new LinkedHashMap<>())
                    .put(row.machineId(), row.tools()));
            return immutablePermissions(byToken);
        } catch (DataAccessException failure) {
            throw new IllegalStateException("credential permissions are unavailable", failure);
        }
    }

    private static Map<String, Map<String, Set<String>>> immutablePermissions(
            Map<String, ? extends Map<String, Set<String>>> permissions) {
        var result = new LinkedHashMap<String, Map<String, Set<String>>>();
        permissions.forEach((token, machines) -> result.put(token, Map.copyOf(machines)));
        return Map.copyOf(result);
    }

    static Map<String, Set<String>> normalizeMachinePermissions(Map<String, Set<String>> permissions,
                                                                 boolean allowWildcard) {
        if (permissions == null || permissions.isEmpty()) {
            throw new IllegalArgumentException("machine_permissions must grant at least one tool on one machine");
        }
        var normalized = new LinkedHashMap<String, Set<String>>();
        permissions.forEach((rawMachine, rawTools) -> {
            var machine = requiredId(rawMachine, "machine_id");
            if ("*".equals(machine) && !allowWildcard) {
                throw new IllegalArgumentException("machine_permissions must name machines explicitly");
            }
            if (rawTools == null || rawTools.isEmpty()) return;
            var tools = new LinkedHashSet<String>();
            for (var rawTool : rawTools) tools.add(requiredTool(rawTool));
            if (!tools.isEmpty()) normalized.put(machine, Set.copyOf(tools));
        });
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("machine_permissions must grant at least one tool on one machine");
        }
        return Map.copyOf(normalized);
    }

    private boolean hasGrant(String tokenId, String machineId, String tool) {
        if (jdbc == null) {
            var direct = memory.get(key(tokenId, machineId));
            if (active(direct) && grants(direct, tool)) return true;
            var wildcard = memory.get(key(tokenId, "*"));
            return active(wildcard) && grants(wildcard, tool);
        }
        try {
            var sql = """
                    SELECT EXISTS (
                        SELECT 1
                          FROM rcm_mcp_token_machine_grant g
                          JOIN rcm_mcp_token t ON t.token_id = g.token_id
                          JOIN rcm_principal p ON p.principal_id = t.principal_id
                         WHERE g.token_id = ? AND (g.agent_id = ? OR g.agent_id = '*')
                           AND (g.expires_at IS NULL OR g.expires_at > CURRENT_TIMESTAMP)
                           AND t.revoked_at IS NULL
                           AND (t.expires_at IS NULL OR t.expires_at > CURRENT_TIMESTAMP)
                           AND p.status = 'active'
                           AND %s
                    )
                    """.formatted("machines".equals(tool)
                    ? "jsonb_array_length(g.tools_json) > 0"
                    : "jsonb_exists(g.tools_json, ?)");
            var granted = "machines".equals(tool)
                    ? jdbc.queryForObject(sql, Boolean.class, tokenId, machineId)
                    : jdbc.queryForObject(sql, Boolean.class, tokenId, machineId, tool);
            return Boolean.TRUE.equals(granted);
        } catch (DataAccessException failure) {
            throw new SecurityException("credential access policy is unavailable", failure);
        }
    }

    private static boolean grants(Grant grant, String tool) {
        return "machines".equals(tool) ? !grant.tools().isEmpty() : grant.tools().contains(tool);
    }

    private static boolean active(Grant grant) {
        return grant != null && (grant.expiresAt() == null || grant.expiresAt().isAfter(Instant.now()));
    }

    private static String requiredTool(String value) {
        if (value == null || !TOOLS.contains(value.trim().toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("unsupported machine tool: " + value);
        }
        return value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String requiredId(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        var normalized = value.trim();
        if (normalized.length() > MAX_ID || normalized.indexOf('\u0000') >= 0
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static String key(String tokenId, String machineId) {
        return tokenId + "\u0000" + machineId;
    }

    private static String json(Set<String> values) {
        return new String(JsonCodec.write(values), StandardCharsets.UTF_8);
    }

    private static Set<String> parseTools(String value) {
        if (value == null || value.isBlank()) return Set.of();
        try {
            var values = JsonCodec.read(value.getBytes(StandardCharsets.UTF_8), List.class);
            var result = new LinkedHashSet<String>();
            for (var item : values) if (item != null) result.add(String.valueOf(item));
            return Set.copyOf(result);
        } catch (RuntimeException ignored) {
            return Set.of();
        }
    }

    private record Grant(String tokenId, String machineId, Set<String> tools, Instant expiresAt) { }

    private record PermissionRow(String tokenId, String machineId, Set<String> tools) { }
}
