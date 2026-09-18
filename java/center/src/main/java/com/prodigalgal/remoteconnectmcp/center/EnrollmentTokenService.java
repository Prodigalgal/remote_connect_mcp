package com.prodigalgal.remoteconnectmcp.center;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Issues and atomically consumes one-time Agent enrollment credentials. Memory
 * storage is test-only; PostgreSQL is the sole production authority and is
 * selected for the whole Center process.
 */
@Service
public final class EnrollmentTokenService {
    public static final long MIN_EXPIRY_SECONDS = 60 * 60;
    public static final long MAX_EXPIRY_SECONDS = 30 * 24 * 60 * 60;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> memory = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final String fixedTestToken;

    @Autowired
    public EnrollmentTokenService(ObjectProvider<JdbcTemplate> jdbcProvider,
                                  ObjectProvider<TransactionTemplate> transactionProvider) {
        this.jdbc = jdbcProvider.getIfAvailable();
        this.transactions = jdbc == null ? null : transactionProvider.getIfAvailable();
        this.fixedTestToken = null;
        if (jdbc != null && transactions == null) {
            throw new IllegalStateException("TransactionTemplate is required when PostgreSQL persistence is enabled");
        }
    }

    EnrollmentTokenService() {
        this.jdbc = null;
        this.transactions = null;
        this.fixedTestToken = null;
    }

    private EnrollmentTokenService(String fixedTestToken) {
        this.jdbc = null;
        this.transactions = null;
        this.fixedTestToken = fixedTestToken;
    }

    static EnrollmentTokenService forTest(String fixedTestToken) {
        return new EnrollmentTokenService(fixedTestToken);
    }

    public IssuedToken issue(String requestedName, Duration lifetime) {
        var name = requestedName == null ? "" : requestedName.trim();
        if (name.isBlank() || name.length() > 512) throw new IllegalArgumentException("requestedName is required");
        var seconds = lifetime == null ? 24 * 60 * 60 : lifetime.toSeconds();
        if (seconds < MIN_EXPIRY_SECONDS || seconds > MAX_EXPIRY_SECONDS) {
            throw new IllegalArgumentException("enrollment token lifetime must be between 1 hour and 30 days");
        }
        var token = randomToken();
        var tokenId = "enrollment-" + UUID.randomUUID().toString().replace("-", "");
        var expires = Instant.now().plusSeconds(seconds);
        var hash = hash(token);
        if (jdbc == null) {
            memory.put(hash, new Entry(tokenId, hash, name, expires));
        } else {
            jdbc.update("""
                    INSERT INTO rcm_enrollment_token(token_id, token_hash, requested_name, max_uses, uses, expires_at, created_at)
                    VALUES (?, ?, ?, 1, 0, ?, CURRENT_TIMESTAMP)
                    """, tokenId, hash, name, java.sql.Timestamp.from(expires));
        }
        return new IssuedToken(tokenId, token, name, expires);
    }

    public boolean consume(String token, String requestedName) {
        if (token == null || token.isBlank() || requestedName == null || requestedName.isBlank()) return false;
        if (fixedTestToken != null) return fixedTestToken.equals(token.trim());
        var hash = hash(token.trim());
        var name = requestedName.trim();
        if (jdbc == null) {
            var entry = memory.get(hash);
            if (entry == null || entry.uses >= entry.maxUses || !entry.requestedName.equals(name) || !Instant.now().isBefore(entry.expiresAt)) return false;
            synchronized (entry) {
                if (entry.uses >= entry.maxUses || !entry.requestedName.equals(name) || !Instant.now().isBefore(entry.expiresAt)) return false;
                entry.uses++;
                return true;
            }
        }
        return transactions.execute(status -> {
            var row = jdbc.query("""
                    SELECT token_id, requested_name, max_uses, uses, expires_at, revoked_at
                      FROM rcm_enrollment_token WHERE token_hash = ? FOR UPDATE
                    """, ps -> ps.setString(1, hash), rs -> {
                if (!rs.next()) return Optional.<TokenRow>empty();
                var expires = rs.getTimestamp("expires_at");
                var revoked = rs.getTimestamp("revoked_at");
                return Optional.of(new TokenRow(rs.getString("token_id"), rs.getString("requested_name"), rs.getInt("max_uses"),
                        rs.getInt("uses"), expires == null ? null : expires.toInstant(), revoked != null));
            });
            if (row.isEmpty()) return false;
            var value = row.get();
            if (value.revoked || value.uses >= value.maxUses || !value.requestedName.equals(name) || value.expiresAt == null || !Instant.now().isBefore(value.expiresAt)) return false;
            jdbc.update("UPDATE rcm_enrollment_token SET uses = uses + 1, last_used_at = CURRENT_TIMESTAMP WHERE token_id = ?", value.tokenId);
            return true;
        });
    }

    public int revoke(String tokenId) {
        if (tokenId == null || tokenId.isBlank()) throw new IllegalArgumentException("tokenId is required");
        if (jdbc == null) {
            return memory.values().stream().filter(entry -> entry.tokenId.equals(tokenId.trim())).mapToInt(entry -> {
                synchronized (entry) { entry.revoked = true; return 1; }
            }).sum();
        }
        return jdbc.update("UPDATE rcm_enrollment_token SET revoked_at = CURRENT_TIMESTAMP WHERE token_id = ? AND revoked_at IS NULL", tokenId.trim());
    }

    private String randomToken() {
        var bytes = new byte[48];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record IssuedToken(String tokenId, String token, String requestedName, Instant expiresAt) {
    }

    private record TokenRow(String tokenId, String requestedName, int maxUses, int uses, Instant expiresAt, boolean revoked) {
    }

    private static final class Entry {
        private final String tokenId;
        private final String hash;
        private final String requestedName;
        private final Instant expiresAt;
        private final int maxUses = 1;
        private int uses;
        private boolean revoked;

        private Entry(String tokenId, String hash, String requestedName, Instant expiresAt) {
            this.tokenId = tokenId;
            this.hash = hash;
            this.requestedName = requestedName;
            this.expiresAt = expiresAt;
        }
    }
}
