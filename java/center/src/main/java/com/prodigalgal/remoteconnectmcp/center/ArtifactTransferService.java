package com.prodigalgal.remoteconnectmcp.center;

import com.prodigalgal.remoteconnectmcp.protocol.FileTransferAction;
import com.prodigalgal.remoteconnectmcp.protocol.FileTransferResponse;
import com.prodigalgal.remoteconnectmcp.protocol.ScopeMode;
import com.prodigalgal.remoteconnectmcp.protocol.TaskCommand;
import com.prodigalgal.remoteconnectmcp.protocol.TaskKind;
import com.prodigalgal.remoteconnectmcp.protocol.SensitiveValueRedactor;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
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
    private static final Duration DEFAULT_TRANSFER_STALL_TIMEOUT = Duration.ofMinutes(2);
    private static final long PROGRESS_STEP_BYTES = 4L * 1024 * 1024;
    private static final long PROGRESS_STEP_NANOS = Duration.ofSeconds(1).toNanos();
    private static final Duration DEFAULT_ARTIFACT_RETENTION = Duration.ofDays(7);
    private static final Duration DEFAULT_SIGNED_URL_TTL = Duration.ofMinutes(15);
    private static final long MAX_RESUMABLE_CHUNK_BYTES = 8L * 1024 * 1024;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ArtifactStore store;
    private final TaskService tasks;
    private final CenterTokenConfig tokens;
    private final TransferResourceLimiter resources;
    private final CenterAsyncExecutor async;
    private final Map<String, MemoryTransfer> memory = new ConcurrentHashMap<>();
    /** Single-Center transfer locks serialize overlapping chunk offsets. */
    private final Map<String, Object> transferLocks = new ConcurrentHashMap<>();
    private final Map<String, Boolean> preparations = new ConcurrentHashMap<>();
    /** Short reservation hand-off for concurrent same-key MCP retries. */
    private final Map<String, CompletableFuture<TransferCreated>> asyncPreparations = new ConcurrentHashMap<>();
    private final HttpClient downloader = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final String publicBaseUrl;
    private final Duration artifactRetention;
    private final Duration signedUrlTtl;
    private final Duration transferStallTimeout;
    private final Path transferSpoolRoot;

    @Autowired
    public ArtifactTransferService(ObjectProvider<JdbcTemplate> jdbcProvider,
                                   ObjectProvider<TransactionTemplate> transactionProvider,
                                   ObjectProvider<ArtifactStore> storeProvider,
                                   TaskService tasks, CenterTokenConfig tokens, CenterAsyncExecutor async) {
        this(jdbcProvider == null ? null : jdbcProvider.getIfAvailable(),
                transactionProvider == null ? null : transactionProvider.getIfAvailable(),
                storeProvider == null ? null : storeProvider.getIfAvailable(), tasks, tokens, async);
    }

    ArtifactTransferService(JdbcTemplate jdbc, TransactionTemplate transactions, ArtifactStore store,
                            TaskService tasks, CenterTokenConfig tokens) {
        this(jdbc, transactions, store, tasks, tokens, null);
    }

    ArtifactTransferService(JdbcTemplate jdbc, TransactionTemplate transactions, ArtifactStore store,
                            TaskService tasks, CenterTokenConfig tokens, CenterAsyncExecutor async) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.store = store == null ? new InMemoryArtifactStore() : store;
        this.tasks = tasks;
        this.tokens = tokens;
        this.resources = new TransferResourceLimiter();
        this.async = async;
        this.publicBaseUrl = normalizeBase(System.getenv("RCM_CENTER_PUBLIC_BASE_URL"));
        this.transferSpoolRoot = spoolRoot();
        this.artifactRetention = durationSetting("RCM_CENTER_ARTIFACT_RETENTION_SECONDS", DEFAULT_ARTIFACT_RETENTION,
                Duration.ofHours(1), Duration.ofDays(365));
        this.signedUrlTtl = durationSetting("RCM_CENTER_ARTIFACT_URL_TTL_SECONDS", DEFAULT_SIGNED_URL_TTL,
                Duration.ofMinutes(1), Duration.ofDays(1));
        this.transferStallTimeout = durationSetting("RCM_CENTER_TRANSFER_STALL_TIMEOUT_SECONDS",
                DEFAULT_TRANSFER_STALL_TIMEOUT, Duration.ofSeconds(5), Duration.ofHours(1));
    }

    /**
     * An async Web->Agent ingest intentionally keeps the ChatGPT download URL
     * only in the running request.  After a Center restart that URL cannot be
     * replayed safely, so fail the durable reservation once at startup rather
     * than leaving an invisible queued task forever.  This is a one-shot
     * recovery action, not a periodic polling loop; callers retry with a new
     * idempotency key and a fresh host file reference.
     */
    @EventListener(ApplicationReadyEvent.class)
    void recoverPendingIngests() {
        if (jdbc == null) return;
        var pending = jdbc.query("""
                SELECT transfer_id, task_id, machine_id
                  FROM rcm_file_transfer
                 WHERE direction = 'web_to_agent' AND status = 'pending'
                 ORDER BY updated_at
                 LIMIT 10000
                """, (rs, rowNum) -> new PendingReservation(rs.getString("transfer_id"),
                        rs.getString("task_id"), rs.getString("machine_id")));
        for (var row : pending) {
            markFailedByTransfer(row.transferId(), "Center restarted before file ingest completed; retry with a new idempotency key");
            if (row.taskId() != null && !row.taskId().isBlank()) {
                try {
                    tasks.failPreparedFileTransfer(row.machineId(), row.taskId(), "Center restarted before file ingest completed");
                } catch (RuntimeException ignored) {
                    // The transfer row remains the durable diagnostic record;
                    // a concurrently completed/removed task needs no retry.
                }
            }
        }
        // A crash between task creation and the reservation INSERT leaves no
        // transfer row to discover.  The pending action marker is deliberately
        // queryable, so close this orphaned task once at startup as well.
        var orphanTasks = jdbc.query("""
                SELECT t.task_id, t.agent_id
                  FROM rcm_task t
                 WHERE t.kind = 'file_transfer' AND t.status = 'queued'
                   AND COALESCE(t.file_transfer_action ->> 'direction', '') = 'web_to_agent'
                   AND COALESCE(t.file_transfer_action ->> 'expected_sha256', '') = ''
                   AND CASE WHEN COALESCE(t.file_transfer_action ->> 'expected_bytes', '') ~ '^[0-9]+$'
                            THEN (t.file_transfer_action ->> 'expected_bytes')::bigint ELSE 0 END = 0
                   AND NOT EXISTS (SELECT 1 FROM rcm_file_transfer f WHERE f.task_id = t.task_id)
                 ORDER BY t.created_at
                 LIMIT 10000
                """, (rs, rowNum) -> new OrphanPreparedTask(rs.getString("task_id"), rs.getString("agent_id")));
        for (var task : orphanTasks) {
            try {
                tasks.failPreparedFileTransfer(task.machineId(), task.taskId(),
                        "Center restarted before file transfer reservation was persisted");
            } catch (RuntimeException ignored) {
                // Keep startup recovery best-effort and bounded; the next
                // explicit task read still exposes the authoritative state.
            }
        }
    }

    public TransferCreated createWebToAgent(TaskOrigin origin, CreateTaskRequest request,
                                            String fileId, String destinationPath, String fileName,
                                            String mimeType, URI downloadUrl, Long expectedBytes,
                                            String expectedSha256, boolean overwrite) {
        requireRequest(origin, request);
        validateFileName(fileName);
        validatePath(destinationPath, "destinationPath");
        var safeMime = normalizeMime(mimeType);
        var ids = ids(origin, request.machineId(), request.idempotencyKey(), fileId, destinationPath);
        var existing = existingCreated(ids.transferId(), origin);
        if (existing != null) return existing;
        // Only a new ingest consumes the host-provided URL.  An idempotent
        // retry after the URL has expired must reuse the durable reservation
        // instead of failing before it can observe the existing transfer.
        validateDownloadUrl(downloadUrl);
        if (async != null) {
            return createWebToAgentAsync(origin, request, ids, destinationPath, fileName, safeMime, overwrite,
                    downloadUrl, expectedBytes, expectedSha256);
        }
        if (preparations.putIfAbsent(ids.transferId(), Boolean.TRUE) != null) {
            // A concurrent MCP retry must not download the same multi-GB
            // ChatGPT file a second time.  The caller can retry after the
            // original request has published its durable transfer row.
            throw new IllegalStateException("file transfer preparation is already in progress");
        }
        Path temporary = null;
        String objectKey = null;
        // When the remote file does not expose a length, reserve the maximum
        // stream size before opening it.  This is conservative by design: it
        // prevents an unknown-length retry from bypassing the spool quota.
        var reservationBytes = expectedBytes != null && expectedBytes >= 0 ? expectedBytes : MAX_BYTES;
        try (var reservation = resources.reserve(origin.principalId(), request.machineId(), reservationBytes)) {
            var downloaded = download(downloadUrl, expectedBytes, expectedSha256, ids.transferId());
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
                    "ready", null, publicUrl(artifactId, origin, task.executionSessionId(), "download"));
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
            preparations.remove(ids.transferId());
        }
    }

    private TransferCreated existingCreated(String transferId, TaskOrigin origin) {
        var row = findTransfer(transferId).orElse(null);
        if (row == null || origin == null || !origin.principalId().equals(row.principalId()) || row.taskId() == null || row.taskId().isBlank()
                || !Set.of("pending", "ready", "delivering", "delivered", "failed", "canceled").contains(row.status())) return null;
        var task = tasks.find(row.taskId()).map(TaskView::new).orElse(null);
        if (task == null) return null;
        var descriptor = new TransferDescriptor(row.transferId(), row.artifactId(), row.direction(), row.taskId(), row.principalId(),
                row.machineId(), row.fileName(), row.mimeType(), row.bytes(), row.sha256(), row.status(), row.error(),
                downloadUrl(row.artifactId(), row.status(), task.executionSessionId(), origin));
        return new TransferCreated(task, descriptor);
    }

    private TransferCreated createWebToAgentAsync(TaskOrigin origin, CreateTaskRequest request, Ids ids,
                                                   String destinationPath, String fileName, String safeMime,
                                                   boolean overwrite, URI downloadUrl, Long expectedBytes,
                                                   String expectedSha256) {
        var signal = new CompletableFuture<TransferCreated>();
        var previous = asyncPreparations.putIfAbsent(ids.transferId(), signal);
        if (previous != null) return awaitReservation(previous, ids.transferId());
        TaskView task = null;
        try {
            var pending = new FileTransferAction(FileTransferAction.WEB_TO_AGENT, ids.transferId(), ids.artifactId(),
                    "", destinationPath, fileName, safeMime, 0L, "", overwrite);
            var createdTask = tasks.create(withAction(request, pending), "mcp", origin);
            task = createdTask;
            var descriptor = new TransferDescriptor(ids.transferId(), ids.artifactId(), FileTransferAction.WEB_TO_AGENT,
                    createdTask.id(), origin.principalId(), request.machineId(), fileName, safeMime, 0L, "", "pending", null,
                    "");
            if (!persistInboundReservation(origin, descriptor, destinationPath)) {
                var existing = existingCreated(ids.transferId(), origin);
                var result = existing == null ? new TransferCreated(createdTask, descriptor) : existing;
                signal.complete(result);
                return result;
            }
            memory.putIfAbsent(ids.transferId(), new MemoryTransfer(descriptor, "", destinationPath, ""));
            async.submit(() -> {
                prepareInbound(origin, request, ids, createdTask.id(), destinationPath, fileName, safeMime, overwrite,
                        downloadUrl, expectedBytes, expectedSha256);
                return null;
            });
            var result = new TransferCreated(createdTask, descriptor);
            signal.complete(result);
            return result;
        } catch (RuntimeException failure) {
            signal.completeExceptionally(failure);
            markFailedByTransfer(ids.transferId(), failure.getMessage());
            try {
                if (task != null) tasks.failPreparedFileTransfer(request.machineId(), task.id(), failure.getMessage());
            } catch (RuntimeException ignored) { }
            throw failure;
        } finally {
            asyncPreparations.remove(ids.transferId(), signal);
        }
    }

    private static TransferCreated awaitReservation(CompletableFuture<TransferCreated> signal, String transferId) {
        try {
            return signal.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("file transfer reservation interrupted: " + transferId, exception);
        } catch (TimeoutException exception) {
            throw new IllegalStateException("file transfer reservation is still being created: " + transferId, exception);
        } catch (ExecutionException exception) {
            var cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("file transfer reservation failed: " + transferId, cause);
        }
    }

    private void prepareInbound(TaskOrigin origin, CreateTaskRequest request, Ids ids, String taskId,
                                String destinationPath, String fileName, String safeMime, boolean overwrite,
                                URI downloadUrl, Long expectedBytes, String expectedSha256) {
        Path temporary = null;
        String objectKey = null;
        try (var reservation = resources.reserve(origin.principalId(), request.machineId(),
                expectedBytes != null && expectedBytes >= 0 ? expectedBytes : MAX_BYTES)) {
            var downloaded = download(downloadUrl, expectedBytes, expectedSha256, ids.transferId());
            temporary = downloaded.path();
            try (var objectInput = Files.newInputStream(temporary, StandardOpenOption.READ)) {
                objectKey = store.put(ids.artifactId(), downloaded.sha256(), objectInput, downloaded.bytes());
            }
            var action = new FileTransferAction(FileTransferAction.WEB_TO_AGENT, ids.transferId(), ids.artifactId(),
                    "", destinationPath, fileName, safeMime, downloaded.bytes(), downloaded.sha256(), overwrite);
            // The action is published first; the JDBC poll gate still sees a
            // pending transfer row and cannot dispatch until metadata commits.
            tasks.updateFileTransferAction(request.machineId(), taskId, action);
            completeInbound(origin, request.machineId(), taskId, ids, fileName, safeMime, downloaded, objectKey);
            var descriptor = new TransferDescriptor(ids.transferId(), ids.artifactId(), FileTransferAction.WEB_TO_AGENT,
                    taskId, origin.principalId(), request.machineId(), fileName, safeMime, downloaded.bytes(), downloaded.sha256(),
                    "ready", null, publicUrl(ids.artifactId(), origin, taskSession(taskId), "download"));
            memory.put(ids.transferId(), new MemoryTransfer(descriptor, objectKey, destinationPath, ""));
        } catch (IOException | RuntimeException failure) {
            if (objectKey != null) {
                try { store.delete(objectKey); } catch (RuntimeException ignored) { }
            }
            markFailedByTransfer(ids.transferId(), failure.getMessage());
            try { tasks.failPreparedFileTransfer(request.machineId(), taskId, failure.getMessage()); } catch (RuntimeException ignored) { }
        } finally {
            deleteTemporary(temporary);
            preparations.remove(ids.transferId());
        }
    }

    public TransferCreated createAgentToWeb(TaskOrigin origin, CreateTaskRequest request,
                                            String sourcePath, String fileName, String mimeType) {
        requireRequest(origin, request);
        validatePath(sourcePath, "sourcePath");
        validateFileName(fileName);
        var safeMime = normalizeMime(mimeType);
        var ids = ids(origin, request.machineId(), request.idempotencyKey(), "", sourcePath);
        var existing = existingCreated(ids.transferId(), origin);
        if (existing != null) return existing;
        var action = new FileTransferAction(FileTransferAction.AGENT_TO_WEB, ids.transferId(), ids.artifactId(),
                sourcePath, "", fileName, safeMime, 0L, "", false);
        var task = tasks.create(withAction(request, action), "mcp", origin);
        var descriptor = new TransferDescriptor(ids.transferId(), ids.artifactId(), FileTransferAction.AGENT_TO_WEB,
                task.id(), origin.principalId(), request.machineId(), fileName, safeMime, 0L, "", "pending", null,
                "");
        persistOutbound(origin, descriptor, sourcePath);
        memory.putIfAbsent(ids.transferId(), new MemoryTransfer(descriptor, "", "", sourcePath));
        return new TransferCreated(task, descriptor);
    }

    /** Open a Center-owned inbound artifact for an authenticated Agent GET. */
    public AgentDownload openForAgent(String machineId, String transferId) {
        return openForAgent(machineId, transferId, null, 0L);
    }

    /** Open an inbound artifact while fencing the Agent's task attempt. */
    public AgentDownload openForAgent(String machineId, String transferId, Integer attempt) {
        return openForAgent(machineId, transferId, attempt, 0L);
    }

    /** Open an inbound artifact at a byte offset for a resumable Agent GET. */
    public AgentDownload openForAgent(String machineId, String transferId, Integer attempt, long offset) {
        var row = findTransfer(transferId).orElseThrow(() -> new IllegalArgumentException("file transfer not found"));
        if (!machineId.equals(row.machineId())) throw new SecurityException("file transfer does not belong to this machine");
        if (!FileTransferAction.WEB_TO_AGENT.equals(row.direction())) throw new IllegalArgumentException("transfer is not an inbound file");
        if (!"ready".equals(row.status()) && !"delivering".equals(row.status()) && !"delivered".equals(row.status())) {
            throw new IllegalArgumentException("file transfer is not ready");
        }
        tasks.assertCurrentAttempt(machineId, row.taskId(), attempt);
        if (offset < 0 || offset > row.bytes()) throw new IllegalArgumentException("resume offset is outside the transfer size");
        markDelivering(row);
        InputStream input = null;
        try {
            input = store.open(row.objectKey());
            skipFully(input, offset);
            return new AgentDownload(row.transferId(), row.fileName(), row.mimeType(), row.bytes() - offset, row.sha256(), input,
                    offset, row.bytes());
        } catch (RuntimeException failure) {
            close(input);
            markFailedByTransfer(row.transferId(), failure.getMessage());
            throw failure;
        } catch (IOException failure) {
            close(input);
            markFailedByTransfer(row.transferId(), failure.getMessage());
            throw new IllegalArgumentException("cannot seek file transfer", failure);
        }
    }

    /**
     * Return the durable offset for an Agent-to-Web upload.  The offset is
     * reconciled against the Center spool file on every probe, so a process
     * restart cannot advertise bytes that were only present in memory.
     */
    public TransferResume resumeFromAgent(String machineId, String transferId, Integer attempt) {
        var row = findTransfer(transferId).orElseThrow(() -> new IllegalArgumentException("file transfer not found"));
        if (!machineId.equals(row.machineId())) throw new SecurityException("file transfer does not belong to this machine");
        if (!FileTransferAction.AGENT_TO_WEB.equals(row.direction())) throw new IllegalArgumentException("transfer is not an outbound file");
        if ("failed".equals(row.status()) || "canceled".equals(row.status())) {
            throw new IllegalArgumentException("file transfer is not resumable");
        }
        tasks.assertCurrentAttempt(machineId, row.taskId(), attempt);
        synchronized (transferLocks.computeIfAbsent(transferId, ignored -> new Object())) {
            try {
                var partial = resumePath(transferId);
                var offset = "delivered".equals(row.status()) ? row.bytes()
                        : Files.exists(partial) ? Files.size(partial) : 0L;
                if (offset < 0 || offset > MAX_BYTES || (jdbc != null && row.bytes() > 0 && offset > row.bytes())) {
                    throw new IOException("Center resume spool is outside the transfer size");
                }
                if (jdbc != null && !"delivered".equals(row.status())) {
                    jdbc.update("UPDATE rcm_file_transfer SET bytes_transferred = ?, updated_at = CURRENT_TIMESTAMP WHERE transfer_id = ? AND status NOT IN ('delivered', 'failed', 'canceled')",
                            offset, transferId);
                }
                return new TransferResume(transferId, offset, row.bytes(), row.sha256(), row.status());
            } catch (IOException exception) {
                throw new IllegalArgumentException("cannot inspect Center transfer resume state", exception);
            }
        }
    }

    /**
     * Append one Content-Range chunk.  Chunks are committed in offset order;
     * a replay of an already stored range simply returns the current offset.
     * The partial file is intentionally retained for transient failures and
     * is promoted to the configured ArtifactStore only after the final hash
     * check succeeds.
     */
    public FileTransferResponse receiveFromAgentChunk(String machineId, String transferId, InputStream input,
                                                      long contentLength, long offset, long total,
                                                      String expectedSha256, String fileName, String mimeType,
                                                      Integer attempt) {
        var row = findTransfer(transferId).orElseThrow(() -> new IllegalArgumentException("file transfer not found"));
        if (!machineId.equals(row.machineId())) throw new SecurityException("file transfer does not belong to this machine");
        if (!FileTransferAction.AGENT_TO_WEB.equals(row.direction())) throw new IllegalArgumentException("transfer is not an outbound file");
        if (input == null || contentLength < 0 || contentLength > MAX_RESUMABLE_CHUNK_BYTES
                || total < 0 || total > MAX_BYTES || offset < 0 || offset > total
                || contentLength != total - offset && contentLength <= 0) {
            throw new IllegalArgumentException("invalid resumable file transfer range");
        }
        if (jdbc != null && row.bytes() > 0 && row.bytes() != total) {
            throw new IllegalArgumentException("resumable transfer size does not match its original metadata");
        }
        tasks.assertCurrentAttempt(machineId, row.taskId(), attempt);
        synchronized (transferLocks.computeIfAbsent(transferId, ignored -> new Object())) {
            try {
                if ("delivered".equals(row.status())) {
                    return new FileTransferResponse(row.transferId(), row.artifactId(), row.status(), row.bytes(), row.sha256(), null);
                }
                if ("failed".equals(row.status()) || "canceled".equals(row.status())) {
                    throw new IllegalArgumentException("file transfer is not resumable");
                }
                var partial = resumePath(transferId);
                var current = Files.exists(partial) ? Files.size(partial) : 0L;
                if (current > total || current > MAX_BYTES) throw new IOException("Center resume spool exceeds the declared size");
                if (offset < current) {
                    if (current < total) {
                        return new FileTransferResponse(row.transferId(), row.artifactId(), "delivering", current, "", null);
                    }
                    // The previous final response may have been lost after
                    // the body reached the total size but before metadata
                    // commit.  Re-enter the finalisation path without
                    // appending the replayed bytes a second time.
                    offset = current;
                    contentLength = 0;
                    input = InputStream.nullInputStream();
                }
                if (offset > current) throw new IllegalArgumentException("resumable transfer offset has a gap");
                if (offset + contentLength > total) throw new IllegalArgumentException("resumable chunk exceeds declared size");
                var safeSha = expectedSha256 == null ? "" : expectedSha256.trim().toLowerCase(java.util.Locale.ROOT);
                if (!safeSha.isBlank() && !SHA256.matcher(safeSha).matches()) {
                    throw new IllegalArgumentException("expected SHA-256 is invalid");
                }
                var declaredSha = row.sha256() == null ? "" : row.sha256().trim().toLowerCase(java.util.Locale.ROOT);
                if (!declaredSha.isBlank() && !safeSha.isBlank() && !declaredSha.equals(safeSha)) {
                    throw new IllegalArgumentException("resumable transfer SHA-256 does not match its original metadata");
                }
                var safeName = fileName == null || fileName.isBlank() ? row.fileName() : fileName;
                validateFileName(safeName);
                var safeMime = normalizeMime(mimeType == null || mimeType.isBlank() ? row.mimeType() : mimeType);
                initializeChunkMetadata(row, total, safeSha);
                markDelivering(row);
                resources.ensurePersistentSpoolCapacity(partial.getParent(), contentLength);
                try (var reservation = resources.reserve(row.principalId(), row.machineId(), contentLength)) {
                    appendChunk(input, partial, contentLength, transferId);
                }
                var next = Files.size(partial);
                updateTransferProgress(transferId, next);
                if (next < total) {
                    return new FileTransferResponse(row.transferId(), row.artifactId(), "delivering", next, "", null);
                }
                if (next != total) throw new IOException("resumable transfer ended at an invalid offset");
                var actualSha = sha256File(partial);
                var finalSha = safeSha.isBlank() ? actualSha : safeSha;
                if (!actualSha.equalsIgnoreCase(finalSha)) {
                    markFailed(row, "resumable transfer SHA-256 mismatch");
                    Files.deleteIfExists(partial);
                    transferLocks.remove(transferId);
                    throw new IllegalArgumentException("resumable transfer SHA-256 mismatch");
                }
                String objectKey = null;
                try {
                    try (var objectInput = Files.newInputStream(partial, StandardOpenOption.READ)) {
                        objectKey = store.put(row.artifactId(), actualSha, objectInput, total);
                    }
                    tasks.assertCurrentAttempt(machineId, row.taskId(), attempt);
                    completeOutbound(row, safeMime, safeName, total, actualSha, objectKey);
                    memory.put(transferId, new MemoryTransfer(new TransferDescriptor(transferId, row.artifactId(), row.direction(), row.taskId(),
                            row.principalId(), row.machineId(), safeName, safeMime, total, actualSha, "delivered", null,
                            publicUrl(row.artifactId(), new TaskOrigin(row.principalId(), TaskOrigin.COMPAT_TOKEN, ""), taskSession(row.taskId()), "download")),
                            objectKey, "", row.sourcePath()));
                    Files.deleteIfExists(partial);
                    transferLocks.remove(transferId);
                    return new FileTransferResponse(transferId, row.artifactId(), "delivered", total, actualSha, null);
                } catch (RuntimeException failure) {
                    if (objectKey != null && !(failure instanceof SecurityException)) {
                        try { store.delete(objectKey); } catch (RuntimeException ignored) { }
                    }
                    throw failure;
                }
            } catch (IOException exception) {
                // A dropped connection is recoverable: keep the partial file
                // and leave the durable row in delivering state for the next
                // Agent attempt instead of converting a transient failure to
                // a terminal transfer error.
                throw new TransferTemporaryException("resumable file transfer chunk interrupted: " + exception.getMessage(), exception);
            }
        }
    }

    /**
     * Persist the Agent's post-stream acknowledgement. The operation is
     * idempotent and never downgrades a terminal delivered/canceled transfer.
     */
    public FileTransferResponse acknowledgeFromAgent(String machineId, String transferId,
                                                     FileTransferResponse acknowledgement, Integer attempt) {
        var row = findTransfer(transferId).orElseThrow(() -> new IllegalArgumentException("file transfer not found"));
        if (!machineId.equals(row.machineId())) throw new SecurityException("file transfer does not belong to this machine");
        if (acknowledgement == null) throw new IllegalArgumentException("file transfer acknowledgement is required");
        var status = acknowledgement.status() == null ? "" : acknowledgement.status().trim().toLowerCase(java.util.Locale.ROOT);
        if (!Set.of("delivered", "failed", "canceled").contains(status)) {
            throw new IllegalArgumentException("unsupported file transfer acknowledgement status");
        }
        tasks.assertCurrentAttempt(machineId, row.taskId(), attempt);
        var bytes = acknowledgement.bytes() < 0 ? 0 : acknowledgement.bytes();
        var sha = acknowledgement.sha256() == null ? "" : acknowledgement.sha256().trim().toLowerCase(java.util.Locale.ROOT);
        if ("delivered".equals(status)) {
            if (bytes != row.bytes()) throw new IllegalArgumentException("acknowledged byte count does not match transfer metadata");
            if (row.sha256() != null && !row.sha256().isBlank() && !row.sha256().equalsIgnoreCase(sha)) {
                throw new IllegalArgumentException("acknowledged SHA-256 does not match transfer metadata");
            }
        }
        var error = acknowledgement.error() == null || acknowledgement.error().isBlank()
                ? null : acknowledgement.error().substring(0, Math.min(acknowledgement.error().length(), 4096));
        if (jdbc == null) {
            var current = memory.get(transferId);
            if (current == null) return new FileTransferResponse(row.transferId(), row.artifactId(), row.status(), row.bytes(), row.sha256(), null);
            var currentStatus = current.descriptor().status();
            if (!"delivered".equals(currentStatus) && !"canceled".equals(currentStatus)) {
                var descriptor = new TransferDescriptor(current.descriptor().transferId(), current.descriptor().artifactId(),
                        current.descriptor().direction(), current.descriptor().taskId(), current.descriptor().principalId(),
                        current.descriptor().machineId(), current.descriptor().fileName(), current.descriptor().mimeType(),
                        "delivered".equals(status) ? bytes : current.descriptor().bytes(),
                        sha.isBlank() ? current.descriptor().sha256() : sha, status, error,
                        current.descriptor().downloadUrl());
                memory.put(transferId, new MemoryTransfer(descriptor, current.objectKey(), current.destinationPath(), current.sourcePath()));
            }
            var effective = memory.get(transferId).descriptor();
            return new FileTransferResponse(effective.transferId(), effective.artifactId(), effective.status(), effective.bytes(), effective.sha256(), effective.error());
        }
        jdbc.update("""
                UPDATE rcm_file_transfer
                   SET status = CASE WHEN status IN ('delivered', 'canceled') THEN status ELSE ? END,
                       bytes_transferred = CASE WHEN ? = 'delivered' THEN ? ELSE bytes_transferred END,
                       error_text = CASE WHEN status IN ('delivered', 'canceled') THEN error_text ELSE ? END,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE transfer_id = ?
                """, status, status, bytes, error, transferId);
        var updated = findTransfer(transferId).orElse(row);
        return new FileTransferResponse(updated.transferId(), updated.artifactId(), updated.status(),
                updated.bytes(), updated.sha256(), error);
    }

    /** Ingest an outbound Agent PUT without loading the body into the heap. */
    public FileTransferResponse receiveFromAgent(String machineId, String transferId, InputStream input,
                                                 long contentLength, String expectedSha256,
                                                 String fileName, String mimeType) {
        return receiveFromAgent(machineId, transferId, input, contentLength, expectedSha256, fileName, mimeType, null);
    }

    /** Ingest an outbound upload and fence the final metadata commit. */
    public FileTransferResponse receiveFromAgent(String machineId, String transferId, InputStream input,
                                                 long contentLength, String expectedSha256,
                                                 String fileName, String mimeType, Integer attempt) {
        var row = findTransfer(transferId).orElseThrow(() -> new IllegalArgumentException("file transfer not found"));
        if (!machineId.equals(row.machineId())) throw new SecurityException("file transfer does not belong to this machine");
        if (!FileTransferAction.AGENT_TO_WEB.equals(row.direction())) throw new IllegalArgumentException("transfer is not an outbound file");
        // -1 is the servlet/chunked sentinel.  Unknown-length uploads still
        // reserve the full stream ceiling so a client cannot bypass the spool
        // quota by omitting Content-Length; spool() enforces the same ceiling
        // while it counts bytes.
        if (input == null || contentLength < -1 || contentLength > MAX_BYTES) throw new IllegalArgumentException("content length is outside the allowed range");
        if ("delivered".equals(row.status())) {
            return new FileTransferResponse(row.transferId(), row.artifactId(), row.status(), row.bytes(), row.sha256(), null);
        }
        if ("canceled".equals(row.status())) throw new IllegalArgumentException("file transfer is canceled");
        var reservationBytes = contentLength >= 0 ? contentLength : MAX_BYTES;
        try (var reservation = resources.reserve(row.principalId(), row.machineId(), reservationBytes)) {
            var safeName = fileName == null || fileName.isBlank() ? row.fileName() : fileName;
            validateFileName(safeName);
            var safeMime = normalizeMime(mimeType == null || mimeType.isBlank() ? row.mimeType() : mimeType);
            markDelivering(row);
            Path temporary = null;
            String objectKey = null;
            var committed = false;
            try {
            var received = spool(input, contentLength, expectedSha256, transferId);
            temporary = received.path();
            var artifactId = row.artifactId();
            try (var objectInput = Files.newInputStream(temporary, StandardOpenOption.READ)) {
                objectKey = store.put(artifactId, received.sha256(), objectInput, received.bytes());
            }
            // The lease can expire while the body is streaming.  Do this
            // immediately before the durable metadata update so a reclaimed
            // attempt cannot make an old Agent upload visible to the Web UI.
            tasks.assertCurrentAttempt(machineId, row.taskId(), attempt);
            var response = new FileTransferResponse(transferId, artifactId, "delivered", received.bytes(), received.sha256(), null);
            completeOutbound(row, safeMime, safeName, received.bytes(), received.sha256(), objectKey);
            committed = true;
            memory.put(transferId, new MemoryTransfer(new TransferDescriptor(transferId, artifactId, row.direction(), row.taskId(),
                    row.principalId(), row.machineId(), safeName, safeMime, received.bytes(), received.sha256(), "delivered", null,
                    publicUrl(artifactId, new TaskOrigin(row.principalId(), TaskOrigin.COMPAT_TOKEN, ""), taskSession(row.taskId()), "download")),
                    objectKey, "", row.sourcePath()));
            return response;
        } catch (IOException exception) {
            markFailed(row, exception.getMessage());
            throw new IllegalArgumentException("could not store Agent file: " + exception.getMessage(), exception);
        } catch (RuntimeException exception) {
            // A stale attempt or a metadata transaction failure can happen
            // after the stream has already been written to the object store.
            // Do not leave that unreferenced object behind for the next GC
            // cycle; a successful metadata commit is the only point at which
            // the object becomes owned by the transfer.
            if (!(exception instanceof SecurityException)) {
                markFailed(row, exception.getMessage());
            }
            if (!committed && objectKey != null) {
                try { store.delete(objectKey); } catch (RuntimeException ignored) { }
            }
            throw exception;
            } finally {
                deleteTemporary(temporary);
            }
        }
    }

    public Optional<TransferDescriptor> findByArtifact(String artifactId, TaskOrigin origin) {
        if (artifactId == null || artifactId.isBlank()) return Optional.empty();
        TransferDescriptor row = jdbc == null ? memory.values().stream().map(MemoryTransfer::descriptor)
                .filter(value -> artifactId.equals(value.artifactId())).findFirst().orElse(null)
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
                row.machineId(), row.fileName(), row.mimeType(), row.bytes(), row.sha256(), row.status(), row.error(),
                downloadUrl(row.artifactId(), row.status(), taskSession(row.taskId()), origin)));
    }

    /**
     * Low-cardinality transfer SLO projection.  It reads metadata counters
     * only; payload bytes and paths never enter the metrics response.
     */
    public TransferMetrics transferMetrics() {
        if (jdbc == null) {
            var active = 0L;
            var delivered = 0L;
            var failed = 0L;
            var canceled = 0L;
            var bytes = 0L;
            var expected = 0L;
            for (var value : memory.values()) {
                var descriptor = value.descriptor();
                switch (descriptor.status()) {
                    case "pending", "ready", "delivering" -> active++;
                    case "delivered" -> delivered++;
                    case "failed" -> failed++;
                    case "canceled" -> canceled++;
                    default -> { }
                }
                bytes = saturatingAdd(bytes, Math.max(0L, descriptor.bytes()));
                expected = saturatingAdd(expected, Math.max(0L, descriptor.bytes()));
            }
            return new TransferMetrics(active, delivered, failed, canceled, bytes, expected, 0.0, 0.0);
        }
        return jdbc.query("""
                SELECT COUNT(*) FILTER (WHERE status IN ('pending', 'ready', 'delivering')),
                       COUNT(*) FILTER (WHERE status = 'delivered'),
                       COUNT(*) FILTER (WHERE status = 'failed'),
                       COUNT(*) FILTER (WHERE status = 'canceled'),
                       COALESCE(SUM(bytes_transferred), 0),
                       COALESCE(SUM(expected_bytes), 0),
                       COALESCE(AVG(EXTRACT(EPOCH FROM (updated_at - created_at)))
                                FILTER (WHERE status IN ('delivered', 'failed', 'canceled')), 0),
                       COALESCE(MAX(EXTRACT(EPOCH FROM (updated_at - created_at)))
                                FILTER (WHERE status IN ('delivered', 'failed', 'canceled')), 0)
                  FROM rcm_file_transfer
                """, rs -> rs.next() ? new TransferMetrics(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                rs.getLong(5), rs.getLong(6), rs.getDouble(7), rs.getDouble(8))
                : new TransferMetrics(0, 0, 0, 0, 0, 0, 0.0, 0.0));
    }

    private TransferDescriptor withDownloadUrl(TransferDescriptor value, TaskOrigin origin) {
        return new TransferDescriptor(value.transferId(), value.artifactId(), value.direction(), value.taskId(), value.principalId(),
                value.machineId(), value.fileName(), value.mimeType(), value.bytes(), value.sha256(), value.status(), value.error(),
                downloadUrl(value.artifactId(), value.status(), taskSession(value.taskId()), origin));
    }

    /** Do not advertise a file URL until the object is durably published. */
    private String downloadUrl(String artifactId, String status, String sessionId, TaskOrigin origin) {
        return ("ready".equals(status) || "delivered".equals(status))
                ? publicUrl(artifactId, origin, sessionId, "download") : "";
    }

    private String taskSession(String taskId) {
        if (taskId == null || taskId.isBlank()) return "";
        return tasks.find(taskId).map(TaskView::new).map(TaskView::executionSessionId).orElse("");
    }

    /** Public file object endpoint URL; the URL carries a short-lived HMAC. */
    public String publicUrl(String artifactId, TaskOrigin origin) {
        return publicUrl(artifactId, origin, "", "download");
    }

    /**
     * Creates a URL bound to the execution session that produced the artifact.
     * A session is intentionally part of the signed subject rather than an
     * authorization header so ChatGPT/React can fetch the file directly.
     */
    public String publicUrl(String artifactId, TaskOrigin origin, String executionSessionId, String purpose) {
        if (artifactId == null || artifactId.isBlank() || origin == null) return "";
        var safePurpose = purpose == null || purpose.isBlank() ? "download" : purpose.trim().toLowerCase(java.util.Locale.ROOT);
        if (!Set.of("download", "preview").contains(safePurpose)) return "";
        var session = executionSessionId == null ? "" : executionSessionId.trim();
        if (session.length() > 256) return "";
        var expires = Instant.now().plus(signedUrlTtl).getEpochSecond();
        var connection = origin.connectionId() == null ? "" : origin.connectionId();
        if (connection.length() > 256) return "";
        var subject = signatureSubject(artifactId, origin.principalId(), expires, safePurpose, connection, session);
        var signature = sign(subject);
        var base = publicBaseUrl.isBlank() ? "" : publicBaseUrl;
        return base + "/artifacts/" + artifactId + "/content?expires=" + expires + "&principal="
                + encode(origin.principalId()) + "&purpose=" + safePurpose + "&connection=" + encode(connection)
                + "&session=" + encode(session) + "&signature=" + encode(signature);
    }

    public PublicArtifact openPublic(String artifactId, long expires, String principal, String signature) {
        return openPublic(artifactId, expires, principal, "", "", "download", signature);
    }

    /** Compatibility overload for links issued before session binding. */
    public PublicArtifact openPublic(String artifactId, long expires, String principal, String connection,
                                     String purpose, String signature) {
        return openPublic(artifactId, expires, principal, connection, "", purpose, signature);
    }

    public PublicArtifact openPublic(String artifactId, long expires, String principal, String connection,
                                     String session, String purpose, String signature) {
        if (artifactId == null || artifactId.isBlank() || principal == null || principal.isBlank()
                || expires < Instant.now().getEpochSecond() || signature == null
                || purpose == null || !Set.of("download", "preview").contains(purpose)
                || connection == null || connection.length() > 256
                || session == null || session.length() > 256) {
            throw new SecurityException("invalid or expired artifact URL");
        }
        var supplied = signature.trim().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var expectedSignature = sign(signatureSubject(artifactId, principal, expires, purpose, connection, session));
        var valid = MessageDigest.isEqual(expectedSignature.getBytes(java.nio.charset.StandardCharsets.US_ASCII), supplied);
        // Keep already-issued connection-bound links valid during a rolling
        // deployment.  The legacy fallback is only reachable when both new
        // binding fields are omitted; newly generated links always include
        // purpose, connection, and session.
        if (!valid && session.isBlank()) {
            valid = MessageDigest.isEqual(sign(signatureSubject(artifactId, principal, expires, purpose, connection, ""))
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII), supplied);
        }
        if (!valid && connection.isBlank() && session.isBlank()) {
            valid = MessageDigest.isEqual(sign(artifactId + "\n" + principal + "\n" + expires)
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII), supplied);
        }
        if (!valid) {
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
        if (jdbc != null) {
            var retainedUntil = jdbc.query("SELECT expires_at FROM rcm_artifact WHERE artifact_id = ? AND principal_id = ?",
                    ps -> { ps.setString(1, artifactId); ps.setString(2, principal); },
                    rs -> rs.next() ? rs.getTimestamp(1).toInstant() : null);
            if (retainedUntil != null && !Instant.now().isBefore(retainedUntil)) {
                throw new IllegalArgumentException("artifact retention has expired");
            }
        }
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

    private boolean persistInboundReservation(TaskOrigin origin, TransferDescriptor descriptor, String destinationPath) {
        if (jdbc == null) return true;
        java.util.function.Supplier<Boolean> write = () -> jdbc.update("""
                INSERT INTO rcm_file_transfer(transfer_id, artifact_id, task_id, principal_id, machine_id, direction, source_path, destination_path, file_name, mime_type, expected_bytes, expected_sha256, bytes_transferred, status, error_text, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, 0, NULL, 0, 'pending', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (transfer_id) DO NOTHING
                """, descriptor.transferId(), descriptor.artifactId(), descriptor.taskId(), origin.principalId(), descriptor.machineId(),
                descriptor.direction(), destinationPath, descriptor.fileName(), descriptor.mimeType()) > 0;
        return transactions == null ? write.get() : Boolean.TRUE.equals(transactions.execute(status -> write.get()));
    }

    private void completeInbound(TaskOrigin origin, String machineId, String taskId, Ids ids, String fileName, String mimeType,
                                 Downloaded downloaded, String objectKey) {
        if (jdbc == null) return;
        Runnable write = () -> {
            jdbc.update("""
                    INSERT INTO rcm_artifact(artifact_id, principal_id, machine_id, task_id, transfer_id, file_name, mime_type, storage_backend, object_key, bytes, sha256, status, created_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ready', CURRENT_TIMESTAMP, ?)
                    ON CONFLICT (artifact_id) DO UPDATE SET object_key = EXCLUDED.object_key, bytes = EXCLUDED.bytes, sha256 = EXCLUDED.sha256, mime_type = EXCLUDED.mime_type, file_name = EXCLUDED.file_name, status = 'ready', expires_at = EXCLUDED.expires_at
                    """, ids.artifactId(), origin.principalId(), machineId, taskId, ids.transferId(), fileName, mimeType,
                    store.backend(), objectKey, downloaded.bytes(), downloaded.sha256(), java.sql.Timestamp.from(Instant.now().plus(artifactRetention)));
            jdbc.update("""
                    UPDATE rcm_file_transfer
                       SET artifact_id = ?, expected_bytes = ?, expected_sha256 = ?, bytes_transferred = 0,
                           status = 'ready', error_text = NULL, updated_at = CURRENT_TIMESTAMP
                     WHERE transfer_id = ?
                    """, ids.artifactId(), downloaded.bytes(), downloaded.sha256(), ids.transferId());
        };
        if (transactions == null) write.run(); else transactions.execute(status -> { write.run(); return null; });
    }

    private void markFailedByTransfer(String transferId, String error) {
        var message = error == null || error.isBlank() ? "file transfer preparation failed" : error;
        var safe = SensitiveValueRedactor.redact(message.substring(0, Math.min(message.length(), 4096)));
        if (jdbc != null) {
            jdbc.update("UPDATE rcm_file_transfer SET status = 'failed', error_text = ?, updated_at = CURRENT_TIMESTAMP WHERE transfer_id = ? AND status NOT IN ('delivered', 'canceled')",
                    safe, transferId);
            return;
        }
        var current = memory.get(transferId);
        if (current == null) return;
        var descriptor = new TransferDescriptor(current.descriptor().transferId(), current.descriptor().artifactId(), current.descriptor().direction(),
                current.descriptor().taskId(), current.descriptor().principalId(), current.descriptor().machineId(), current.descriptor().fileName(),
                current.descriptor().mimeType(), current.descriptor().bytes(), current.descriptor().sha256(), "failed", safe,
                current.descriptor().downloadUrl());
        memory.put(transferId, new MemoryTransfer(descriptor, current.objectKey(), current.destinationPath(), current.sourcePath()));
    }

    private void persistInbound(TaskOrigin origin, TransferDescriptor descriptor, String objectKey, String destinationPath) {
        if (jdbc == null) return;
        Runnable write = () -> {
            jdbc.update("""
                    INSERT INTO rcm_artifact(artifact_id, principal_id, machine_id, task_id, transfer_id, file_name, mime_type, storage_backend, object_key, bytes, sha256, status, created_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?)
                    ON CONFLICT (artifact_id) DO NOTHING
                    """, descriptor.artifactId(), origin.principalId(), descriptor.machineId(), descriptor.taskId(), descriptor.transferId(),
                    descriptor.fileName(), descriptor.mimeType(), store.backend(), objectKey, descriptor.bytes(), descriptor.sha256(), "ready",
                    java.sql.Timestamp.from(Instant.now().plus(artifactRetention)));
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
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ready', CURRENT_TIMESTAMP, ?)
                    ON CONFLICT (artifact_id) DO UPDATE SET object_key = EXCLUDED.object_key, bytes = EXCLUDED.bytes, sha256 = EXCLUDED.sha256, mime_type = EXCLUDED.mime_type, file_name = EXCLUDED.file_name, status = 'ready'
                    """, row.artifactId(), row.principalId(), row.machineId(), row.taskId(), row.transferId(), fileName, mime, store.backend(), objectKey, bytes, sha,
                    java.sql.Timestamp.from(Instant.now().plus(artifactRetention)));
            jdbc.update("UPDATE rcm_file_transfer SET file_name = ?, mime_type = ?, expected_bytes = ?, expected_sha256 = ?, bytes_transferred = ?, status = 'delivered', error_text = NULL, updated_at = CURRENT_TIMESTAMP WHERE transfer_id = ?",
                    fileName, mime, bytes, sha, bytes, row.transferId());
        };
        if (transactions == null) write.run(); else transactions.execute(status -> { write.run(); return null; });
    }

    private void markFailed(TransferRow row, String error) {
        markFailedByTransfer(row.transferId(), error);
    }

    private void markDelivering(TransferRow row) {
        if (jdbc != null) {
            jdbc.update("UPDATE rcm_file_transfer SET status = CASE WHEN status IN ('delivered', 'canceled') THEN status ELSE 'delivering' END, updated_at = CURRENT_TIMESTAMP WHERE transfer_id = ?",
                    row.transferId());
            return;
        }
        var current = memory.get(row.transferId());
        if (current == null || "delivered".equals(current.descriptor().status()) || "canceled".equals(current.descriptor().status())) return;
        var descriptor = new TransferDescriptor(current.descriptor().transferId(), current.descriptor().artifactId(), current.descriptor().direction(),
                current.descriptor().taskId(), current.descriptor().principalId(), current.descriptor().machineId(), current.descriptor().fileName(),
                current.descriptor().mimeType(), current.descriptor().bytes(), current.descriptor().sha256(), "delivering", current.descriptor().error(),
                current.descriptor().downloadUrl());
        memory.put(row.transferId(), new MemoryTransfer(descriptor, current.objectKey(), current.destinationPath(), current.sourcePath()));
    }

    private Optional<TransferRow> findTransfer(String transferId) {
        if (transferId == null || transferId.isBlank()) return Optional.empty();
        if (jdbc == null) {
            return Optional.ofNullable(memory.get(transferId.trim())).map(value -> new TransferRow(value.descriptor().transferId(), value.descriptor().artifactId(),
                    value.descriptor().taskId(), value.descriptor().principalId(), value.descriptor().machineId(), value.descriptor().direction(),
                    value.sourcePath(), value.destinationPath(), value.descriptor().fileName(), value.descriptor().mimeType(), value.descriptor().bytes(), value.descriptor().sha256(), value.descriptor().status(), value.descriptor().error(), value.objectKey()));
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
                rs.getString("file_name"), rs.getString("mime_type"), rs.getLong("expected_bytes"), rs.getString("expected_sha256"), rs.getString("status"), rs.getString("error_text"), rs.getString("object_key"));
    }

    private static TransferDescriptor descriptor(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TransferDescriptor(rs.getString("transfer_id"), rs.getString("artifact_id"), rs.getString("direction"), rs.getString("task_id"),
                rs.getString("principal_id"), rs.getString("machine_id"), rs.getString("file_name"), rs.getString("mime_type"), rs.getLong("bytes"), rs.getString("sha256"),
                rs.getString("status"), rs.getString("error_text"), "");
    }

    private Downloaded download(URI uri, Long expectedBytes, String expectedSha256, String transferId) throws IOException {
        try {
            var request = HttpRequest.newBuilder(uri).timeout(DOWNLOAD_TIMEOUT).header("Accept", "application/octet-stream").GET().build();
            var response = downloader.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                close(response.body());
                throw new IOException("download URL returned HTTP " + response.statusCode());
            }
            var length = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (length > MAX_BYTES || (expectedBytes != null && length >= 0 && length != expectedBytes)) {
                close(response.body());
                throw new IOException("download size is outside the declared limit");
            }
            return spool(response.body(), expectedBytes == null ? length : expectedBytes, expectedSha256, transferId);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("download interrupted", exception);
        }
    }

    private Downloaded spool(InputStream input, long expectedBytes, String expectedSha256, String transferId) throws IOException {
        if (expectedBytes > MAX_BYTES) throw new IOException("file size is outside the allowed range");
        Files.createDirectories(transferSpoolRoot);
        var temporary = Files.createTempFile(transferSpoolRoot, "rcm-transfer-", ".part");
        try (input; var output = Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING)) {
            var digest = MessageDigest.getInstance("SHA-256");
            var buffer = new byte[1024 * 1024];
            long count = 0;
            long lastProgress = 0;
            long lastProgressNanos = System.nanoTime();
            var deadlineNanos = System.nanoTime() + DOWNLOAD_TIMEOUT.toNanos();
            int read;
            while ((read = readWithWatchdog(input, buffer, deadlineNanos)) >= 0) {
                if (read == 0) continue;
                count += read;
                if ((expectedBytes >= 0 && count > expectedBytes) || count > MAX_BYTES) throw new IOException("file exceeds declared size");
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                var now = System.nanoTime();
                if (transferId != null && !transferId.isBlank()
                        && (count - lastProgress >= PROGRESS_STEP_BYTES || now - lastProgressNanos >= PROGRESS_STEP_NANOS)) {
                    updateTransferProgress(transferId, count);
                    lastProgress = count;
                    lastProgressNanos = now;
                }
            }
            if (transferId != null && !transferId.isBlank()) updateTransferProgress(transferId, count);
            var sha = HexFormat.of().formatHex(digest.digest());
            if ((expectedBytes >= 0 && count != expectedBytes) || (expectedSha256 != null && !expectedSha256.isBlank() && !sha.equalsIgnoreCase(expectedSha256.trim()))) {
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

    private void initializeChunkMetadata(TransferRow row, long total, String expectedSha256) {
        if (jdbc == null) return;
        var safeSha = expectedSha256 == null || expectedSha256.isBlank() ? null : expectedSha256;
        jdbc.update("""
                UPDATE rcm_file_transfer
                   SET expected_bytes = CASE WHEN expected_bytes = 0 THEN ? ELSE expected_bytes END,
                       expected_sha256 = CASE WHEN COALESCE(expected_sha256, '') = '' THEN ? ELSE expected_sha256 END,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE transfer_id = ? AND status NOT IN ('delivered', 'failed', 'canceled')
                """, total, safeSha, row.transferId());
    }

    private void appendChunk(InputStream input, Path partial, long expectedBytes, String transferId) throws IOException {
        Files.createDirectories(partial.getParent());
        try (var output = Files.newOutputStream(partial, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            var buffer = new byte[1024 * 1024];
            var deadlineNanos = System.nanoTime() + DOWNLOAD_TIMEOUT.toNanos();
            long count = 0;
            int read;
            while ((read = readWithWatchdog(input, buffer, deadlineNanos)) >= 0) {
                if (read == 0) continue;
                count += read;
                if (count > expectedBytes) throw new IOException("resumable chunk exceeds its Content-Range length");
                output.write(buffer, 0, read);
            }
            if (count != expectedBytes) throw new IOException("resumable chunk body is shorter than Content-Range");
        }
    }

    private Path resumePath(String transferId) throws IOException {
        if (transferId == null || transferId.isBlank()) throw new IOException("transfer id is required");
        var resumeRoot = transferSpoolRoot.resolve("resume").normalize();
        Files.createDirectories(resumeRoot);
        var safe = transferId.replaceAll("[^A-Za-z0-9._-]", "_");
        var path = resumeRoot.resolve(safe + ".part").normalize();
        if (!path.startsWith(resumeRoot)) throw new IOException("unsafe transfer resume path");
        return path;
    }

    private static String sha256File(Path path) throws IOException {
        try (var input = Files.newInputStream(path, StandardOpenOption.READ)) {
            var digest = MessageDigest.getInstance("SHA-256");
            var buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
    }

    private static void skipFully(InputStream input, long bytes) throws IOException {
        var remaining = bytes;
        while (remaining > 0) {
            var skipped = input.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
                continue;
            }
            if (input.read() < 0) throw new IOException("artifact ended before the requested resume offset");
            remaining--;
        }
    }

    /**
     * A request timeout covers connection establishment, but a streaming body
     * can otherwise remain blocked forever after the response has started. Run
     * each potentially blocking read on the existing virtual-thread executor;
     * cancellation closes the body and enforces both the no-progress and
     * absolute transfer deadlines without a polling timer.
     */
    private int readWithWatchdog(InputStream input, byte[] buffer, long deadlineNanos) throws IOException {
        if (async == null) return input.read(buffer);
        var remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) throw new IOException("file transfer exceeded the maximum lifetime");
        var waitNanos = Math.min(remaining, transferStallTimeout.toNanos());
        var read = async.submit(() -> input.read(buffer));
        try {
            return read.get(waitNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            read.cancel(true);
            close(input);
            throw new IOException("file transfer stalled without progress", exception);
        } catch (InterruptedException exception) {
            read.cancel(true);
            close(input);
            Thread.currentThread().interrupt();
            throw new IOException("file transfer read interrupted", exception);
        } catch (ExecutionException exception) {
            var cause = exception.getCause();
            if (cause instanceof java.util.concurrent.CompletionException completion && completion.getCause() != null) {
                cause = completion.getCause();
            }
            if (cause instanceof IOException io) throw io;
            throw new IOException("file transfer read failed", cause == null ? exception : cause);
        }
    }

    private void updateTransferProgress(String transferId, long bytes) {
        if (jdbc != null) {
            jdbc.update("""
                    UPDATE rcm_file_transfer
                       SET bytes_transferred = CASE WHEN status IN ('pending', 'ready', 'delivering') THEN ? ELSE bytes_transferred END,
                           updated_at = CURRENT_TIMESTAMP
                     WHERE transfer_id = ?
                    """, bytes, transferId);
            return;
        }
        var current = memory.get(transferId);
        if (current == null) return;
        var descriptor = current.descriptor();
        if (Set.of("delivered", "failed", "canceled").contains(descriptor.status())) return;
        var updated = new TransferDescriptor(descriptor.transferId(), descriptor.artifactId(), descriptor.direction(), descriptor.taskId(),
                descriptor.principalId(), descriptor.machineId(), descriptor.fileName(), descriptor.mimeType(), Math.max(descriptor.bytes(), bytes),
                descriptor.sha256(), descriptor.status(), descriptor.error(), descriptor.downloadUrl());
        memory.put(transferId, new MemoryTransfer(updated, current.objectKey(), current.destinationPath(), current.sourcePath()));
    }

    private static long saturatingAdd(long left, long right) {
        if (right <= 0 || Long.MAX_VALUE - left < right) return Long.MAX_VALUE;
        return left + right;
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

    private static String encode(String value) { return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8); }
    private static String signatureSubject(String artifactId, String principal, long expires, String purpose,
                                           String connection, String session) {
        return artifactId + "\n" + principal + "\n" + expires + "\n" + purpose + "\n" + connection + "\n" + session;
    }
    private static String normalizeBase(String value) { return value == null ? "" : value.trim().replaceAll("/+$", ""); }
    private static Path spoolRoot() {
        var configured = System.getenv(TransferResourceLimiter.SPOOL_ROOT_ENV);
        var value = configured == null || configured.isBlank()
                ? System.getProperty("java.io.tmpdir", ".") : configured.trim();
        if (value.indexOf('\u0000') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalStateException(TransferResourceLimiter.SPOOL_ROOT_ENV + " contains invalid path characters");
        }
        return Path.of(value).toAbsolutePath().normalize();
    }
    private static Duration durationSetting(String key, Duration fallback, Duration minimum, Duration maximum) {
        var raw = System.getenv(key);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            var value = Duration.ofSeconds(Long.parseLong(raw.trim()));
            if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
                throw new IllegalStateException(key + " is outside the allowed range");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(key + " must be an integer number of seconds", exception);
        }
    }
    private static void deleteTemporary(Path path) { if (path != null) try { Files.deleteIfExists(path); } catch (IOException ignored) { } }
    private static void close(InputStream input) { if (input != null) try { input.close(); } catch (IOException ignored) { } }

    public record TransferCreated(TaskView task, TransferDescriptor transfer) { }
    public record TransferDescriptor(String transferId, String artifactId, String direction, String taskId,
                                     String principalId, String machineId, String fileName, String mimeType, long bytes,
                                     String sha256, String status, String error, String downloadUrl) {
    }
    public record AgentDownload(String transferId, String fileName, String mimeType, long bytes, String sha256, InputStream body,
                                long offset, long totalBytes) { }
    public record TransferResume(String transferId, long offset, long expectedBytes, String expectedSha256, String status) { }
    /** HTTP 503 marker used to make a dropped chunk eligible for AgentRetry. */
    public static final class TransferTemporaryException extends RuntimeException {
        public TransferTemporaryException(String message, Throwable cause) { super(message, cause); }
    }
    public record PublicArtifact(String artifactId, String fileName, String mimeType, long bytes, String sha256, String status, InputStream body) { }
    public record TransferMetrics(long active, long delivered, long failed, long canceled,
                                  long bytesTransferred, long expectedBytes,
                                  double averageDurationSeconds, double maxDurationSeconds) { }
    private record Downloaded(Path path, long bytes, String sha256) { }
    private record Ids(String transferId, String artifactId) { }
    private record TransferRow(String transferId, String artifactId, String taskId, String principalId, String machineId,
                               String direction, String sourcePath, String destinationPath, String fileName, String mimeType,
                               long bytes, String sha256, String status, String error, String objectKey) { }
    private record PendingReservation(String transferId, String taskId, String machineId) { }
    private record OrphanPreparedTask(String taskId, String machineId) { }
    private record MemoryTransfer(TransferDescriptor descriptor, String objectKey, String destinationPath, String sourcePath) { }
}
