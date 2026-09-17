package com.prodigalgal.remoteconnectmcp.center;

import com.prodigalgal.remoteconnectmcp.protocol.FileTransferAction;
import com.prodigalgal.remoteconnectmcp.protocol.FileTransferResponse;
import com.prodigalgal.remoteconnectmcp.protocol.ScopeMode;
import com.prodigalgal.remoteconnectmcp.protocol.TaskCommand;
import com.prodigalgal.remoteconnectmcp.protocol.TaskKind;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Center-side metadata and streaming data-plane coordinator for bidirectional
 * file transfers. PostgreSQL stores only metadata; the configured ArtifactStore
 * owns the bytes. MCP callers receive compact transfer metadata and task IDs.
 */
@Service
public final class ArtifactTransferService {
    public static final long MAX_BYTES = ArtifactStore.MAX_STREAM_BYTES;
    private static final Pattern SHA256 = Pattern.compile("(?i)[0-9a-f]{64}");
    private static final int MAX_FILE_NAME = 512;
    private static final int MAX_PATH = 4096;
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(30);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ArtifactStore store;
    private final TaskService tasks;
    private final CenterTokenConfig tokens;
    private final Map<String, MemoryTransfer> memory = new ConcurrentHashMap<>();
    private final HttpClient downloader = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final String publicBaseUrl;

    @Autowired
    public ArtifactTransferService(ObjectProvider<JdbcTemplate> jdbcProvider,
                                   ObjectProvider<TransactionTemplate> transactionProvider,
                                   ObjectProvider<ArtifactStore> storeProvider,
                                   TaskService tasks, CenterTokenConfig tokens) {
        this(jdbcProvider == null ? null : jdbcProvider.getIfAvailable(),
                transactionProvider == null ? null : transactionProvider.getIfAvailable(),
                storeProvider == null ? null : storeProvider.getIfAvailable(), tasks, tokens);
    }

    ArtifactTransferService(JdbcTemplate jdbc, TransactionTemplate transactions, ArtifactStore store,
                            TaskService tasks, CenterTokenConfig tokens) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.store = store == null ? new InMemoryArtifactStore() : store;
        this.tasks = tasks;
        this.tokens = tokens;
        this.publicBaseUrl = normalizeBase(System.getenv("RCM_CENTER_PUBLIC_BASE_URL"));
    }

    public TransferCreated createWebToAgent(TaskOrigin origin, CreateTaskRequest request,
                                            String fileId, String destinationPath, String fileName,
                                            String mimeType, URI downloadUrl, Long expectedBytes,
                                            String expectedSha256, boolean overwrite) {
        requireRequest(origin, request);
        validateFileName(fileName);
        validatePath(destinationPath, "destinationPath");
        validateDownloadUrl(downloadUrl);
        var safeMime = normalizeMime(mimeType);
        var ids = ids(origin, request.machineId(), request.idempotencyKey(), fileId, destinationPath);
        Path temporary = null;
        String objectKey = null;
        try {
            var downloaded = download(downloadUrl, expectedBytes, expectedSha256);
            temporary = downloaded.path();
            var artifactId = ids.artifactId();
            try (var objectInput = Files.newInputStream(temporary, StandardOpenOption.READ)) {
                objectKey = store.put(artifactId, downloaded.sha256(), objectInput, downloaded.bytes());
            }
            var action = new FileTransferAction(FileTransferAction.WEB_TO_AGENT, ids.transferId(), artifactId,
                    "", destinationPath, fileName, safeMime, downloaded.bytes(), downloaded.sha256(), overwrite);
            var task = tasks.create(withAction(request, action), "mcp", origin);
            var descriptor = new TransferDescriptor(ids.transferId(), artifactId, FileTransferAction.WEB_TO_AGENT,
                    task.id(), origin.principalId(), request.machineId(), fileName, safeMime, downloaded.bytes(), downloaded.sha256(),
                    "ready", null, publicUrl(artifactId, origin));
            persistInbound(origin, descriptor, objectKey, destinationPath);
            memory.putIfAbsent(ids.transferId(), new MemoryTransfer(descriptor, objectKey, destinationPath, ""));
            return new TransferCreated(task, descriptor);
        } catch (IOException exception) {
            throw new IllegalArgumentException("could not fetch uploaded file: " + exception.getMessage(), exception);
        } finally {
            deleteTemporary(temporary);
            // A failed task/metadata insert must not leave a Center object
            // which later GC cannot identify.
            if (objectKey != null && !hasTransfer(ids(origin, request.machineId(), request.idempotencyKey(), fileId, destinationPath).transferId())) {
                try { store.delete(objectKey); } catch (RuntimeException ignored) { }
            }
        }
    }

    public TransferCreated createAgentToWeb(TaskOrigin origin, CreateTaskRequest request,
                                            String sourcePath, String fileName, String mimeType) {
        requireRequest(origin, request);
        validatePath(sourcePath, "sourcePath");
        validateFileName(fileName);
        var safeMime = normalizeMime(mimeType);
        var ids = ids(origin, request.machineId(), request.idempotencyKey(), "", sourcePath);
        var action = new FileTransferAction(FileTransferAction.AGENT_TO_WEB, ids.transferId(), ids.artifactId(),
                sourcePath, "", fileName, safeMime, 0L, "", false);
        var task = tasks.create(withAction(request, action), "mcp", origin);
        var descriptor = new TransferDescriptor(ids.transferId(), ids.artifactId(), FileTransferAction.AGENT_TO_WEB,
                task.id(), origin.principalId(), request.machineId(), fileName, safeMime, 0L, "", "pending", null,
                publicUrl(ids.artifactId(), origin));
        persistOutbound(origin, descriptor, sourcePath);
        memory.putIfAbsent(ids.transferId(), new MemoryTransfer(descriptor, "", "", sourcePath));
        return new TransferCreated(task, descriptor);
    }

    /** Open a Center-owned inbound artifact for an authenticated Agent GET. */
    public AgentDownload openForAgent(String machineId, String transferId) {
        var row = findTransfer(transferId).orElseThrow(() -> new IllegalArgumentException("file transfer not found"));
        if (!machineId.equals(row.machineId())) throw new SecurityException("file transfer does not belong to this machine");
        if (!FileTransferAction.WEB_TO_AGENT.equals(row.direction())) throw new IllegalArgumentException("transfer is not an inbound file");
        if (!"ready".equals(row.status()) && !"delivered".equals(row.status())) throw new IllegalArgumentException("file transfer is not ready");
        var input = store.open(row.objectKey());
        return new AgentDownload(row.transferId(), row.fileName(), row.mimeType(), row.bytes(), row.sha256(), input);
    }

    /** Ingest an outbound Agent PUT without loading the body into the heap. */
    public FileTransferResponse receiveFromAgent(String machineId, String transferId, InputStream input,
                                                 long contentLength, String expectedSha256,
                                                 String fileName, String mimeType) {
        var row = findTransfer(transferId).orElseThrow(() -> new IllegalArgumentException("file transfer not found"));
        if (!machineId.equals(row.machineId())) throw new SecurityException("file transfer does not belong to this machine");
        if (!FileTransferAction.AGENT_TO_WEB.equals(row.direction())) throw new IllegalArgumentException("transfer is not an outbound file");
        if (input == null || contentLength <= 0 || contentLength > MAX_BYTES) throw new IllegalArgumentException("content length is outside the allowed range");
        var safeName = fileName == null || fileName.isBlank() ? row.fileName() : fileName;
        validateFileName(safeName);
        var safeMime = normalizeMime(mimeType == null || mimeType.isBlank() ? row.mimeType() : mimeType);
        Path temporary = null;
        try {
            var received = spool(input, contentLength, expectedSha256);
            temporary = received.path();
            var artifactId = row.artifactId();
            String objectKey;
            try (var objectInput = Files.newInputStream(temporary, StandardOpenOption.READ)) {
                objectKey = store.put(artifactId, received.sha256(), objectInput, received.bytes());
            }
            var response = new FileTransferResponse(transferId, artifactId, "ready", received.bytes(), received.sha256(), null);
            completeOutbound(row, safeMime, safeName, received.bytes(), received.sha256(), objectKey);
            memory.put(transferId, new MemoryTransfer(new TransferDescriptor(transferId, artifactId, row.direction(), row.taskId(),
                    row.principalId(), row.machineId(), safeName, safeMime, received.bytes(), received.sha256(), "ready", null,
                    publicUrl(artifactId, new TaskOrigin(row.principalId(), TaskOrigin.COMPAT_TOKEN, ""))), objectKey, "", row.sourcePath()));
            return response;
        } catch (IOException exception) {
            markFailed(row, exception.getMessage());
            throw new IllegalArgumentException("could not store Agent file: " + exception.getMessage(), exception);
        } finally {
            deleteTemporary(temporary);
        }
    }

    public Optional<TransferDescriptor> findByArtifact(String artifactId, TaskOrigin origin) {
        if (artifactId == null || artifactId.isBlank()) return Optional.empty();
        var row = jdbc == null ? memory.values().stream().map(MemoryTransfer::descriptor)
                .filter(value -> artifactId.equals(value.artifactId()) && principal.equals(value.principalId()))
                .map(value -> new PublicArtifact(value.artifactId(), value.fileName(), value.mimeType(), value.bytes(), value.sha256(), value.status(), null))
                .findFirst().orElse(null)
                : jdbc.query("SELECT t.transfer_id, t.artifact_id, t.direction, t.task_id, t.machine_id, t.principal_id, t.file_name, t.mime_type, a.bytes, a.sha256, t.status, t.error_text FROM rcm_file_transfer t LEFT JOIN rcm_artifact a ON a.artifact_id = t.artifact_id WHERE t.artifact_id = ?",
                ps -> ps.setString(1, artifactId.trim()), rs -> rs.next() ? descriptor(rs) : null);
        if (row == null || origin == null || !origin.principalId().equals(row.principalId())) return Optional.empty();
        return Optional.of(withDownloadUrl(row, origin));
    }

    public Optional<TransferDescriptor> findByTransfer(String transferId, TaskOrigin origin) {
        if (transferId == null || transferId.isBlank() || origin == null) return Optional.empty();
        var row = findTransfer(transferId).orElse(null);
        if (row == null || !origin.principalId().equals(row.principalId())) return Optional.empty();
        return Optional.of(new TransferDescriptor(row.transferId(), row.artifactId(), row.direction(), row.taskId(), row.principalId(),
                row.machineId(), row.fileName(), row.mimeType(), row.bytes(), row.sha256(), row.status(), null,
                publicUrl(row.artifactId(), origin)));
    }

    private TransferDescriptor withDownloadUrl(TransferDescriptor value, TaskOrigin origin) {
        return new TransferDescriptor(value.transferId(), value.artifactId(), value.direction(), value.taskId(), value.principalId(),
                value.machineId(), value.fileName(), value.mimeType(), value.bytes(), value.sha256(), value.status(), value.error(),
                publicUrl(value.artifactId(), origin));
    }

    /** Public file object endpoint URL; the URL carries only a short-lived HMAC. */
    public String publicUrl(String artifactId, TaskOrigin origin) {
        if (artifactId == null || artifactId.isBlank() || origin == null) return "";
        var expires = Instant.now().plus(Duration.ofMinutes(15)).getEpochSecond();
        var subject = artifactId + "\n" + origin.principalId() + "\n" + expires;
        var signature = sign(subject);
        var base = publicBaseUrl.isBlank() ? "" : publicBaseUrl;
        return base + "/artifacts/" + artifactId + "/content?expires=" + expires + "&principal="
                + encode(origin.principalId()) + "&signature=" + encode(signature);
    }

    public PublicArtifact openPublic(String artifactId, long expires, String principal, String signature) {
        if (artifactId == null || artifactId.isBlank() || principal == null || principal.isBlank()
                || expires < Instant.now().getEpochSecond() || !MessageDigest.isEqual(sign(artifactId + "\n" + principal + "\n" + expires).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                decode(signature))) {
            throw new SecurityException("invalid or expired artifact URL");
        }
        PublicArtifact row = jdbc == null ? memory.values().stream().map(MemoryTransfer::descriptor)
                .filter(value -> artifactId.equals(value.artifactId()) && principal.equals(value.principalId()))
                .map(value -> new PublicArtifact(value.artifactId(), value.fileName(), value.mimeType(), value.bytes(), value.sha256(), value.status(), null))
                .findFirst().orElse(null)
                : jdbc.query("SELECT artifact_id, file_name, mime_type, bytes, sha256, status FROM rcm_artifact WHERE artifact_id = ? AND principal_id = ?",
                ps -> { ps.setString(1, artifactId); ps.setString(2, principal); }, rs -> rs.next()
                        ? new PublicArtifact(artifactId, rs.getString("file_name"), rs.getString("mime_type"), rs.getLong("bytes"), rs.getString("sha256"), rs.getString("status"), null)
                        : null);
        if (row == null) throw new IllegalArgumentException("artifact not found");
        var objectKey = jdbc == null ? memory.values().stream().filter(value -> artifactId.equals(value.descriptor().artifactId())).map(MemoryTransfer::objectKey).findFirst().orElse("")
                : jdbc.queryForObject("SELECT object_key FROM rcm_artifact WHERE artifact_id = ?", String.class, artifactId);
        if (objectKey == null || objectKey.isBlank()) throw new IllegalArgumentException("artifact is not ready");
        return new PublicArtifact(row.artifactId(), row.fileName(), row.mimeType(), row.bytes(), row.sha256(), row.status(), store.open(objectKey));
    }

    private CreateTaskRequest withAction(CreateTaskRequest request, FileTransferAction action) {
        var source = request.command();
        var command = new TaskCommand("", TaskKind.FILE_TRANSFER, "file_transfer", null,
                source.cwd(), Map.of(), 0, null, Instant.now(), null, action);
        return new CreateTaskRequest(request.machineId(), command, request.idempotencyKey(), request.projectId(),
                request.worktreeId(), request.scopeMode(), request.scopeRoot(), request.sessionId(), request.risk(),
                request.elevationRequired(), request.origin());
    }

    private void persistInbound(TaskOrigin origin, TransferDescriptor descriptor, String objectKey, String destinationPath) {
        if (jdbc == null) return;
        Runnable write = () -> {
            jdbc.update("""
                    INSERT INTO rcm_artifact(artifact_id, principal_id, machine_id, task_id, transfer_id, file_name, mime_type, storage_backend, object_key, bytes, sha256, status, created_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '15 minutes')
                    ON CONFLICT (artifact_id) DO NOTHING
                    """, descriptor.artifactId(), origin.principalId(), descriptor.machineId(), descriptor.taskId(), descriptor.transferId(),
                    descriptor.fileName(), descriptor.mimeType(), store.backend(), objectKey, descriptor.bytes(), descriptor.sha256(), "ready");
            jdbc.update("""
                    INSERT INTO rcm_file_transfer(transfer_id, artifact_id, task_id, principal_id, machine_id, direction, source_path, destination_path, file_name, mime_type, expected_bytes, expected_sha256, bytes_transferred, status, error_text, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, 0, ?, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    ON CONFLICT (transfer_id) DO NOTHING
                    """, descriptor.transferId(), descriptor.artifactId(), descriptor.taskId(), origin.principalId(), descriptor.machineId(), descriptor.direction(),
                    destinationPath, descriptor.fileName(), descriptor.mimeType(), descriptor.bytes(), descriptor.sha256(), descriptor.status());
        };
        if (transactions == null) write.run(); else transactions.execute(status -> { write.run(); return null; });
    }

    private void persistOutbound(TaskOrigin origin, TransferDescriptor descriptor, String sourcePath) {
        if (jdbc == null) return;
        Runnable write = () -> {
            jdbc.update("""
                    INSERT INTO rcm_file_transfer(transfer_id, artifact_id, task_id, principal_id, machine_id, direction, source_path, destination_path, file_name, mime_type, expected_bytes, expected_sha256, bytes_transferred, status, error_text, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, 0, NULL, 0, ?, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    ON CONFLICT (transfer_id) DO NOTHING
                    """, descriptor.transferId(), descriptor.artifactId(), descriptor.taskId(), origin.principalId(), descriptor.machineId(), descriptor.direction(),
                    sourcePath, descriptor.fileName(), descriptor.mimeType(), descriptor.status());
        };
        if (transactions == null) write.run(); else transactions.execute(status -> { write.run(); return null; });
    }

    private void completeOutbound(TransferRow row, String mime, String fileName, long bytes, String sha, String objectKey) {
        if (jdbc == null) return;
        Runnable write = () -> {
            jdbc.update("""
                    INSERT INTO rcm_artifact(artifact_id, principal_id, machine_id, task_id, transfer_id, file_name, mime_type, storage_backend, object_key, bytes, sha256, status, created_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ready', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '15 minutes')
                    ON CONFLICT (artifact_id) DO UPDATE SET object_key = EXCLUDED.object_key, bytes = EXCLUDED.bytes, sha256 = EXCLUDED.sha256, mime_type = EXCLUDED.mime_type, file_name = EXCLUDED.file_name, status = 'ready'
                    """, row.artifactId(), row.principalId(), row.machineId(), row.taskId(), row.transferId(), fileName, mime, store.backend(), objectKey, bytes, sha);
            jdbc.update("UPDATE rcm_file_transfer SET file_name = ?, mime_type = ?, expected_bytes = ?, expected_sha256 = ?, bytes_transferred = ?, status = 'ready', error_text = NULL, updated_at = CURRENT_TIMESTAMP WHERE transfer_id = ?",
                    fileName, mime, bytes, sha, bytes, row.transferId());
        };
        if (transactions == null) write.run(); else transactions.execute(status -> { write.run(); return null; });
    }

    private void markFailed(TransferRow row, String error) {
        if (jdbc != null) jdbc.update("UPDATE rcm_file_transfer SET status = 'failed', error_text = ?, updated_at = CURRENT_TIMESTAMP WHERE transfer_id = ?",
                error == null ? "file transfer failed" : error.substring(0, Math.min(error.length(), 4096)), row.transferId());
    }

    private Optional<TransferRow> findTransfer(String transferId) {
        if (transferId == null || transferId.isBlank()) return Optional.empty();
        if (jdbc == null) {
            return Optional.ofNullable(memory.get(transferId.trim())).map(value -> new TransferRow(value.descriptor().transferId(), value.descriptor().artifactId(),
                    value.descriptor().taskId(), value.descriptor().principalId(), value.descriptor().machineId(), value.descriptor().direction(),
                    value.sourcePath(), value.destinationPath(), value.descriptor().fileName(), value.descriptor().mimeType(), value.descriptor().bytes(), value.descriptor().sha256(), value.descriptor().status(), value.objectKey()));
        }
        return jdbc.query("SELECT t.transfer_id, t.artifact_id, t.task_id, t.principal_id, t.machine_id, t.direction, t.source_path, t.destination_path, t.file_name, t.mime_type, t.expected_bytes, t.expected_sha256, t.status, t.error_text, a.object_key FROM rcm_file_transfer t LEFT JOIN rcm_artifact a ON a.artifact_id = t.artifact_id WHERE t.transfer_id = ?",
                ps -> ps.setString(1, transferId.trim()), rs -> rs.next() ? Optional.of(transfer(rs)) : Optional.empty());
    }

    private boolean hasTransfer(String transferId) {
        return findTransfer(transferId).isPresent();
    }

    private static TransferRow transfer(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TransferRow(rs.getString("transfer_id"), rs.getString("artifact_id"), rs.getString("task_id"), rs.getString("principal_id"),
                rs.getString("machine_id"), rs.getString("direction"), rs.getString("source_path"), rs.getString("destination_path"),
                rs.getString("file_name"), rs.getString("mime_type"), rs.getLong("expected_bytes"), rs.getString("expected_sha256"), rs.getString("status"), rs.getString("object_key"));
    }

    private static TransferDescriptor descriptor(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TransferDescriptor(rs.getString("transfer_id"), rs.getString("artifact_id"), rs.getString("direction"), rs.getString("task_id"),
                rs.getString("principal_id"), rs.getString("machine_id"), rs.getString("file_name"), rs.getString("mime_type"), rs.getLong("bytes"), rs.getString("sha256"),
                rs.getString("status"), rs.getString("error_text"), "");
    }

    private Downloaded download(URI uri, Long expectedBytes, String expectedSha256) throws IOException {
        try {
            var request = HttpRequest.newBuilder(uri).timeout(DOWNLOAD_TIMEOUT).header("Accept", "application/octet-stream").GET().build();
            var response = downloader.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                close(response.body());
                throw new IOException("download URL returned HTTP " + response.statusCode());
            }
            var length = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (length > MAX_BYTES || (expectedBytes != null && expectedBytes > 0 && length > 0 && length != expectedBytes)) {
                close(response.body());
                throw new IOException("download size is outside the declared limit");
            }
            return spool(response.body(), expectedBytes != null && expectedBytes > 0 ? expectedBytes : length, expectedSha256);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("download interrupted", exception);
        }
    }

    private Downloaded spool(InputStream input, long expectedBytes, String expectedSha256) throws IOException {
        if (expectedBytes > MAX_BYTES) throw new IOException("file size is outside the allowed range");
        var temporary = Files.createTempFile("rcm-transfer-", ".part");
        try (input; var output = Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING)) {
            var digest = MessageDigest.getInstance("SHA-256");
            var buffer = new byte[1024 * 1024];
            long count = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                count += read;
                if ((expectedBytes > 0 && count > expectedBytes) || count > MAX_BYTES) throw new IOException("file exceeds declared size");
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
            }
            var sha = HexFormat.of().formatHex(digest.digest());
            if ((expectedBytes > 0 && count != expectedBytes) || (expectedSha256 != null && !expectedSha256.isBlank() && !sha.equalsIgnoreCase(expectedSha256.trim()))) {
                throw new IOException("file size or SHA-256 does not match metadata");
            }
            return new Downloaded(temporary, count, sha);
        } catch (java.security.NoSuchAlgorithmException exception) {
            deleteTemporary(temporary);
            throw new IOException("SHA-256 is unavailable", exception);
        } catch (IOException exception) {
            deleteTemporary(temporary);
            throw exception;
        }
    }

    private static void requireRequest(TaskOrigin origin, CreateTaskRequest request) {
        if (origin == null || request == null || request.machineId().isBlank()) throw new IllegalArgumentException("transfer request is incomplete");
    }

    private static void validateDownloadUrl(URI uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("download_url must be an HTTPS URL");
        }
        try {
            for (var address : InetAddress.getAllByName(uri.getHost())) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
                    throw new IllegalArgumentException("download_url points to a private address");
                }
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("download_url host cannot be resolved", exception);
        }
    }

    private static void validatePath(String value, String name) {
        if (value == null || value.isBlank() || value.length() > MAX_PATH || value.indexOf('\u0000') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) throw new IllegalArgumentException(name + " is invalid");
    }

    private static void validateFileName(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_FILE_NAME || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0
                || value.indexOf('\u0000') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) throw new IllegalArgumentException("fileName is invalid");
    }

    private static String normalizeMime(String value) {
        var result = value == null || value.isBlank() ? "application/octet-stream" : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (result.length() > 128 || result.indexOf('\r') >= 0 || result.indexOf('\n') >= 0) throw new IllegalArgumentException("mimeType is invalid");
        return result;
    }

    private Ids ids(TaskOrigin origin, String machineId, String idempotency, String fileId, String path) {
        var seed = origin.principalId() + "\n" + machineId + "\n" + (idempotency == null ? "" : idempotency) + "\n" + fileId + "\n" + path;
        var digest = sha256(seed).substring(0, 48);
        return new Ids("transfer_" + digest, "artifact_" + digest);
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 is unavailable", exception); }
    }

    private String sign(String value) {
        try {
            var mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(tokens.artifactDownloadSecret().getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException("artifact URL signing is unavailable", exception); }
    }

    private static byte[] decode(String value) {
        try { return Base64.getUrlDecoder().decode(value == null ? "" : value); }
        catch (IllegalArgumentException exception) { return new byte[0]; }
    }

    private static String encode(String value) { return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8); }
    private static String normalizeBase(String value) { return value == null ? "" : value.trim().replaceAll("/+$", ""); }
    private static void deleteTemporary(Path path) { if (path != null) try { Files.deleteIfExists(path); } catch (IOException ignored) { } }
    private static void close(InputStream input) { if (input != null) try { input.close(); } catch (IOException ignored) { } }

    public record TransferCreated(TaskView task, TransferDescriptor transfer) { }
    public record TransferDescriptor(String transferId, String artifactId, String direction, String taskId,
                                     String principalId, String machineId, String fileName, String mimeType, long bytes,
                                     String sha256, String status, String error, String downloadUrl) {
    }
    public record AgentDownload(String transferId, String fileName, String mimeType, long bytes, String sha256, InputStream body) { }
    public record PublicArtifact(String artifactId, String fileName, String mimeType, long bytes, String sha256, String status, InputStream body) { }
    private record Downloaded(Path path, long bytes, String sha256) { }
    private record Ids(String transferId, String artifactId) { }
    private record TransferRow(String transferId, String artifactId, String taskId, String principalId, String machineId,
                               String direction, String sourcePath, String destinationPath, String fileName, String mimeType,
                               long bytes, String sha256, String status, String objectKey) { }
    private record MemoryTransfer(TransferDescriptor descriptor, String objectKey, String destinationPath, String sourcePath) { }
}
