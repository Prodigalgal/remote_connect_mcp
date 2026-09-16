package com.prodigalgal.remoteconnectmcp.center;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.prodigalgal.remoteconnectmcp.protocol.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Resolves opaque MCP Bearer tokens to principals and issues/revokes user
 * tokens for the console. PostgreSQL is authoritative in production; the
 * small in-memory map exists only for protocol tests and memory mode.
 */
@Service
public final class McpPrincipalService {
    private static final int MAX_TOKEN_BYTES = 512;
    private static final long MAX_TTL_SECONDS = Duration.ofDays(3650).toSeconds();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CenterTokenConfig compatibility;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Map<String, MemoryToken> memoryTokens = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public McpPrincipalService(CenterTokenConfig compatibility,
                               ObjectProvider<JdbcTemplate> jdbcProvider,
                               ObjectProvider<TransactionTemplate> transactionProvider) {
        this.compatibility = compatibility;
        this.jdbc = jdbcProvider == null ? null : jdbcProvider.getIfAvailable();
        this.transactions = transactionProvider == null ? null : transactionProvider.getIfAvailable();
    }

    McpPrincipalService(CenterTokenConfig compatibility) {
        this.compatibility = compatibility;
        this.jdbc = null;
        this.transactions = null;
    }

    /** Resolve and validate a plaintext Bearer value without persisting it. */
    public Optional<McpPrincipal> resolve(String candidate) {
        var value = normalizeToken(candidate);
        if (value.isEmpty()) return Optional.empty();
        var hash = sha256(value);
        var dynamic = resolveDatabase(hash).or(() -> resolveMemory(hash));
        if (dynamic.isPresent()) return dynamic;
        // Keep existing connectors usable until the administrator migrates to
        // per-user tokens. This principal is deliberately explicit as shared.
        return compatibility.acceptsMcp(value) ? Optional.of(McpPrincipal.compatibility()) : Optional.empty();
    }

    /** Issue a token; plaintext is returned to the caller exactly once. */
    public IssuedToken issue(IssueRequest request) {
        var value = request == null ? new IssueRequest("", "", null, Set.of()) : request;
        var principalId = normalizeIdentifier(value.principalId(), "principal_" + randomHex(16), 180);
        var displayName = value.displayName() == null || value.displayName().isBlank()
                ? principalId : value.displayName().trim();
        if (displayName.length() > 256) throw new IllegalArgumentException("display_name is too long");
        var scopes = value.scopes() == null || value.scopes().isEmpty()
                ? Set.of("mcp:read", "mcp:execute", "mcp:project") : Set.copyOf(value.scopes());
        // Validate before any database write.
        scopes = new McpPrincipal(principalId, "token", displayName, scopes, false, null).scopes();
        var ttl = value.expiresInSeconds() == null ? Duration.ofDays(30).toSeconds() : value.expiresInSeconds();
        if (ttl < 0 || ttl > MAX_TTL_SECONDS || (ttl > 0 && ttl < 3600)) {
            throw new IllegalArgumentException("expires_in_seconds must be 0 or between 3600 and 315360000 seconds");
        }
        var tokenId = "mcp_" + randomHex(16);
        var token = "rcm_mcp_" + randomToken();
        var hash = sha256(token);
        var createdAt = Instant.now();
        var expiresAt = ttl == 0 ? null : createdAt.plusSeconds(ttl);
        var principal = new McpPrincipal(principalId, tokenId, displayName, scopes, false, expiresAt);
        if (jdbc != null && transactions != null) {
            var finalScopes = scopes;
            transactions.execute(status -> {
                jdbc.update("""
                        INSERT INTO rcm_principal(principal_id, kind, display_name, status, created_at, updated_at)
                        VALUES (?, 'user', ?, 'active', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        ON CONFLICT (principal_id) DO UPDATE SET display_name = EXCLUDED.display_name,
                            updated_at = CURRENT_TIMESTAMP
                        """, principalId, displayName);
                jdbc.update("""
                        INSERT INTO rcm_mcp_token(token_id, principal_id, token_hash, display_name, scope_json,
                                                  expires_at, revoked_at, created_at)
                        VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, NULL, ?)
                        """, tokenId, principalId, hash, displayName,
                        new String(JsonCodec.write(finalScopes), StandardCharsets.UTF_8),
                        timestamp(expiresAt), timestamp(createdAt));
                return null;
            });
        } else {
            memoryTokens.put(hash, new MemoryToken(principal, token, null, createdAt));
        }
        return new IssuedToken(tokenId, principalId, displayName, scopes, expiresAt, token);
    }

    public List<McpTokenView> list(int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 200) throw new IllegalArgumentException("invalid token page");
        if (jdbc != null) {
            try {
                return jdbc.query("""
                        SELECT t.token_id, t.principal_id, t.display_name, t.scope_json,
                               t.expires_at, t.revoked_at, t.created_at
                          FROM rcm_mcp_token t
                         ORDER BY t.created_at DESC, t.token_id DESC OFFSET ? LIMIT ?
                        """, ps -> { ps.setInt(1, offset); ps.setInt(2, limit); },
                        (rs, rowNum) -> new McpTokenView(rs.getString("token_id"), rs.getString("principal_id"),
                                rs.getString("display_name"), parseScopes(rs.getString("scope_json")),
                                instant(rs.getTimestamp("expires_at")), instant(rs.getTimestamp("revoked_at")),
                                instant(rs.getTimestamp("created_at"))));
            } catch (DataAccessException ignored) {
                // A pre-015 database remains usable through the compatibility
                // token until the next Liquibase migration.
                return List.of();
            }
        }
        return memoryTokens.values().stream().map(value -> new McpTokenView(value.principal().tokenId(),
                value.principal().principalId(), value.principal().displayName(), value.principal().scopes(),
                value.principal().expiresAt(), value.revokedAt(), value.createdAt()))
                .skip(offset).limit(limit).toList();
    }

    public int count() {
        if (jdbc != null) {
            try {
                var value = jdbc.queryForObject("SELECT COUNT(*) FROM rcm_mcp_token", Long.class);
                return value == null ? 0 : Math.toIntExact(value);
            } catch (DataAccessException ignored) {
                return 0;
            }
        }
        return memoryTokens.size();
    }

    public boolean revoke(String tokenId) {
        if (tokenId == null || tokenId.isBlank()) return false;
        var normalized = tokenId.trim();
        var changed = false;
        if (jdbc != null) {
            try {
                changed = jdbc.update("UPDATE rcm_mcp_token SET revoked_at = CURRENT_TIMESTAMP WHERE token_id = ? AND revoked_at IS NULL", normalized) > 0;
            } catch (DataAccessException ignored) {
                // Keep memory fallback behavior deterministic in protocol mode.
            }
        }
        for (var entry : new ArrayList<>(memoryTokens.entrySet())) {
            if (normalized.equals(entry.getValue().principal().tokenId())) {
                memoryTokens.put(entry.getKey(), new MemoryToken(entry.getValue().principal(),
                        entry.getValue().plaintext(), Instant.now(), entry.getValue().createdAt()));
                changed = true;
            }
        }
        return changed;
    }

    private Optional<McpPrincipal> resolveDatabase(String hash) {
        if (jdbc == null) return Optional.empty();
        try {
            return jdbc.query("""
                    SELECT t.token_id, t.principal_id, COALESCE(t.display_name, p.display_name) AS display_name,
                           t.scope_json, t.expires_at
                      FROM rcm_mcp_token t
                      JOIN rcm_principal p ON p.principal_id = t.principal_id
                     WHERE t.token_hash = ? AND t.revoked_at IS NULL AND p.status = 'active'
                       AND (t.expires_at IS NULL OR t.expires_at > CURRENT_TIMESTAMP)
                     LIMIT 1
                    """, ps -> ps.setString(1, hash), (rs, rowNum) -> new McpPrincipal(
                    rs.getString("principal_id"), rs.getString("token_id"), rs.getString("display_name"),
                    parseScopes(rs.getString("scope_json")), false, instant(rs.getTimestamp("expires_at"))))
                    .stream().findFirst();
        } catch (DataAccessException ignored) {
            return Optional.empty();
        }
    }

    private Optional<McpPrincipal> resolveMemory(String hash) {
        var value = memoryTokens.get(hash);
        if (value == null || value.revokedAt() != null) return Optional.empty();
        var expiresAt = value.principal().expiresAt();
        if (expiresAt != null && !Instant.now().isBefore(expiresAt)) return Optional.empty();
        return Optional.of(value.principal());
    }

    private static Set<String> parseScopes(String json) {
        if (json == null || json.isBlank()) return Set.of("mcp:read");
        try {
            var values = JsonCodec.read(json.getBytes(StandardCharsets.UTF_8), List.class);
            var result = new java.util.LinkedHashSet<String>();
            for (var value : values) if (value != null) result.add(String.valueOf(value));
            return result.isEmpty() ? Set.of("mcp:read") : Set.copyOf(result);
        } catch (RuntimeException ignored) {
            return Set.of("mcp:read");
        }
    }

    private static String normalizeToken(String value) {
        if (value == null || value.isBlank()) return "";
        var normalized = value.trim();
        if (normalized.length() > MAX_TOKEN_BYTES || normalized.indexOf('\u0000') >= 0
                || normalized.chars().anyMatch(Character::isISOControl)) return "";
        return normalized;
    }

    private static String normalizeIdentifier(String value, String fallback, int max) {
        if (value == null || value.isBlank()) return fallback;
        var normalized = value.trim();
        if (normalized.length() > max || !normalized.matches("[A-Za-z0-9][A-Za-z0-9._:/-]*")) {
            throw new IllegalArgumentException("principal_id is invalid");
        }
        return normalized;
    }

    private static String randomToken() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String randomHex(int bytes) {
        var value = new byte[bytes];
        RANDOM.nextBytes(value);
        return java.util.HexFormat.of().formatHex(value);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record IssueRequest(@JsonProperty("principal_id") String principalId,
                               @JsonProperty("display_name") String displayName,
                               @JsonProperty("expires_in_seconds") Long expiresInSeconds,
                               Set<String> scopes) {
    }

    public record IssuedToken(String tokenId, String principalId, String displayName,
                              Set<String> scopes, Instant expiresAt, String token) {
    }

    private record MemoryToken(McpPrincipal principal, String plaintext, Instant revokedAt, Instant createdAt) {
    }
}
