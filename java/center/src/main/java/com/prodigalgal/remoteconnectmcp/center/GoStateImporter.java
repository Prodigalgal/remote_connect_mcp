package com.prodigalgal.remoteconnectmcp.center;

import com.prodigalgal.remoteconnectmcp.protocol.JsonCodec;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One-shot importer for the Go Center file store.  It is intentionally not a
 * normal runtime service: run it against an already migrated, empty
 * PostgreSQL database, verify the counts, and then switch Agent traffic.
 * Plaintext access tokens are not present in the Go state file and are never
 * reconstructed here; MCP/Admin tokens remain environment-managed.
 */
final class GoStateImporter {
    private static final long MAX_OUTPUT_BYTES = TaskService.MAX_OUTPUT_BYTES;
    private static final int MAX_ARTIFACT_BYTES = 8 * 1024 * 1024;
    private static final long MAX_STATE_BYTES = 256L * 1024 * 1024;
    private static final String SHA256 = "(?i)[0-9a-f]{64}";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    GoStateImporter(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    ImportSummary importState(Path input) throws IOException {
        var stateFile = resolveStateFile(input);
        var stateRoot = stateFile.getParent();
        if (Files.size(stateFile) > MAX_STATE_BYTES) {
            throw new IOException("Go state.json exceeds 256 MiB importer limit");
        }
        @SuppressWarnings("unchecked")
        var root = (Map<String, Object>) JsonCodec.read(Files.readAllBytes(stateFile), Map.class);
        var machines = objectMap(root.get("machines"));
        // The Go persistedState field is named enrollment_tokens; accept the
        // shorter spelling as well for older hand-exported snapshots.
        var enrollments = objectMap(root.containsKey("enrollment_tokens")
                ? root.get("enrollment_tokens") : root.get("enrollments"));
        var tasks = objectMap(root.get("tasks"));
        var now = Instant.now();
        return transactions.execute(status -> {
            var machineCount = 0;
            for (var entry : machines.entrySet()) {
                importMachine(entry.getKey(), object(entry.getValue()), now);
                machineCount++;
            }
            var enrollmentCount = 0;
            for (var entry : enrollments.entrySet()) {
                importEnrollment(entry.getKey(), object(entry.getValue()), now);
                enrollmentCount++;
            }
            var taskCount = 0;
            var outputCount = 0L;
            var artifactCount = 0;
            for (var entry : tasks.entrySet()) {
                var imported = importTask(entry.getKey(), object(entry.getValue()), stateRoot, now);
                taskCount++;
                outputCount += imported.outputBytes();
                artifactCount += imported.artifact() ? 1 : 0;
            }
            return new ImportSummary(machineCount, enrollmentCount, taskCount, outputCount, artifactCount);
        });
    }

    private void importMachine(String id, Map<String, Object> value, Instant now) {
        var machineId = requiredText(id, "machine id", 128);
        var name = requiredText(text(value.get("name"), text(value.get("hostname"), machineId)), "machine name", 512);
        var tokenHash = requiredHash(text(value.get("token_hash")), "machine " + machineId + " token_hash");
        var created = instant(value.get("created_at"), now);
        var updated = instant(value.get("updated_at"), created);
        var lastSeen = instant(value.get("last_seen"), updated);
        var capabilities = jsonArray(value.get("capabilities"));
        var scope = text(value.get("scope_mode"), "unrestricted");
        jdbc.update("""
                INSERT INTO rcm_agent(agent_id, machine_name, host_id, hostname, os, arch, version,
                    default_cwd, scope_mode, workspace_root, capabilities, token_hash,
                    last_seen_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?)
                ON CONFLICT (agent_id) DO UPDATE SET machine_name = EXCLUDED.machine_name,
                    host_id = EXCLUDED.host_id, hostname = EXCLUDED.hostname, os = EXCLUDED.os,
                    arch = EXCLUDED.arch, version = EXCLUDED.version, default_cwd = EXCLUDED.default_cwd,
                    scope_mode = EXCLUDED.scope_mode, workspace_root = EXCLUDED.workspace_root,
                    capabilities = EXCLUDED.capabilities, token_hash = EXCLUDED.token_hash,
                    last_seen_at = EXCLUDED.last_seen_at, updated_at = EXCLUDED.updated_at
                """, machineId, name, text(value.get("host_id"), name), nullableText(value.get("hostname")),
                nullableText(value.get("os")), nullableText(value.get("arch")), nullableText(value.get("version")),
                nullableText(value.get("default_cwd")), scope, nullableText(value.get("workspace_root")), capabilities,
                tokenHash, timestamp(lastSeen), timestamp(created), timestamp(updated));
    }

    private void importEnrollment(String id, Map<String, Object> value, Instant now) {
        var tokenId = requiredIdentifier(text(value.get("id"), id), "enrollment token id", 128);
        var hash = requiredHash(text(value.get("token_hash")), "enrollment " + tokenId + " token_hash");
        var created = instant(value.get("created_at"), now);
        var persistent = bool(value.get("persistent"));
        var revoked = instantOrNull(value.get("revoked_at"));
        if (persistent && revoked == null) revoked = now;
        jdbc.update("""
                INSERT INTO rcm_enrollment_token(token_id, token_hash, requested_name, max_uses, uses,
                    expires_at, created_at, last_used_at, revoked_at)
                VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?)
                ON CONFLICT (token_id) DO UPDATE SET token_hash = EXCLUDED.token_hash,
                    requested_name = EXCLUDED.requested_name, max_uses = 1, uses = EXCLUDED.uses,
                    expires_at = EXCLUDED.expires_at, last_used_at = EXCLUDED.last_used_at,
                    revoked_at = EXCLUDED.revoked_at
                """, tokenId, hash, requiredText(text(value.get("name"), "imported-agent"), "enrollment name", 512),
                integer(value.get("uses"), 0), timestamp(instantOrNull(value.get("expires_at"))), timestamp(created),
                timestamp(instantOrNull(value.get("last_used_at"))), timestamp(revoked));
    }

    private ImportedTask importTask(String key, Map<String, Object> value, Path stateRoot, Instant now) {
        var taskId = requiredIdentifier(text(value.get("id"), key), "task id", 128);
        var machineId = requiredIdentifier(text(value.get("machine_id")), "task " + taskId + " machine_id", 128);
        var kind = text(value.get("kind"), "command");
        var capability = nullableText(value.get("required_capability"));
        var command = nullableText(value.get("command"));
        var environment = jsonObject(value.get("env"));
        var desktop = value.get("desktop");
        var desktopJson = desktop == null ? null : new String(JsonCodec.write(desktop), StandardCharsets.UTF_8);
        var created = instant(value.get("created_at"), now);
        var status = text(value.get("status"), TaskStatus.QUEUED);
        var originalTruncated = bool(value.get("output_truncated"));
        var outputPath = stateRoot.resolve("task-output").resolve(taskId + ".log").normalize();
        var declaredOutputBytes = longValue(value.get("output_bytes"), 0L);
        var retainedOutput = importOutput(taskId, outputPath, declaredOutputBytes);
        var outputTruncated = originalTruncated || retainedOutput.truncated();
        var error = nullableText(value.get("error"));
        if (error == null && declaredOutputBytes > 0 && retainedOutput.bytes() == 0) {
            error = "legacy output file is missing during import";
        }
        var originalExit = value.get("exit_code");
        jdbc.update("""
                INSERT INTO rcm_task(task_id, agent_id, kind, required_capability, command_text, cwd,
                    environment, desktop_action, timeout_seconds, idempotency_key, status, exit_code, lease_until,
                    attempt, output_bytes, output_truncated, error_text, created_at, dispatched_at,
                    started_at, finished_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (task_id) DO UPDATE SET agent_id = EXCLUDED.agent_id,
                    kind = EXCLUDED.kind, required_capability = EXCLUDED.required_capability,
                    command_text = EXCLUDED.command_text, cwd = EXCLUDED.cwd,
                    environment = EXCLUDED.environment, desktop_action = EXCLUDED.desktop_action,
                    timeout_seconds = EXCLUDED.timeout_seconds, idempotency_key = EXCLUDED.idempotency_key,
                    status = EXCLUDED.status, exit_code = EXCLUDED.exit_code, lease_until = EXCLUDED.lease_until, attempt = EXCLUDED.attempt,
                    output_bytes = EXCLUDED.output_bytes, output_truncated = EXCLUDED.output_truncated,
                    error_text = EXCLUDED.error_text, created_at = EXCLUDED.created_at,
                    dispatched_at = EXCLUDED.dispatched_at, started_at = EXCLUDED.started_at,
                    finished_at = EXCLUDED.finished_at, updated_at = EXCLUDED.updated_at
                """, taskId, machineId, kind, capability, command, nullableText(value.get("cwd")), environment,
                desktopJson, integer(value.get("timeout_seconds"), 0), nullableText(value.get("idempotency_key")),
                status, nullableInteger(originalExit), timestamp(instantOrNull(value.get("lease_until"))), integer(value.get("attempt"), 0),
                retainedOutput.bytes(), outputTruncated, error, timestamp(created),
                timestamp(instantOrNull(value.get("dispatched_at"))), timestamp(instantOrNull(value.get("started_at"))),
                timestamp(instantOrNull(value.get("finished_at"))), timestamp(instant(value.get("updated_at"), created)));
        jdbc.update("INSERT INTO rcm_task_output(task_id, output_data, updated_at) VALUES (?, ?, CURRENT_TIMESTAMP) ON CONFLICT (task_id) DO UPDATE SET output_data = EXCLUDED.output_data, updated_at = CURRENT_TIMESTAMP",
                taskId, new byte[0]);
        // The output row is filled by the streaming callback after the task
        // row exists, so a repeated import can safely replace it.
        if (retainedOutput.bytes() > 0) {
            streamOutput(taskId, outputPath, retainedOutput.bytes());
        }
        var artifactPath = stateRoot.resolve("task-artifacts").resolve(taskId + ".bin").normalize();
        var artifact = importArtifact(taskId, artifactPath, value, now);
        return new ImportedTask(retainedOutput.bytes(), artifact);
    }

    private RetainedOutput importOutput(String taskId, Path path, long declaredBytes) {
        try {
            if (!Files.isRegularFile(path)) return new RetainedOutput(0, declaredBytes > 0);
            var size = Files.size(path);
            return new RetainedOutput(Math.min(size, MAX_OUTPUT_BYTES), size > MAX_OUTPUT_BYTES || declaredBytes > size);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot inspect Go output for " + taskId, exception);
        }
    }

    private void streamOutput(String taskId, Path path, long bytes) {
        jdbc.execute((PreparedStatementCreator) con -> con.prepareStatement("UPDATE rcm_task_output SET output_data = ?, updated_at = CURRENT_TIMESTAMP WHERE task_id = ?"),
                (PreparedStatementCallback<Void>) ps -> {
            try (var input = Files.newInputStream(path)) {
                ps.setBinaryStream(1, new LimitedInputStream(input, bytes), bytes);
                ps.setString(2, taskId);
                ps.executeUpdate();
                return null;
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        });
    }

    private boolean importArtifact(String taskId, Path path, Map<String, Object> value, Instant now) {
        var declaredBytes = longValue(value.get("artifact_bytes"), 0L);
        var mime = nullableText(value.get("artifact_mime"));
        if (declaredBytes <= 0 || mime == null || !Files.isRegularFile(path)) return false;
        try {
            var bytes = Files.size(path);
            if (bytes <= 0 || bytes > MAX_ARTIFACT_BYTES) throw new IllegalStateException("Go artifact exceeds 8 MiB: " + taskId);
            if (declaredBytes != bytes) throw new IllegalStateException("artifact byte count mismatch for " + taskId);
            var data = Files.readAllBytes(path);
            var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
            var expected = requiredHash(text(value.get("artifact_sha256")), "task " + taskId + " artifact_sha256");
            if (!digest.equalsIgnoreCase(expected)) throw new IllegalStateException("artifact SHA-256 mismatch for " + taskId);
            jdbc.update("""
                    INSERT INTO rcm_task_artifact(task_id, mime_type, object_key, bytes, sha256, artifact_data, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (task_id) DO UPDATE SET mime_type = EXCLUDED.mime_type,
                        object_key = EXCLUDED.object_key, bytes = EXCLUDED.bytes, sha256 = EXCLUDED.sha256,
                        artifact_data = EXCLUDED.artifact_data, created_at = EXCLUDED.created_at
                    """, taskId, mime, "inline:" + taskId, data.length, digest, data, timestamp(now));
            return true;
        } catch (IOException exception) {
            throw new IllegalStateException("cannot import Go artifact " + taskId, exception);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static Path resolveStateFile(Path input) throws IOException {
        var value = input.toAbsolutePath().normalize();
        var file = Files.isDirectory(value) ? value.resolve("state.json") : value;
        if (!Files.isRegularFile(file)) throw new IOException("Go state.json not found: " + file);
        return file;
    }

    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("Go state entry is not an object");
        var result = new java.util.LinkedHashMap<String, Object>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static Map<String, Object> objectMap(Object value) {
        if (value == null) return Map.of();
        return object(value);
    }

    private static String jsonObject(Object value) {
        if (value == null) return "{}";
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("environment must be an object");
        return new String(JsonCodec.write(value), StandardCharsets.UTF_8);
    }

    private static String jsonArray(Object value) {
        if (value == null) return "[]";
        if (!(value instanceof List<?>)) throw new IllegalArgumentException("capabilities must be an array");
        return new String(JsonCodec.write(value), StandardCharsets.UTF_8);
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private static String text(Object value, String fallback) { var result = text(value); return result.isBlank() ? fallback : result; }
    private static String nullableText(Object value) { var result = text(value); return result.isBlank() ? null : result; }

    private static String requiredText(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max || value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException(field + " is empty or too long");
        }
        return value;
    }

    private static String requiredIdentifier(String value, String field, int max) {
        requiredText(value, field, max);
        if (!value.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException(field + " contains unsafe path characters");
        return value;
    }

    private static String requiredHash(String value, String field) {
        if (value == null || !value.matches(SHA256)) throw new IllegalArgumentException(field + " must be a SHA-256 hex digest");
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean bool(Object value) { return value instanceof Boolean b && b; }
    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        return fallback;
    }
    private static Integer nullableInteger(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }
    private static long longValue(Object value, long fallback) {
        if (value instanceof Number number) return number.longValue();
        return fallback;
    }

    private static Instant instant(Object value, Instant fallback) {
        var parsed = instantOrNull(value);
        return parsed == null ? fallback : parsed;
    }

    private static Instant instantOrNull(Object value) {
        if (value == null) return null;
        var raw = text(value);
        if (raw.isBlank()) return null;
        try { return Instant.parse(raw); }
        catch (RuntimeException exception) { throw new IllegalArgumentException("invalid timestamp: " + raw, exception); }
    }

    private static java.sql.Timestamp timestamp(Instant value) { return value == null ? null : java.sql.Timestamp.from(value); }

    record ImportSummary(int machines, int enrollments, int tasks, long outputBytes, int artifacts) {
    }

    private record RetainedOutput(long bytes, boolean truncated) {
    }

    private record ImportedTask(long outputBytes, boolean artifact) {
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private long remaining;

        private LimitedInputStream(InputStream input, long remaining) {
            super(input);
            this.remaining = remaining;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) return -1;
            var value = super.read();
            if (value >= 0) remaining--;
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) return -1;
            var count = super.read(buffer, offset, (int) Math.min(length, remaining));
            if (count > 0) remaining -= count;
            return count;
        }
    }
}
