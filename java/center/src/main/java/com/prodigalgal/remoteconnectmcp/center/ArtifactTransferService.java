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
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
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
    private static final int MAX_DOWNLOAD_REDIRECTS = 5;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ArtifactStore store;
    private final TaskService tasks;
    private final CenterTokenConfig tokens;
    private final TransferResourceLimiter resources;
    private final CenterAsyncExecutor async;
    private final McpQuotaService quota;
    private final Map<String, MemoryTransfer> memory = new ConcurrentHashMap<>();
    /** Single-Center transfer locks serialize overlapping chunk offsets. */
    private final Map<String, TransferLock> transferLocks = new ConcurrentHashMap<>();
    private final Map<String, Boolean> preparations = new ConcurrentHashMap<>();
    /** Short reservation hand-off for concurrent same-key MCP retries. */
    private final Map<String, CompletableFuture<TransferCreated>> asyncPreparations = new ConcurrentHashMap<>();
    private final AtomicLong resumeCount = new AtomicLong();
    private final AtomicLong partialSpoolBytes = new AtomicLong();
    private final Map<String, Long> partialSpoolByTransfer = new ConcurrentHashMap<>();
    private final HttpClient downloader = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final String publicBaseUrl;
    private final Duration artifactRetention;
    private final Duration webArtifactRetention;
    private final Duration largeArtifactRetention;
    private final Duration textArtifactRetention;
    private final Duration signedUrlTtl;
    private final Duration transferStallTimeout;
    private final Path transferSpoolRoot;
    private final SecureRandom accessTokenRandom = new SecureRandom();

    @Autowired
    public ArtifactTransferService(ObjectProvider<JdbcTemplate> jdbcProvider,
                                   ObjectProvider<TransactionTemplate> transactionProvider,
                                   ObjectProvider<ArtifactStore> storeProvider,
                                   TaskService tasks, CenterTokenConfig tokens, CenterAsyncExecutor async,
                                   ObjectProvider<McpQuotaService> quotaProvider) {
        this(jdbcProvider == null ? null : jdbcProvider.getIfAvailable(),
                transactionProvider == null ? null : transactionProvider.getIfAvailable(),
                storeProvider == null ? null : storeProvider.getIfAvailable(), tasks, tokens, async,
                quotaProvider == null ? null : quotaProvider.getIfAvailable());
    }

    ArtifactTransferService(JdbcTemplate jdbc, TransactionTemplate transactions, ArtifactStore store,
                            TaskService tasks, CenterTokenConfig tokens) {
        this(jdbc, transactions, store, tasks, tokens, null, null);
    }

    ArtifactTransferService(JdbcTemplate jdbc, TransactionTemplate transactions, ArtifactStore store,
                            TaskService tasks, CenterTokenConfig tokens, CenterAsyncExecutor async) {
        this(jdbc, transactions, store, tasks, tokens, async, null);
    }

    ArtifactTransferService(JdbcTemplate jdbc, TransactionTemplate transactions, ArtifactStore store,
                            TaskService tasks, CenterTokenConfig tokens, CenterAsyncExecutor async,
                            McpQuotaService quota) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.store = store == null ? new InMemoryArtifactStore() : store;
        this.tasks = tasks;
        this.tokens = tokens;
        this.resources = new TransferResourceLimiter();
        this.async = async;
        this.quota = quota;
        this.publicBaseUrl = normalizeBase(System.getenv("RCM_CENTER_PUBLIC_BASE_URL"));
        this.transferSpoolRoot = spoolRoot();
        this.artifactRetention = durationSetting("RCM_CENTER_ARTIFACT_RETENTION_SECONDS", DEFAULT_ARTIFACT_RETENTION,
                Duration.ofHours(1), Duration.ofDays(365));
        this.webArtifactRetention = durationSetting("RCM_CENTER_ARTIFACT_WEB_RETENTION_SECONDS", artifactRetention,
                Duration.ofMinutes(5), Duration.ofDays(365));
        this.largeArtifactRetention = durationSetting("RCM_CENTER_ARTIFACT_LARGE_RETENTION_SECONDS",
                artifactRetention, Duration.ofHours(1), Duration.ofDays(365));
        this.textArtifactRetention = durationSetting("RCM_CENTER_ARTIFACT_TEXT_RETENTION_SECONDS",
                artifactRetention, Duration.ofMinutes(5), Duration.ofDays(365));
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
        requireIdempotencyKey(request);
        validatePath(destinationPath, "destinationPath");
        var safeName = fileNameOrLeaf(fileName, destinationPath);
        validateFileName(safeName);
        var safeMime = normalizeMime(mimeType, safeName);
        var ids = ids(origin, request.machineId(), request.idempotencyKey(), fileId, destinationPath);
        var existing = existingCreated(ids.transferId(), origin);
        if (existing != null) return existing;
        if (quota != null) quota.assertTransferAdmission(origin,
                expectedBytes != null && expectedBytes >= 0 ? expectedBytes : MAX_BYTES, ids.transferId());
        // Only a new ingest consumes the host-provided URL.  An idempotent
        // retry after the URL has expired must reuse the durable reservation
        // instead of failing before it can observe the existing transfer.
        validateDownloadUrl(downloadUrl);
        if (async != null) {
            return createWebToAgentAsync(origin, request, ids, destinationPath, safeName, safeMime, overwrite,
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
            try (var objectInput = Files.newInputStream(temporary, LinkOption.NOFOLLOW_LINKS, StandardOpenOption.READ)) {
                objectKey = store.put(artifactId, downloaded.sha256(), objectInput, downloaded.bytes());
            }
            var action = new FileTransferAction(FileTransferAction.WEB_TO_AGENT, ids.transferId(), artifactId,
                    "", destinationPath, safeName, safeMime, downloaded.bytes(), downloaded.sha256(), overwrite);
            var task = tasks.create(withAction(request, action), "mcp", origin);
            var descriptor = new TransferDescriptor(ids.transferId(), artifactId, FileTransferAction.WEB_TO_AGENT,
                    task.id(), origin.principalId(), request.machineId(), safeName, safeMime, downloaded.bytes(), downloaded.sha256(),
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
                downloadUrl(row.artifactId(), row.status(), task.executionSessionId(), origin), row.bytesTransferred());
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
            var declaredBytes = expectedBytes != null && expectedBytes >= 0 ? expectedBytes : 0L;
            var declaredSha = expectedSha256 == null ? "" : expectedSha256.trim().toLowerCase(java.util.Locale.ROOT);
            if (!declaredSha.isBlank() && !SHA256.matcher(declaredSha).matches()) {
                throw new IllegalArgumentException("expected SHA-256 is invalid");
            }
            var pending = new FileTransferAction(FileTransferAction.WEB_TO_AGENT, ids.transferId(), ids.artifactId(),
                    "", destinationPath, fileName, safeMime, 0L, "", overwrite);
            var createdTask = tasks.create(withAction(request, pending), "mcp", origin);
            task = createdTask;
            var descriptor = new TransferDescriptor(ids.transferId(), ids.artifactId(), FileTransferAction.WEB_TO_AGENT,
                    createdTask.id(), origin.principalId(), request.machineId(), fileName, safeMime, declaredBytes, declaredSha, "pending", null,
                    "");
            if (!persistInboundReservation(origin, descriptor, destinationPath, declaredBytes, declaredSha)) {
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
            try (var objectInput = Files.newInputStream(temporary, LinkOption.NOFOLLOW_LINKS, StandardOpenOption.READ)) {
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
            memory.put(ids.transferId(), new MemoryTransfer(descriptor, objectKey, destinationPath, "",
                    memoryExpiry(ids.transferId())));
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
        requireIdempotencyKey(request);
        validatePath(sourcePath, "sourcePath");
        var safeName = fileNameOrLeaf(fileName, sourcePath);
        validateFileName(safeName);
        var safeMime = normalizeMime(mimeType, safeName);
        var ids = ids(origin, request.machineId(), request.idempotencyKey(), "", sourcePath);
        var existing = existingCreated(ids.transferId(), origin);
        if (existing != null) return existing;
        if (quota != null) quota.assertTransferAdmission(origin, MAX_BYTES, ids.transferId());
        var action = new FileTransferAction(FileTransferAction.AGENT_TO_WEB, ids.transferId(), ids.artifactId(),
                sourcePath, "", safeName, safeMime, 0L, "", false);
        var task = tasks.create(withAction(request, action), "mcp", origin);
        var descriptor = new TransferDescriptor(ids.transferId(), ids.artifactId(), FileTransferAction.AGENT_TO_WEB,
                task.id(), origin.principalId(), request.machineId(), safeName, safeMime, 0L, "", "pending", null,
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
        if (row.objectKey() == null || row.objectKey().isBlank()) {
            throw new IllegalArgumentException("file transfer payload is not available");
        }
        tasks.assertCurrentAttempt(machineId, row.taskId(), attempt);
        if (offset < 0 || offset > row.bytes()) throw new IllegalArgumentException("resume offset is outside the transfer size");
        if (offset > 0) resumeCount.incrementAndGet();
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
        var guard = acquireTransferLock(transferId);
        try {
            synchronized (guard.monitor) {
            // Refresh after waiting for the monitor.  A previous caller may
            // have finalized the transfer while this request was queued; the
            // pre-lock snapshot must never be used to recreate a stale offset.
            row = findTransfer(transferId).orElseThrow(() -> new IllegalArgumentException("file transfer not found"));
            try {
                var partial = resumePath(transferId);
                var offset = "delivered".equals(row.status()) ? row.bytes()
                        : regularFileSize(partial);
                if (offset > 0) resumeCount.incrementAndGet();
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
        } finally {
            releaseTransferLock(transferId, guard);
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
        var guard = acquireTransferLock(transferId);
        try {
            synchronized (guard.monitor) {
            // Refresh after waiting for the monitor so a late duplicate sees
            // the authoritative delivered/failed state instead of a stale
            // pre-lock row and a deleted partial spool.
            row = findTransfer(transferId).orElseThrow(() -> new IllegalArgumentException("file transfer not found"));
            try {
                if ("delivered".equals(row.status())) {
                    return new FileTransferResponse(row.transferId(), row.artifactId(), row.status(), row.bytes(), row.sha256(), null);
                }
                if ("failed".equals(row.status()) || "canceled".equals(row.status())) {
                    throw new IllegalArgumentException("file transfer is not resumable");
                }
                var partial = resumePath(transferId);
                var current = regularFileSize(partial);
                if (current > 0) resumeCount.incrementAndGet();
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
                try (var persistent = resources.ensurePersistentSpoolCapacity(partial.getParent(), contentLength);
                     var reservation = resources.reserve(row.principalId(), row.machineId(), contentLength)) {
                    appendChunk(input, partial, contentLength, transferId);
                }
                var next = regularFileSize(partial);
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
                    forgetPartialSpool(transferId);
                    throw new IllegalArgumentException("resumable transfer SHA-256 mismatch");
                }
                String objectKey = null;
                try {
                    try (var objectInput = Files.newInputStream(partial, LinkOption.NOFOLLOW_LINKS, StandardOpenOption.READ)) {
                        objectKey = store.put(row.artifactId(), actualSha, objectInput, total);
                    }
                    tasks.assertCurrentAttempt(machineId, row.taskId(), attempt);
                    completeOutbound(row, safeMime, safeName, total, actualSha, objectKey);
                    memory.put(transferId, new MemoryTransfer(new TransferDescriptor(transferId, row.artifactId(), row.direction(), row.taskId(),
                            row.principalId(), row.machineId(), safeName, safeMime, total, actualSha, "delivered", null,
                            publicUrl(row.artifactId(), new TaskOrigin(row.principalId(), TaskOrigin.CONFIGURED_TOKEN, ""), taskSession(row.taskId()), "download"), total),
                            objectKey, "", row.sourcePath(), memoryExpiry(transferId)));
                Files.deleteIfExists(partial);
                forgetPartialSpool(transferId);
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
        } finally {
            releaseTransferLock(transferId, guard);
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
                        current.descriptor().downloadUrl(),
                        "delivered".equals(status) ? bytes : current.descriptor().bytesTransferred());
                memory.put(transferId, new MemoryTransfer(descriptor, current.objectKey(), current.destinationPath(), current.sourcePath(), current.expiresAt()));
                if ("failed".equals(status) || "canceled".equals(status)) cleanupUncommittedStorage(transferId);
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
        if ("failed".equals(updated.status()) || "canceled".equals(updated.status())) {
            cleanupUncommittedStorage(transferId);
        }
        return new FileTransferResponse(updated.transferId(), updated.artifactId(), updated.status(),
                updated.bytes(), updated.sha256(), error);
    }

    /**
     * Cancel a queued transfer together with its task.  A delivering stream is
     * left to its Agent ACK so an in-flight upload cannot be raced into a
     * half-canceled commit; its terminal ACK still performs spool cleanup.
     */
    public void cancelForTask(String taskId) {
        if (taskId == null || taskId.isBlank()) return;
        var requested = taskId.trim();
        if (jdbc != null) {
            var ids = jdbc.query("""
                    SELECT transfer_id FROM rcm_file_transfer
                     WHERE task_id = ? AND status IN ('pending', 'ready')
                    """, ps -> ps.setString(1, requested),
                    (rs, rowNum) -> rs.getString("transfer_id"));
            jdbc.update("""
                    UPDATE rcm_file_transfer SET status = 'canceled', error_text = COALESCE(error_text, 'canceled by caller'),
                           updated_at = CURRENT_TIMESTAMP
                     WHERE task_id = ? AND status IN ('pending', 'ready')
                    """, requested);
            ids.forEach(this::cleanupUncommittedStorage);
            return;
        }
        memory.values().stream()
                .filter(value -> requested.equals(value.descriptor().taskId()))
                .filter(value -> Set.of("pending", "ready").contains(value.descriptor().status()))
                .map(value -> value.descriptor().transferId())
                .forEach(id -> {
                    var current = memory.get(id);
                    if (current == null || !Set.of("pending", "ready").contains(current.descriptor().status())) return;
                    var descriptor = new TransferDescriptor(current.descriptor().transferId(), current.descriptor().artifactId(),
                            current.descriptor().direction(), current.descriptor().taskId(), current.descriptor().principalId(),
                            current.descriptor().machineId(), current.descriptor().fileName(), current.descriptor().mimeType(),
                            current.descriptor().bytes(), current.descriptor().sha256(), "canceled", current.descriptor().error(),
                            current.descriptor().downloadUrl(), current.descriptor().bytesTransferred());
                    memory.put(id, new MemoryTransfer(descriptor, current.objectKey(), current.destinationPath(), current.sourcePath(), current.expiresAt()));
                    cleanupUncommittedStorage(id);
                });
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
        var initial = findTransfer(transferId).orElseThrow(() -> new IllegalArgumentException("file transfer not found"));
        if (machineId == null || !machineId.equals(initial.machineId())) {
            throw new SecurityException("file transfer does not belong to this machine");
        }
        if (!FileTransferAction.AGENT_TO_WEB.equals(initial.direction())) {
            throw new IllegalArgumentException("transfer is not an outbound file");
        }
        // Whole-body uploads are also serialized per transfer.  Without this
        // guard, a retried HTTP request can spool and store the same multi-GB
        // body twice before the final metadata fence notices the duplicate.
        var guard = acquireTransferLock(transferId);
        try {
            synchronized (guard.monitor) {
                return receiveFromAgentLocked(machineId, transferId, input, contentLength, expectedSha256,
                        fileName, mimeType, attempt);
            }
        } finally {
            releaseTransferLock(transferId, guard);
        }
    }

    private FileTransferResponse receiveFromAgentLocked(String machineId, String transferId, InputStream input,
                                                         long contentLength, String expectedSha256,
                                                         String fileName, String mimeType, Integer attempt) {
        var row = findTransfer(transferId).orElseThrow(() -> new IllegalArgumentException("file transfer not found"));
        if (machineId == null || !machineId.equals(row.machineId())) {
            throw new SecurityException("file transfer does not belong to this machine");
        }
        if (!FileTransferAction.AGENT_TO_WEB.equals(row.direction())) {
            throw new IllegalArgumentException("transfer is not an outbound file");
        }
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
            try (var objectInput = Files.newInputStream(temporary, LinkOption.NOFOLLOW_LINKS, StandardOpenOption.READ)) {
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
                    publicUrl(artifactId, new TaskOrigin(row.principalId(), TaskOrigin.CONFIGURED_TOKEN, ""), taskSession(row.taskId()), "download"), received.bytes()),
                    objectKey, "", row.sourcePath(), memoryExpiry(transferId)));
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
                : jdbc.query("SELECT t.transfer_id, t.artifact_id, t.direction, t.task_id, t.machine_id, t.principal_id, t.file_name, t.mime_type, COALESCE(a.bytes, t.bytes_transferred) AS bytes, t.bytes_transferred, a.sha256, t.status, t.error_text FROM rcm_file_transfer t LEFT JOIN rcm_artifact a ON a.artifact_id = t.artifact_id WHERE t.artifact_id = ? AND t.status IN ('ready', 'delivered')",
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
                downloadUrl(row.artifactId(), row.status(), taskSession(row.taskId()), origin), progressBytes(row)));
    }

    /**
     * Read a small, already-delivered image for a direct MCP image content
     * result.  File transfers remain object-store backed and large files never
     * enter the MCP response; this bounded fast path only restores the natural
     * Go-era behaviour for screenshots and camera-sized images.
     */
    public Optional<InlineArtifact> readInline(String artifactId, TaskOrigin origin, long maxBytes) {
        var value = readInlineContent(artifactId, origin, maxBytes);
        if (value.isEmpty() || value.get().mimeType() == null
                || !value.get().mimeType().toLowerCase(java.util.Locale.ROOT).startsWith("image/")) {
            return Optional.empty();
        }
        return value;
    }

    /**
     * Read a bounded, already-delivered artifact for an explicit inline MCP
     * request.  Unlike the image-only fast path this supports text, audio and
     * generic binary content; callers still choose the appropriate MCP
     * Content subtype and never receive an unbounded object-store read.
     */
    public Optional<InlineArtifact> readInlineContent(String artifactId, TaskOrigin origin, long maxBytes) {
        if (artifactId == null || artifactId.isBlank() || origin == null || maxBytes < 1) return Optional.empty();
        var descriptor = findByArtifact(artifactId, origin).orElse(null);
        if (descriptor == null || descriptor.bytes() < 0 || descriptor.bytes() > maxBytes) {
            return Optional.empty();
        }
        var objectKey = jdbc == null
                ? memory.values().stream()
                .filter(value -> artifactId.trim().equals(value.descriptor().artifactId())
                        && origin.principalId().equals(value.descriptor().principalId())
                        && Set.of("ready", "delivered").contains(value.descriptor().status()))
                .map(MemoryTransfer::objectKey).filter(value -> value != null && !value.isBlank()).findFirst().orElse("")
                : jdbc.query("SELECT a.object_key FROM rcm_artifact a WHERE a.artifact_id = ? AND a.principal_id = ? AND a.status IN ('ready', 'delivered')",
                ps -> { ps.setString(1, artifactId.trim()); ps.setString(2, origin.principalId()); },
                rs -> rs.next() ? rs.getString(1) : "");
        if (objectKey == null || objectKey.isBlank()) return Optional.empty();
        var data = store.read(objectKey);
        if (data.length != descriptor.bytes() || data.length > maxBytes) {
            throw new ArtifactStore.StorageException("inline artifact metadata does not match stored bytes");
        }
        var digest = sha256(data);
        if (!digest.equalsIgnoreCase(descriptor.sha256())) {
            throw new ArtifactStore.StorageException("inline artifact SHA-256 does not match stored bytes");
        }
        return Optional.of(new InlineArtifact(descriptor.artifactId(), descriptor.fileName(), descriptor.mimeType(), digest, data));
    }

    /**
     * Bounded admin projection for artifact/transfer management.  The list
     * never opens an object or returns a path; payload access remains behind
     * the signed public URL or the authenticated task endpoint.
     */
    public List<ArtifactAdminView> listArtifacts(String principalId, String machineId, String sessionId,
                                                 int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 200) throw new IllegalArgumentException("invalid artifact page");
        var principal = normalizeFilter(principalId);
        var machine = normalizeFilter(machineId);
        var session = normalizeFilter(sessionId);
        if (jdbc == null) {
            return memory.values().stream().map(value -> adminView(value.descriptor(), value.expiresAt(), "",
                            value.retentionPolicy(), value.pinned()))
                    .filter(value -> principal.isBlank() || principal.equals(value.principalId()))
                    .filter(value -> machine.isBlank() || machine.equals(value.machineId()))
                    .filter(value -> session.isBlank() || session.equals(value.sessionId()))
                    .sorted(java.util.Comparator.comparing(ArtifactAdminView::createdAt,
                            java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                    .skip(offset).limit(limit).toList();
        }
        var filters = new StringBuilder(" WHERE 1=1 ");
        var values = new java.util.ArrayList<String>();
        if (!principal.isBlank()) { filters.append(" AND a.principal_id = ?"); values.add(principal); }
        if (!machine.isBlank()) { filters.append(" AND a.machine_id = ?"); values.add(machine); }
        if (!session.isBlank()) {
            filters.append(" AND COALESCE(t.task_id, a.task_id, '') IN (SELECT task_id FROM rcm_task WHERE execution_session_id = ?)");
            values.add(session);
        }
        var sql = """
                SELECT a.artifact_id, a.transfer_id, a.task_id, a.principal_id, a.machine_id,
                       a.file_name, a.mime_type, a.bytes, a.sha256, a.status,
                       a.created_at, a.expires_at, a.retention_policy, a.pinned,
                       COALESCE(t.status, a.status) AS transfer_status,
                       COALESCE(t.bytes_transferred, a.bytes) AS bytes_transferred
                  FROM rcm_artifact a
                  LEFT JOIN rcm_file_transfer t ON t.transfer_id = a.transfer_id
                """ + filters + " ORDER BY a.created_at DESC, a.artifact_id DESC OFFSET ? LIMIT ?";
        return jdbc.query(sql, ps -> {
            var index = 1;
            for (var value : values) ps.setString(index++, value);
            ps.setInt(index++, offset);
            ps.setInt(index, limit);
        }, (rs, row) -> new ArtifactAdminView(rs.getString("artifact_id"), rs.getString("transfer_id"),
                rs.getString("task_id"), rs.getString("principal_id"), rs.getString("machine_id"),
                rs.getString("file_name"), rs.getString("mime_type"), rs.getLong("bytes"),
                rs.getLong("bytes_transferred"), rs.getString("status"), rs.getString("transfer_status"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("expires_at").toInstant(),
                rs.getString("sha256"), sessionForTask(rs.getString("task_id")),
                rs.getString("retention_policy"), rs.getBoolean("pinned")));
    }

    public int countArtifacts(String principalId, String machineId, String sessionId) {
        var principal = normalizeFilter(principalId);
        var machine = normalizeFilter(machineId);
        var session = normalizeFilter(sessionId);
        if (jdbc == null) {
            return Math.toIntExact(memory.values().stream().map(value -> value.descriptor())
                    .filter(value -> principal.isBlank() || principal.equals(value.principalId()))
                    .filter(value -> machine.isBlank() || machine.equals(value.machineId()))
                    .filter(value -> session.isBlank() || session.equals(sessionForTask(value.taskId()))).count());
        }
        var sql = """
                SELECT COUNT(*) FROM rcm_artifact a
                 LEFT JOIN rcm_file_transfer t ON t.transfer_id = a.transfer_id
                 WHERE (? = '' OR a.principal_id = ?)
                   AND (? = '' OR a.machine_id = ?)
                   AND (? = '' OR COALESCE(t.task_id, a.task_id, '') IN
                       (SELECT task_id FROM rcm_task WHERE execution_session_id = ?))
                """;
        var count = jdbc.queryForObject(sql, Long.class, principal, principal, machine, machine, session, session);
        return count == null ? 0 : Math.toIntExact(count);
    }

    /**
     * Remove expired first-class file artifacts in a bounded, retryable pass.
     *
     * <p>The database row is locked while the object is deleted.  This keeps a
     * concurrent retention extension or pin operation from deleting a payload
     * whose metadata has just been changed.  If the object backend reports an
     * error, the row is deliberately kept so the next maintenance run can
     * retry it.  There is no timer in the Center; an external CronJob or
     * operator invokes the bounded admin endpoint.</p>
     */
    public ArtifactGcResult gcExpiredArtifacts(int limit) {
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("limit must be between 1 and 500");
        }
        if (jdbc == null) {
            var candidates = new ArrayList<MemoryTransfer>();
            var now = Instant.now();
            for (var value : memory.values()) {
                var descriptor = value.descriptor();
                if (candidates.size() >= limit || value.pinned()
                        || value.expiresAt() == null || now.isBefore(value.expiresAt())
                        || !Set.of("ready", "delivered").contains(descriptor.status())) continue;
                candidates.add(value);
            }
            var objectFiles = 0;
            var failures = 0;
            long bytes = 0L;
            for (var value : candidates) {
                try {
                    if (value.objectKey() != null && !value.objectKey().isBlank()) {
                        store.delete(value.objectKey());
                        objectFiles++;
                        bytes = saturatingAdd(bytes, Math.max(0L, value.descriptor().bytes()));
                    }
                    memory.remove(value.descriptor().transferId(), value);
                } catch (RuntimeException failure) {
                    failures++;
                }
            }
            return new ArtifactGcResult(candidates.size() - failures, objectFiles, failures, bytes, 0);
        }

        var result = transactions == null
                ? gcExpiredArtifactsWithoutTransaction(limit)
                : transactions.execute(status -> gcExpiredArtifactsInTransaction(limit));
        if (result == null) return new ArtifactGcResult(0, 0, 0, 0L, 0);

        // Filesystem stores can enumerate only their own opaque namespace.  A
        // one-hour grace period protects put-before-metadata-commit races;
        // remote gateways intentionally return zero because they own listing
        // and lifecycle policy themselves.
        var referenced = jdbc.query("""
                SELECT object_key FROM rcm_artifact
                 WHERE storage_backend = ? AND object_key IS NOT NULL
                UNION
                SELECT object_key FROM rcm_task_artifact
                 WHERE storage_backend = ? AND object_key IS NOT NULL
                """, ps -> {
            ps.setString(1, store.backend());
            ps.setString(2, store.backend());
        }, (rs, rowNum) -> rs.getString("object_key"));
        var orphanFiles = 0;
        var orphanFailures = 0;
        try {
            orphanFiles = store.sweepOrphans(Set.copyOf(referenced), Instant.now().minus(Duration.ofHours(1)), limit);
        } catch (RuntimeException failure) {
            orphanFailures = 1;
        }
        return new ArtifactGcResult(result.metadataRows(), result.objectFiles() + orphanFiles,
                result.deleteFailures() + orphanFailures, result.objectBytes(), orphanFiles);
    }

    private ArtifactGcResult gcExpiredArtifactsWithoutTransaction(int limit) {
        // Production PostgreSQL always wires a TransactionTemplate.  This
        // fallback keeps focused adapter tests deterministic without silently
        // deleting metadata before the object backend confirms deletion.
        var candidates = jdbc.query("""
                SELECT artifact_id, task_id, object_key, bytes
                  FROM rcm_artifact
                 WHERE pinned = FALSE
                   AND expires_at IS NOT NULL AND expires_at <= CURRENT_TIMESTAMP
                   AND status IN ('ready', 'delivered')
                 ORDER BY expires_at, artifact_id
                 LIMIT ?
                """, ps -> ps.setInt(1, limit), this::artifactGcCandidate);
        return deleteExpiredArtifactCandidates(candidates);
    }

    private ArtifactGcResult gcExpiredArtifactsInTransaction(int limit) {
        var candidates = jdbc.query("""
                SELECT artifact_id, task_id, object_key, bytes
                  FROM rcm_artifact
                 WHERE pinned = FALSE
                   AND expires_at IS NOT NULL AND expires_at <= CURRENT_TIMESTAMP
                   AND status IN ('ready', 'delivered')
                 ORDER BY expires_at, artifact_id
                 LIMIT ?
                 FOR UPDATE SKIP LOCKED
                """, ps -> ps.setInt(1, limit), this::artifactGcCandidate);
        return deleteExpiredArtifactCandidates(candidates);
    }

    private ArtifactGcResult deleteExpiredArtifactCandidates(List<ArtifactGcCandidate> candidates) {
        var metadataRows = 0;
        var objectFiles = 0;
        var failures = 0;
        long bytes = 0L;
        for (var candidate : candidates) {
            try {
                if (candidate.objectKey() != null && !candidate.objectKey().isBlank()) {
                    store.delete(candidate.objectKey());
                    objectFiles++;
                    bytes = saturatingAdd(bytes, Math.max(0L, candidate.bytes()));
                }
                var removed = jdbc.update("""
                        DELETE FROM rcm_artifact
                         WHERE artifact_id = ? AND pinned = FALSE
                           AND expires_at IS NOT NULL AND expires_at <= CURRENT_TIMESTAMP
                        """, candidate.artifactId());
                if (removed > 0) {
                    metadataRows++;
                    if (candidate.taskId() != null && !candidate.taskId().isBlank()) {
                        // Do not leave the compact task projection advertising
                        // a file after its last first-class artifact vanished.
                        // An older task projection is preserved when another
                        // artifact still exists for the same task.
                        jdbc.update("""
                                UPDATE rcm_task
                                   SET artifact_bytes = 0, artifact_mime = NULL,
                                       artifact_sha256 = NULL, updated_at = CURRENT_TIMESTAMP
                                 WHERE task_id = ?
                                   AND NOT EXISTS (SELECT 1 FROM rcm_artifact WHERE task_id = ?)
                                   AND NOT EXISTS (SELECT 1 FROM rcm_task_artifact WHERE task_id = ?)
                                """, candidate.taskId(), candidate.taskId(), candidate.taskId());
                    }
                }
            } catch (RuntimeException failure) {
                // Keep the metadata row when the object backend failed.  The
                // next bounded run can retry it and the admin result exposes
                // the failure count without placing the Center on a retry loop.
                failures++;
            }
        }
        return new ArtifactGcResult(metadataRows, objectFiles, failures, bytes, 0);
    }

    private ArtifactGcCandidate artifactGcCandidate(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new ArtifactGcCandidate(rs.getString("artifact_id"), rs.getString("task_id"),
                rs.getString("object_key"), rs.getLong("bytes"));
    }

    /** Delete metadata and the owned object; repeated deletion is idempotent. */
    public boolean deleteArtifact(String artifactId, String principalId) {
        if (artifactId == null || artifactId.isBlank()) return false;
        var id = artifactId.trim();
        var principal = normalizeFilter(principalId);
        if (jdbc == null) {
            var removed = false;
            for (var entry : memory.entrySet()) {
                var value = entry.getValue();
                if (!id.equals(value.descriptor().artifactId())
                        || (!principal.isBlank() && !principal.equals(value.descriptor().principalId()))) continue;
                if (value.objectKey() != null && !value.objectKey().isBlank()) {
                    try { store.delete(value.objectKey()); } catch (RuntimeException ignored) { }
                }
                removed |= memory.remove(entry.getKey(), value);
            }
            return removed;
        }
        var object = jdbc.query("SELECT object_key, principal_id FROM rcm_artifact WHERE artifact_id = ?",
                ps -> ps.setString(1, id), rs -> rs.next() ? new StoredArtifact(rs.getString(1), rs.getString(2)) : null);
        if (object == null || (!principal.isBlank() && !principal.equals(object.principalId()))) return false;
        Integer deleted;
        if (transactions == null) {
            deleted = jdbc.update("DELETE FROM rcm_artifact WHERE artifact_id = ?", id);
        } else {
            deleted = transactions.execute(status -> jdbc.update("DELETE FROM rcm_artifact WHERE artifact_id = ?", id));
        }
        if (deleted != null && deleted > 0 && object.objectKey() != null && !object.objectKey().isBlank()) {
            try { store.delete(object.objectKey()); } catch (RuntimeException ignored) { }
        }
        return deleted != null && deleted > 0;
    }

    /** Extend retention without ever shortening an existing retention period. */
    public boolean extendArtifactRetention(String artifactId, String principalId, Duration extension) {
        return extendArtifactRetention(artifactId, principalId, extension, "task-bound", false);
    }

    /** Apply a bounded lifecycle policy and optionally pin an artifact. */
    public boolean extendArtifactRetention(String artifactId, String principalId, Duration extension,
                                           String policy, boolean pinned) {
        if (artifactId == null || artifactId.isBlank() || extension == null
                || extension.isNegative() || extension.isZero() || extension.compareTo(Duration.ofDays(365)) > 0) {
            throw new IllegalArgumentException("retention extension must be between 1 second and 365 days");
        }
        var normalizedPolicy = policy == null || policy.isBlank() ? "task-bound" : policy.trim().toLowerCase(java.util.Locale.ROOT);
        if (!("ephemeral".equals(normalizedPolicy) || "task-bound".equals(normalizedPolicy) || "pinned".equals(normalizedPolicy))) {
            throw new IllegalArgumentException("retention policy must be ephemeral, task-bound, or pinned");
        }
        pinned = pinned || "pinned".equals(normalizedPolicy);
        if (jdbc == null) {
            var id = artifactId.trim();
            var principal = normalizeFilter(principalId);
            var changed = false;
            var nextExpiry = Instant.now().plus(extension);
            for (var entry : memory.entrySet()) {
                var value = entry.getValue();
                if (!id.equals(value.descriptor().artifactId())
                        || (!principal.isBlank() && !principal.equals(value.descriptor().principalId()))) continue;
                var expires = value.expiresAt() == null || value.expiresAt().isBefore(nextExpiry)
                        ? nextExpiry : value.expiresAt();
                memory.put(entry.getKey(), new MemoryTransfer(value.descriptor(), value.objectKey(),
                        value.destinationPath(), value.sourcePath(), expires, normalizedPolicy, pinned));
                changed = true;
            }
            return changed;
        }
        var principal = normalizeFilter(principalId);
        var changed = jdbc.update("""
                UPDATE rcm_artifact
                   SET expires_at = GREATEST(expires_at, CURRENT_TIMESTAMP + (? * INTERVAL '1 second')),
                       retention_policy = ?, pinned = ?
                 WHERE artifact_id = ? AND (? = '' OR principal_id = ?)
                """, extension.toSeconds(), normalizedPolicy, pinned, artifactId.trim(), principal, principal);
        return changed > 0;
    }

    private static String normalizeFilter(String value) {
        return value == null ? "" : value.trim();
    }

    private Duration artifactRetentionFor(String direction, String mimeType, long bytes) {
        if (bytes >= 1024L * 1024 * 1024) return largeArtifactRetention;
        var mime = mimeType == null ? "" : mimeType.toLowerCase(java.util.Locale.ROOT);
        if (mime.startsWith("text/") || mime.contains("json") || mime.contains("xml") || mime.contains("log")) {
            return textArtifactRetention;
        }
        return FileTransferAction.WEB_TO_AGENT.equals(direction) ? webArtifactRetention : artifactRetention;
    }

    private ArtifactAdminView adminView(TransferDescriptor value, Instant expiresAt, String transferStatus) {
        return adminView(value, expiresAt, transferStatus, "task-bound", false);
    }

    private ArtifactAdminView adminView(TransferDescriptor value, Instant expiresAt, String transferStatus,
                                        String retentionPolicy, boolean pinned) {
        return new ArtifactAdminView(value.artifactId(), value.transferId(), value.taskId(), value.principalId(),
                value.machineId(), value.fileName(), value.mimeType(), value.bytes(), value.bytesTransferred(),
                "ready".equals(value.status()) || "delivered".equals(value.status()) ? value.status() : value.status(),
                transferStatus == null || transferStatus.isBlank() ? value.status() : transferStatus,
                null, expiresAt, value.sha256(), sessionForTask(value.taskId()), retentionPolicy, pinned);
    }

    private long progressBytes(TransferRow row) {
        return Math.max(0L, Math.min(MAX_BYTES, row.bytesTransferred()));
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
                bytes = saturatingAdd(bytes, Math.max(0L, descriptor.bytesTransferred()));
                var declared = descriptor.bytes() > 0 ? descriptor.bytes() : descriptor.bytesTransferred();
                expected = saturatingAdd(expected, Math.max(0L, declared));
            }
            return new TransferMetrics(active, delivered, failed, canceled, bytes, expected, 0.0, 0.0,
                    resumeCount.get(), partialSpoolBytes.get(), 0L);
        }
        return jdbc.query("""
                SELECT COUNT(*) FILTER (WHERE status IN ('pending', 'ready', 'delivering')),
                       COUNT(*) FILTER (WHERE status = 'delivered'),
                       COUNT(*) FILTER (WHERE status = 'failed'),
                       COUNT(*) FILTER (WHERE status = 'canceled'),
                       COALESCE(SUM(bytes_transferred), 0),
                       COALESCE(SUM(CASE WHEN expected_bytes = 0 THEN bytes_transferred ELSE expected_bytes END), 0),
                       COALESCE(AVG(EXTRACT(EPOCH FROM (updated_at - created_at)))
                                FILTER (WHERE status IN ('delivered', 'failed', 'canceled')), 0),
                       COALESCE(MAX(EXTRACT(EPOCH FROM (updated_at - created_at)))
                                FILTER (WHERE status IN ('delivered', 'failed', 'canceled')), 0)
                  FROM rcm_file_transfer
                """, rs -> rs.next() ? new TransferMetrics(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                rs.getLong(5), rs.getLong(6), rs.getDouble(7), rs.getDouble(8),
                resumeCount.get(), partialSpoolBytes.get(), 0L)
                : new TransferMetrics(0, 0, 0, 0, 0, 0, 0.0, 0.0,
                resumeCount.get(), partialSpoolBytes.get(), 0L));
    }

    private TransferDescriptor withDownloadUrl(TransferDescriptor value, TaskOrigin origin) {
        return new TransferDescriptor(value.transferId(), value.artifactId(), value.direction(), value.taskId(), value.principalId(),
                value.machineId(), value.fileName(), value.mimeType(), value.bytes(), value.sha256(), value.status(), value.error(),
                downloadUrl(value.artifactId(), value.status(), taskSession(value.taskId()), origin), value.bytesTransferred());
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

    /** Expose only the opaque session correlation needed to sign a preview URL. */
    String sessionForTask(String taskId) {
        return taskSession(taskId);
    }

    /** Public file object endpoint URL; the URL carries a short-lived HMAC. */
    public String publicUrl(String artifactId, TaskOrigin origin) {
        return publicUrl(artifactId, origin, "", "download");
    }

    /** Configured HTTPS origin used by MCP Apps CSP metadata and diagnostics. */
    String publicBaseUrl() {
        return publicBaseUrl;
    }

    /**
     * Fail readiness before a durable Center advertises unusable file URLs.
     * Development memory mode intentionally permits an empty public origin so
     * protocol tests do not need an externally reachable host.
     */
    String readinessFailure(boolean durableDeployment) {
        if (!durableDeployment) return null;
        if (publicBaseUrl.isBlank()) return "RCM_CENTER_PUBLIC_BASE_URL is required for durable artifact delivery";
        try {
            var uri = URI.create(publicBaseUrl);
            var host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || host.isBlank()
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || host.endsWith(".example.invalid") || "example.invalid".equalsIgnoreCase(host)) {
                return "RCM_CENTER_PUBLIC_BASE_URL must be a real HTTPS origin without query or fragment";
            }
        } catch (IllegalArgumentException exception) {
            return "RCM_CENTER_PUBLIC_BASE_URL is invalid";
        }
        if (!tokens.artifactSigningConfigured()) {
            return "REMOTE_CONNECT_MCP_CENTER_ARTIFACT_SIGNING_SECRET is required for durable artifact delivery";
        }
        if (tokens.artifactSigningKeys().stream().anyMatch(key -> key.secret().length() < 32)) {
            return "artifact signing secrets must be at least 32 characters";
        }
        return null;
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
        var token = accessToken(artifactId, origin.principalId(), expires, safePurpose, connection, session);
        var base = publicBaseUrl.isBlank() ? "" : publicBaseUrl;
        return base + "/artifacts/" + artifactId + "/content?token=" + encode(token);
    }

    /** Validate a new opaque access token without exposing principal/session in URLs. */
    public PublicArtifact openPublic(String token) {
        var access = decodeAccessToken(token);
        return openPublic(access.artifactId(), access.expires(), access.principal(), access.connection(),
                access.session(), access.purpose(), access.signature());
    }

    /**
     * Open a signed artifact URL with one bounded event-driven wait when the
     * Agent upload is still in flight.  The HTTP request waits on the durable
     * task change signal; it does not poll the database or expose an
     * unauthenticated readiness endpoint.
     */
    public PublicArtifact openPublic(String token, long waitMs) throws InterruptedException {
        var access = decodeAccessToken(token);
        try {
            return openPublic(access.artifactId(), access.expires(), access.principal(), access.connection(),
                    access.session(), access.purpose(), access.signature());
        } catch (IllegalArgumentException notReady) {
            var boundedWait = Math.max(0L, Math.min(30000L, waitMs));
            if (boundedWait == 0L) throw notReady;
            var taskId = taskForArtifact(access.artifactId(), access.principal());
            if (taskId.isBlank()) throw notReady;
            var origin = new TaskOrigin(access.principal(), TaskOrigin.CONFIGURED_TOKEN, access.connection());
            tasks.waitForTerminal(origin, taskId, Duration.ofMillis(boundedWait));
            return openPublic(access.artifactId(), access.expires(), access.principal(), access.connection(),
                    access.session(), access.purpose(), access.signature());
        }
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
        var valid = verifySignature(signatureSubject(artifactId, principal, expires, purpose, connection, session), supplied);
        if (!valid) {
            throw new SecurityException("invalid or expired artifact URL");
        }
        var now = Instant.now();
        PublicArtifact row = jdbc == null ? memory.values().stream()
                .filter(value -> artifactId.equals(value.descriptor().artifactId())
                        && principal.equals(value.descriptor().principalId())
                        && (value.pinned() || value.expiresAt() == null || now.isBefore(value.expiresAt())))
                .map(value -> value.descriptor())
                .filter(value -> "ready".equals(value.status()) || "delivered".equals(value.status()))
                .map(value -> new PublicArtifact(value.artifactId(), value.fileName(), value.mimeType(), value.bytes(), value.sha256(), value.status(), null))
                .findFirst().orElse(null)
                : jdbc.query("SELECT artifact_id, file_name, mime_type, bytes, sha256, status FROM rcm_artifact WHERE artifact_id = ? AND principal_id = ? AND status IN ('ready', 'delivered')",
                ps -> { ps.setString(1, artifactId); ps.setString(2, principal); }, rs -> rs.next()
                        ? new PublicArtifact(artifactId, rs.getString("file_name"), rs.getString("mime_type"), rs.getLong("bytes"), rs.getString("sha256"), rs.getString("status"), null)
                        : null);
        if (row == null) throw new IllegalArgumentException("artifact not found");
        if (jdbc != null) {
            var retainedUntil = jdbc.query("SELECT expires_at, pinned FROM rcm_artifact WHERE artifact_id = ? AND principal_id = ?",
                    ps -> { ps.setString(1, artifactId); ps.setString(2, principal); },
                    rs -> rs.next() ? new Retention(rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant(), rs.getBoolean(2)) : null);
            if (retainedUntil != null && !retainedUntil.pinned() && retainedUntil.expiresAt() != null
                    && !Instant.now().isBefore(retainedUntil.expiresAt())) {
                throw new IllegalArgumentException("artifact retention has expired");
            }
        }
        var objectKey = jdbc == null ? memory.values().stream().filter(value -> artifactId.equals(value.descriptor().artifactId())).map(MemoryTransfer::objectKey).findFirst().orElse("")
                : jdbc.queryForObject("SELECT object_key FROM rcm_artifact WHERE artifact_id = ?", String.class, artifactId);
        if (objectKey == null || objectKey.isBlank()) throw new IllegalArgumentException("artifact is not ready");
        return new PublicArtifact(row.artifactId(), row.fileName(), row.mimeType(), row.bytes(), row.sha256(), row.status(), store.open(objectKey));
    }

    private String taskForArtifact(String artifactId, String principal) {
        if (artifactId == null || artifactId.isBlank() || principal == null || principal.isBlank()) return "";
        if (jdbc == null) {
            return memory.values().stream()
                    .filter(value -> artifactId.equals(value.descriptor().artifactId())
                            && principal.equals(value.descriptor().principalId()))
                    .map(value -> value.descriptor().taskId())
                    .filter(value -> value != null && !value.isBlank())
                    .findFirst().orElse("");
        }
        return jdbc.query("SELECT task_id FROM rcm_file_transfer WHERE artifact_id = ? AND principal_id = ?",
                ps -> { ps.setString(1, artifactId); ps.setString(2, principal); },
                rs -> rs.next() ? rs.getString(1) : "");
    }

    /**
     * Open the latest streamed artifact for the authenticated Console task
     * endpoint.  Task artifacts and file-transfer artifacts intentionally use
     * different tables, so the Console must have one bounded fallback instead
     * of making Agent-to-Web files invisible after delivery.  Admin auth is
     * performed by the controller; this method only enforces retention/status
     * and never accepts a caller-provided object key.
     */
    public Optional<PublicArtifact> openForAdminTask(String taskId) {
        if (taskId == null || taskId.isBlank()) return Optional.empty();
        var requested = taskId.trim();
        if (jdbc == null) {
            var now = Instant.now();
            return memory.values().stream()
                    .filter(value -> requested.equals(value.descriptor().taskId()))
                    .filter(value -> value.pinned() || value.expiresAt() == null || now.isBefore(value.expiresAt()))
                    .filter(value -> Set.of("ready", "delivered").contains(value.descriptor().status()))
                    .filter(value -> value.objectKey() != null && !value.objectKey().isBlank())
                    .sorted(java.util.Comparator.comparing(MemoryTransfer::expiresAt,
                            java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                    .findFirst()
                    .map(value -> new PublicArtifact(value.descriptor().artifactId(), value.descriptor().fileName(),
                            value.descriptor().mimeType(), value.descriptor().bytes(), value.descriptor().sha256(),
                            value.descriptor().status(), store.open(value.objectKey())));
        }
        var row = jdbc.query("""
                SELECT artifact_id, file_name, mime_type, bytes, sha256, status, object_key
                  FROM rcm_artifact
                   WHERE task_id = ? AND status IN ('ready', 'delivered')
                    AND (pinned OR expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
                 ORDER BY created_at DESC, artifact_id DESC
                 LIMIT 1
                """, ps -> ps.setString(1, requested), rs -> rs.next()
                ? new AdminArtifactRow(rs.getString("artifact_id"), rs.getString("file_name"),
                        rs.getString("mime_type"), rs.getLong("bytes"), rs.getString("sha256"),
                        rs.getString("status"), rs.getString("object_key")) : null);
        if (row == null || row.objectKey() == null || row.objectKey().isBlank()) return Optional.empty();
        return Optional.of(new PublicArtifact(row.artifactId(), row.fileName(), row.mimeType(), row.bytes(),
                row.sha256(), row.status(), store.open(row.objectKey())));
    }

    private CreateTaskRequest withAction(CreateTaskRequest request, FileTransferAction action) {
        var source = request.command();
        var command = new TaskCommand("", TaskKind.FILE_TRANSFER, "file_transfer", null,
                source.cwd(), Map.of(), 0, null, Instant.now(), null, 0, action);
        return new CreateTaskRequest(request.machineId(), command, request.idempotencyKey(), request.projectId(),
                request.worktreeId(), request.scopeMode(), request.scopeRoot(), request.workspacePolicy(),
                request.laneMode(), request.sessionId(), request.risk(), request.elevationRequired(), request.origin());
    }

    private boolean persistInboundReservation(TaskOrigin origin, TransferDescriptor descriptor, String destinationPath,
                                              long expectedBytes, String expectedSha256) {
        if (jdbc == null) return true;
        java.util.function.Supplier<Boolean> write = () -> jdbc.update("""
                INSERT INTO rcm_file_transfer(transfer_id, artifact_id, task_id, principal_id, machine_id, direction, source_path, destination_path, file_name, mime_type, expected_bytes, expected_sha256, bytes_transferred, status, error_text, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, NULLIF(?, ''), 0, 'pending', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (transfer_id) DO NOTHING
                """, descriptor.transferId(), descriptor.artifactId(), descriptor.taskId(), origin.principalId(), descriptor.machineId(),
                descriptor.direction(), destinationPath, descriptor.fileName(), descriptor.mimeType(), expectedBytes, expectedSha256) > 0;
        java.util.function.Supplier<Boolean> guarded = () -> {
            if (quota != null) quota.assertTransferAdmissionInTransaction(origin, descriptor.transferId(),
                    expectedBytes > 0 ? expectedBytes : MAX_BYTES);
            return write.get();
        };
        return transactions == null ? guarded.get() : Boolean.TRUE.equals(transactions.execute(status -> guarded.get()));
    }

    private void completeInbound(TaskOrigin origin, String machineId, String taskId, Ids ids, String fileName, String mimeType,
                                 Downloaded downloaded, String objectKey) {
        if (jdbc == null) return;
        Runnable write = () -> {
            // The Web ingest runs asynchronously after the MCP request has
            // returned.  A caller may cancel the task while the source URL is
            // still being downloaded; only a pending reservation may advance
            // to ready, never a canceled/failed one.
            var changed = jdbc.update("""
                    UPDATE rcm_file_transfer
                       SET artifact_id = ?, expected_bytes = ?, expected_sha256 = ?, bytes_transferred = 0,
                           status = 'ready', error_text = NULL, updated_at = CURRENT_TIMESTAMP
                     WHERE transfer_id = ? AND status = 'pending'
                    """, ids.artifactId(), downloaded.bytes(), downloaded.sha256(), ids.transferId());
            if (changed == 0) {
                var status = jdbc.query("SELECT status FROM rcm_file_transfer WHERE transfer_id = ?",
                        ps -> ps.setString(1, ids.transferId()), rs -> rs.next() ? rs.getString(1) : null);
                if ("ready".equalsIgnoreCase(status) || "delivered".equalsIgnoreCase(status)) return;
                throw new IllegalStateException("file transfer no longer accepts preparation");
            }
            jdbc.update("""
                    INSERT INTO rcm_artifact(artifact_id, principal_id, machine_id, task_id, transfer_id, file_name, mime_type, storage_backend, object_key, bytes, sha256, status, created_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ready', CURRENT_TIMESTAMP, ?)
                    ON CONFLICT (artifact_id) DO UPDATE SET object_key = EXCLUDED.object_key, bytes = EXCLUDED.bytes, sha256 = EXCLUDED.sha256, mime_type = EXCLUDED.mime_type, file_name = EXCLUDED.file_name, status = 'ready', expires_at = EXCLUDED.expires_at
                    """, ids.artifactId(), origin.principalId(), machineId, taskId, ids.transferId(), fileName, mimeType,
                    store.backend(), objectKey, downloaded.bytes(), downloaded.sha256(), java.sql.Timestamp.from(Instant.now().plus(artifactRetentionFor(FileTransferAction.WEB_TO_AGENT, mimeType, downloaded.bytes()))));
        };
        if (transactions == null) write.run(); else transactions.execute(status -> { write.run(); return null; });
    }

    private void markFailedByTransfer(String transferId, String error) {
        var message = error == null || error.isBlank() ? "file transfer preparation failed" : error;
        var safe = SensitiveValueRedactor.redact(message.substring(0, Math.min(message.length(), 4096)));
        if (jdbc != null) {
            var current = findTransfer(transferId).orElse(null);
            if (current != null && Set.of("delivered", "canceled").contains(current.status())) return;
            var changed = jdbc.update("UPDATE rcm_file_transfer SET status = 'failed', error_text = ?, updated_at = CURRENT_TIMESTAMP WHERE transfer_id = ? AND status NOT IN ('delivered', 'canceled')",
                    safe, transferId);
            if (changed > 0) cleanupUncommittedStorage(transferId);
            return;
        }
        var current = memory.get(transferId);
        if (current == null) return;
        if (Set.of("delivered", "canceled").contains(current.descriptor().status())) return;
        var descriptor = new TransferDescriptor(current.descriptor().transferId(), current.descriptor().artifactId(), current.descriptor().direction(),
                current.descriptor().taskId(), current.descriptor().principalId(), current.descriptor().machineId(), current.descriptor().fileName(),
                current.descriptor().mimeType(), current.descriptor().bytes(), current.descriptor().sha256(), "failed", safe,
                current.descriptor().downloadUrl(), current.descriptor().bytesTransferred());
        memory.put(transferId, new MemoryTransfer(descriptor, current.objectKey(), current.destinationPath(), current.sourcePath(), current.expiresAt()));
        cleanupUncommittedStorage(transferId);
    }

    /** Remove only storage which is not owned by a ready/delivered artifact. */
    private void cleanupUncommittedStorage(String transferId) {
        if (transferId == null || transferId.isBlank()) return;
        var guard = acquireTransferLock(transferId);
        try {
            synchronized (guard.monitor) {
                deleteResumeSpool(transferId);
                if (jdbc != null) {
                    var object = jdbc.query("""
                            SELECT a.object_key, a.status
                              FROM rcm_file_transfer t
                              LEFT JOIN rcm_artifact a ON a.artifact_id = t.artifact_id
                             WHERE t.transfer_id = ?
                            """, ps -> ps.setString(1, transferId), rs -> rs.next()
                            ? new StoredObject(rs.getString("object_key"), rs.getString("status")) : null);
                    if (object != null && object.objectKey() != null && !object.objectKey().isBlank()
                            && !Set.of("ready", "delivered").contains(object.status())) {
                        try { store.delete(object.objectKey()); } catch (RuntimeException ignored) { }
                    }
                } else {
                    var current = memory.get(transferId);
                    if (current != null && current.objectKey() != null && !current.objectKey().isBlank()
                            && !Set.of("delivered", "canceled").contains(current.descriptor().status())) {
                        try { store.delete(current.objectKey()); } catch (RuntimeException ignored) { }
                    }
                }
            }
        } finally {
            releaseTransferLock(transferId, guard);
        }
    }

    /** Best-effort deletion of a durable resumable prefix; never creates a directory. */
    private void deleteResumeSpool(String transferId) {
        if (transferId == null || transferId.isBlank()) return;
        var resumeRoot = transferSpoolRoot.resolve("resume").normalize();
        var safe = transferId.replaceAll("[^A-Za-z0-9._-]", "_");
        var path = resumeRoot.resolve(safe + ".part").normalize();
        if (!path.startsWith(resumeRoot)) return;
        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
        forgetPartialSpool(transferId);
    }

    private void persistInbound(TaskOrigin origin, TransferDescriptor descriptor, String objectKey, String destinationPath) {
        if (jdbc == null) return;
        Runnable write = () -> {
            if (quota != null) quota.assertTransferAdmissionInTransaction(origin, descriptor.transferId(),
                    Math.max(0L, descriptor.bytes()));
            jdbc.update("""
                    INSERT INTO rcm_artifact(artifact_id, principal_id, machine_id, task_id, transfer_id, file_name, mime_type, storage_backend, object_key, bytes, sha256, status, created_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?)
                    ON CONFLICT (artifact_id) DO NOTHING
                    """, descriptor.artifactId(), origin.principalId(), descriptor.machineId(), descriptor.taskId(), descriptor.transferId(),
                    descriptor.fileName(), descriptor.mimeType(), store.backend(), objectKey, descriptor.bytes(), descriptor.sha256(), "ready",
                    java.sql.Timestamp.from(Instant.now().plus(artifactRetentionFor(descriptor.direction(), descriptor.mimeType(), descriptor.bytes()))));
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
            if (quota != null) quota.assertTransferAdmissionInTransaction(origin, descriptor.transferId(), MAX_BYTES);
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
        if (jdbc == null) {
            // Memory mode is used by protocol tests and local development. It
            // has no durable transfer row to update, but the compact task
            // projection must still expose the streamed artifact exactly once.
            tasks.recordTransferArtifact(row.machineId(), row.taskId(), mime, bytes, sha);
            return;
        }
        Runnable write = () -> {
            // Fence cancellation/failure before publishing the object
            // reference.  A late Agent ACK must never make a canceled task
            // visible again.  A duplicate completion of an already delivered
            // transfer is harmless and remains idempotent.
            var changed = jdbc.update("UPDATE rcm_file_transfer SET file_name = ?, mime_type = ?, expected_bytes = ?, expected_sha256 = ?, bytes_transferred = ?, status = 'delivered', error_text = NULL, updated_at = CURRENT_TIMESTAMP WHERE transfer_id = ? AND status NOT IN ('delivered', 'failed', 'canceled')",
                    fileName, mime, bytes, sha, bytes, row.transferId());
            if (changed == 0) {
                var status = jdbc.query("SELECT status FROM rcm_file_transfer WHERE transfer_id = ?",
                        ps -> ps.setString(1, row.transferId()), rs -> rs.next() ? rs.getString(1) : null);
                if ("delivered".equalsIgnoreCase(status)) return;
                throw new IllegalStateException("file transfer no longer accepts completion");
            }
            jdbc.update("""
                    INSERT INTO rcm_artifact(artifact_id, principal_id, machine_id, task_id, transfer_id, file_name, mime_type, storage_backend, object_key, bytes, sha256, status, created_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ready', CURRENT_TIMESTAMP, ?)
                    ON CONFLICT (artifact_id) DO UPDATE SET object_key = EXCLUDED.object_key, bytes = EXCLUDED.bytes, sha256 = EXCLUDED.sha256, mime_type = EXCLUDED.mime_type, file_name = EXCLUDED.file_name, status = 'ready'
                    """, row.artifactId(), row.principalId(), row.machineId(), row.taskId(), row.transferId(), fileName, mime, store.backend(), objectKey, bytes, sha,
                    java.sql.Timestamp.from(Instant.now().plus(artifactRetentionFor(row.direction(), mime, bytes))));
            tasks.recordTransferArtifact(row.machineId(), row.taskId(), mime, bytes, sha);
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
                current.descriptor().downloadUrl(), current.descriptor().bytesTransferred());
        memory.put(row.transferId(), new MemoryTransfer(descriptor, current.objectKey(), current.destinationPath(), current.sourcePath(), current.expiresAt()));
    }

    private Optional<TransferRow> findTransfer(String transferId) {
        if (transferId == null || transferId.isBlank()) return Optional.empty();
        if (jdbc == null) {
            return Optional.ofNullable(memory.get(transferId.trim())).map(value -> new TransferRow(value.descriptor().transferId(), value.descriptor().artifactId(),
                    value.descriptor().taskId(), value.descriptor().principalId(), value.descriptor().machineId(), value.descriptor().direction(),
                    value.sourcePath(), value.destinationPath(), value.descriptor().fileName(), value.descriptor().mimeType(), value.descriptor().bytes(), value.descriptor().bytesTransferred(), value.descriptor().sha256(), value.descriptor().status(), value.descriptor().error(), value.objectKey()));
        }
        return jdbc.query("SELECT t.transfer_id, t.artifact_id, t.task_id, t.principal_id, t.machine_id, t.direction, t.source_path, t.destination_path, t.file_name, t.mime_type, t.expected_bytes, t.bytes_transferred, t.expected_sha256, t.status, t.error_text, a.object_key FROM rcm_file_transfer t LEFT JOIN rcm_artifact a ON a.artifact_id = t.artifact_id WHERE t.transfer_id = ?",
                ps -> ps.setString(1, transferId.trim()), rs -> rs.next() ? Optional.of(transfer(rs)) : Optional.empty());
    }

    private boolean hasTransfer(String transferId) {
        return findTransfer(transferId).isPresent();
    }

    private static TransferRow transfer(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TransferRow(rs.getString("transfer_id"), rs.getString("artifact_id"), rs.getString("task_id"), rs.getString("principal_id"),
                rs.getString("machine_id"), rs.getString("direction"), rs.getString("source_path"), rs.getString("destination_path"),
                rs.getString("file_name"), rs.getString("mime_type"), rs.getLong("expected_bytes"), rs.getLong("bytes_transferred"),
                rs.getString("expected_sha256"), rs.getString("status"), rs.getString("error_text"), rs.getString("object_key"));
    }

    private static TransferDescriptor descriptor(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TransferDescriptor(rs.getString("transfer_id"), rs.getString("artifact_id"), rs.getString("direction"), rs.getString("task_id"),
                rs.getString("principal_id"), rs.getString("machine_id"), rs.getString("file_name"), rs.getString("mime_type"), rs.getLong("bytes"), rs.getString("sha256"),
                rs.getString("status"), rs.getString("error_text"), "", rs.getLong("bytes_transferred"));
    }

    private Downloaded download(URI uri, Long expectedBytes, String expectedSha256, String transferId) throws IOException {
        try {
            // ChatGPT file references and object-storage download URLs may
            // legitimately return 301/302/303/307/308 before the bytes are
            // available.  The JDK client keeps redirects disabled so every
            // hop can be revalidated as HTTPS and public-address-only.  This
            // prevents a redirect from turning the ChatGPT file bridge into
            // an SSRF/DNS-rebinding primitive while still accepting bounded
            // signed-URL redirects.
            var current = uri;
            var visited = new java.util.HashSet<URI>();
            visited.add(current);
            for (var redirect = 0; ; redirect++) {
                var resolvedBefore = validateDownloadUrl(current);
                var request = HttpRequest.newBuilder(current).timeout(DOWNLOAD_TIMEOUT)
                        .header("Accept", "application/octet-stream").GET().build();
                var response = downloader.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try {
                    var responseUri = response.uri() == null ? current : response.uri();
                    var resolvedAfter = validateDownloadUrl(responseUri);
                    if (!resolvedBefore.equals(resolvedAfter)) {
                        throw new IOException("download URL DNS resolution changed during request");
                    }
                    if (isRedirect(response.statusCode())) {
                        if (redirect >= MAX_DOWNLOAD_REDIRECTS) {
                            throw new IOException("download URL exceeded the redirect limit");
                        }
                        var location = response.headers().firstValue("Location")
                                .orElseThrow(() -> new IOException("download URL redirect has no Location"));
                        final URI next;
                        try {
                            next = current.resolve(location);
                        } catch (IllegalArgumentException exception) {
                            throw new IOException("download URL redirect is invalid", exception);
                        }
                        validateDownloadUrl(next);
                        if (!visited.add(next)) throw new IOException("download URL redirect loop detected");
                        close(response.body());
                        current = next;
                        continue;
                    }
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new IOException("download URL returned HTTP " + response.statusCode());
                    }
                    var length = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
                    if (length > MAX_BYTES || (expectedBytes != null && length >= 0 && length != expectedBytes)) {
                        throw new IOException("download size is outside the declared limit");
                    }
                    var body = response.body();
                    // spool() owns and closes the final response stream.
                    return spool(body, expectedBytes == null ? length : expectedBytes, expectedSha256, transferId);
                } catch (IOException | RuntimeException failure) {
                    close(response.body());
                    throw failure;
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("download interrupted", exception);
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private Downloaded spool(InputStream input, long expectedBytes, String expectedSha256, String transferId) throws IOException {
        if (expectedBytes > MAX_BYTES) throw new IOException("file size is outside the allowed range");
        Files.createDirectories(transferSpoolRoot);
        var temporary = Files.createTempFile(transferSpoolRoot, "rcm-transfer-", ".part");
        try (input; var output = Files.newOutputStream(temporary, LinkOption.NOFOLLOW_LINKS,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
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
        } finally {
            // The ordinary stream uses a temporary file which is removed as
            // soon as the caller has copied it to the object store.  Do not
            // leave its progress in the in-process spool gauge after either a
            // successful or failed request.
            forgetPartialSpool(transferId);
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
        // A confirmed offset must survive a Center process restart (and be as
        // reliable as the PVC allows across a host crash). Force each bounded
        // chunk before acknowledging it; otherwise the file length could be
        // visible while its last bytes still exist only in the page cache and
        // a resume probe could skip data after a crash.
        try (var output = java.nio.channels.FileChannel.open(partial, LinkOption.NOFOLLOW_LINKS, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            var buffer = new byte[1024 * 1024];
            var deadlineNanos = System.nanoTime() + DOWNLOAD_TIMEOUT.toNanos();
            long count = 0;
            try {
                int read;
                while ((read = readWithWatchdog(input, buffer, deadlineNanos)) >= 0) {
                    if (read == 0) continue;
                    count += read;
                    if (count > expectedBytes) throw new IOException("resumable chunk exceeds its Content-Range length");
                    var view = ByteBuffer.wrap(buffer, 0, read);
                    while (view.hasRemaining()) output.write(view);
                }
                if (count != expectedBytes) throw new IOException("resumable chunk body is shorter than Content-Range");
            } catch (IOException failure) {
                // The partial length is used as the next resume offset even
                // after an interrupted request. Flush that prefix before the
                // connection is reported as temporary failure, otherwise a
                // crash could advertise page-cache bytes that were never
                // durable on the PVC.
                try { output.force(true); } catch (IOException ignored) { }
                throw failure;
            }
            output.force(true);
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
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS, StandardOpenOption.READ)) {
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
        var bounded = Math.max(0L, Math.min(MAX_BYTES, bytes));
        var previous = partialSpoolByTransfer.put(transferId, bounded);
        partialSpoolBytes.addAndGet(bounded - (previous == null ? 0L : previous));
        if (jdbc != null) {
            jdbc.update("""
                    UPDATE rcm_file_transfer
                       SET bytes_transferred = CASE WHEN status IN ('pending', 'ready', 'delivering') THEN ? ELSE bytes_transferred END,
                           updated_at = CURRENT_TIMESTAMP
                     WHERE transfer_id = ?
                    """, bounded, transferId);
            return;
        }
        var current = memory.get(transferId);
        if (current == null) return;
        var descriptor = current.descriptor();
        if (Set.of("delivered", "failed", "canceled").contains(descriptor.status())) return;
        var updated = new TransferDescriptor(descriptor.transferId(), descriptor.artifactId(), descriptor.direction(), descriptor.taskId(),
                descriptor.principalId(), descriptor.machineId(), descriptor.fileName(), descriptor.mimeType(), Math.max(descriptor.bytes(), bytes),
                descriptor.sha256(), descriptor.status(), descriptor.error(), descriptor.downloadUrl(),
                Math.max(descriptor.bytesTransferred(), bytes));
        memory.put(transferId, new MemoryTransfer(updated, current.objectKey(), current.destinationPath(), current.sourcePath(), current.expiresAt()));
    }

    private void forgetPartialSpool(String transferId) {
        if (transferId == null) return;
        var previous = partialSpoolByTransfer.remove(transferId);
        if (previous != null) partialSpoolBytes.addAndGet(-previous);
    }

    private Instant memoryExpiry(String transferId) {
        var current = transferId == null ? null : memory.get(transferId);
        return current == null || current.expiresAt() == null
                ? Instant.now().plus(artifactRetention) : current.expiresAt();
    }

    private static long saturatingAdd(long left, long right) {
        if (right <= 0 || Long.MAX_VALUE - left < right) return Long.MAX_VALUE;
        return left + right;
    }

    private static void requireRequest(TaskOrigin origin, CreateTaskRequest request) {
        if (origin == null || request == null || request.machineId().isBlank()) throw new IllegalArgumentException("transfer request is incomplete");
    }

    /**
     * A transfer has a durable payload and may be retried long after the MCP
     * request that created it.  Deriving its identity from an empty key makes
     * every later request for the same path reuse an old transfer forever, so
     * the file tools require the caller to provide a stable retry key.
     */
    private static void requireIdempotencyKey(CreateTaskRequest request) {
        var key = request == null ? "" : request.idempotencyKey();
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("idempotency_key is required for file transfers");
        }
        if (key.length() > 256) throw new IllegalArgumentException("idempotency_key is too long");
    }

    private static Set<InetAddress> validateDownloadUrl(URI uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("download_url must be an HTTPS URL");
        }
        try {
            var addresses = new java.util.LinkedHashSet<InetAddress>();
            for (var address : InetAddress.getAllByName(uri.getHost())) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
                    throw new IllegalArgumentException("download_url points to a private address");
                }
                addresses.add(address);
            }
            if (addresses.isEmpty()) throw new IllegalArgumentException("download_url host has no public address");
            return Set.copyOf(addresses);
        } catch (IOException exception) {
            throw new IllegalArgumentException("download_url host cannot be resolved", exception);
        }
    }

    private static void validatePath(String value, String name) {
        if (value == null || value.isBlank() || value.length() > MAX_PATH || value.indexOf('\u0000') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
                || value.endsWith("/") || value.endsWith("\\")) throw new IllegalArgumentException(name + " must be a complete file path");
    }

    /**
     * Keep one unambiguous path semantic: source/destination is the complete
     * filesystem path, while file_name is only a display override.  When the
     * override is omitted derive a safe leaf so every transfer still has a
     * stable user-facing name.
     */
    private static String fileNameOrLeaf(String provided, String path) {
        if (provided != null && !provided.isBlank()) return provided.trim();
        if (path == null || path.isBlank()) return "";
        var value = path.trim().replace('\\', '/');
        var slash = value.lastIndexOf('/');
        return slash >= 0 ? value.substring(slash + 1) : value;
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

    private static String normalizeMime(String value, String fileName) {
        var candidate = value;
        if (candidate == null || candidate.isBlank()) candidate = inferredMime(fileName);
        return normalizeMime(candidate);
    }

    private static String inferredMime(String fileName) {
        var name = fileName == null ? "" : fileName.trim().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".pdf")) return "application/pdf";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".txt") || name.endsWith(".log")) return "text/plain";
        return "application/octet-stream";
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

    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 is unavailable", exception); }
    }

    private String sign(String value) {
        return sign(value, tokens.currentArtifactSigningKey());
    }

    private String sign(String value, CenterTokenConfig.ArtifactSigningKey key) {
        try {
            var mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key.secret().getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException("artifact URL signing is unavailable", exception); }
    }

    private boolean verifySignature(String value, byte[] supplied) {
        for (var key : tokens.artifactSigningKeys()) {
            var expected = sign(value, key).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(expected, supplied)) return true;
        }
        return false;
    }

    private String accessToken(String artifactId, String principal, long expires, String purpose,
                               String connection, String session) {
        try {
            var signingKey = tokens.currentArtifactSigningKey();
            // The key id is encrypted inside the opaque token.  It permits a
            // current+previous rotation without putting key metadata in the
            // public URL or proxy logs.
            var plain = (signingKey.kid() + "\n" + artifactId + "\n" + principal + "\n" + expires + "\n" + purpose + "\n"
                    + connection + "\n" + session).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var nonce = new byte[12];
            accessTokenRandom.nextBytes(nonce);
            var cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, accessKey(signingKey.secret()), new javax.crypto.spec.GCMParameterSpec(128, nonce));
            var encrypted = cipher.doFinal(plain);
            var combined = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, combined, 0, nonce.length);
            System.arraycopy(encrypted, 0, combined, nonce.length, encrypted.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(combined);
        } catch (Exception exception) {
            throw new IllegalStateException("artifact access token encryption is unavailable", exception);
        }
    }

    private AccessToken decodeAccessToken(String token) {
        if (token == null || token.isBlank() || token.length() > 4096) {
            throw new SecurityException("invalid or expired artifact URL");
        }
        try {
            var normalized = token.trim();
            var combined = Base64.getUrlDecoder().decode(normalized);
            // Reject alternate Base64URL spellings whose unused tail bits
            // decode to the same bytes.  Without canonical encoding, a token
            // with its final character changed can be accepted on one JDK but
            // rejected on another, weakening tamper detection and making the
            // signed URL contract platform-dependent.
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(combined).equals(normalized)) {
                throw new SecurityException("invalid or expired artifact URL");
            }
            if (combined.length < 12 + 16) throw new SecurityException("invalid or expired artifact URL");
            var nonce = Arrays.copyOfRange(combined, 0, 12);
            var encrypted = Arrays.copyOfRange(combined, 12, combined.length);
            var cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            for (var key : tokens.artifactSigningKeys()) {
                try {
                    cipher.init(javax.crypto.Cipher.DECRYPT_MODE, accessKey(key.secret()), new javax.crypto.spec.GCMParameterSpec(128, nonce));
                    var fields = new String(cipher.doFinal(encrypted), java.nio.charset.StandardCharsets.UTF_8).split("\\n", -1);
                    if (fields.length != 7 || !key.kid().equals(fields[0])
                            || fields[1].isBlank() || fields[2].isBlank()
                            || fields[4].isBlank() || fields[5].length() > 256
                            || fields[6].length() > 256) continue;
                    var expires = Long.parseLong(fields[3]);
                    if (expires < Instant.now().getEpochSecond()) continue;
                    var signature = sign(signatureSubject(fields[1], fields[2], expires,
                            fields[4], fields[5], fields[6]), key);
                    return new AccessToken(fields[1], expires, fields[2], fields[5],
                            fields[6], fields[4], key.kid(), signature);
                } catch (Exception ignored) {
                    // An AES-GCM authentication failure is expected while
                    // trying the other active rotation key.
                }
            }
            throw new SecurityException("invalid or expired artifact URL");
        } catch (SecurityException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SecurityException("invalid or expired artifact URL", exception);
        }
    }

    private static javax.crypto.spec.SecretKeySpec accessKey(String secret) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(
                    secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new javax.crypto.spec.SecretKeySpec(digest, "AES");
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
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
                ? Path.of(System.getProperty("java.io.tmpdir", "."), "remote-connect-mcp-transfer").toString()
                : configured.trim();
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

    private static long regularFileSize(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return 0L;
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("transfer resume spool is not a regular file");
        }
        return Files.size(path);
    }

    /**
     * Acquire a reference-counted per-transfer monitor.  A plain
     * computeIfAbsent/remove pair is racy: a waiter can still hold the old
     * monitor while a new caller creates a second monitor for the same ID,
     * allowing two chunk writers to proceed concurrently.  References keep
     * the monitor in the map until every waiter has left it.
     */
    private TransferLock acquireTransferLock(String transferId) {
        return transferLocks.compute(transferId, (ignored, current) -> {
            var value = current == null ? new TransferLock() : current;
            value.references.incrementAndGet();
            return value;
        });
    }

    private void releaseTransferLock(String transferId, TransferLock guard) {
        if (guard == null || guard.references.decrementAndGet() != 0) return;
        // Re-check the reference count in the map's per-key atomic update. If
        // a future caller acquired the guard between decrement and removal it
        // is retained; otherwise it is safe to retire the monitor.
        transferLocks.computeIfPresent(transferId, (ignored, current) ->
                current == guard && current.references.get() == 0 ? null : current);
    }

    public record TransferCreated(TaskView task, TransferDescriptor transfer) { }
    public record ArtifactAdminView(String artifactId, String transferId, String taskId, String principalId,
                                    String machineId, String fileName, String mimeType, long bytes,
                                    long bytesTransferred, String status, String transferStatus,
                                    Instant createdAt, Instant expiresAt, String sha256, String sessionId,
                                    String retentionPolicy, boolean pinned) {
        public ArtifactAdminView(String artifactId, String transferId, String taskId, String principalId,
                                 String machineId, String fileName, String mimeType, long bytes,
                                 long bytesTransferred, String status, String transferStatus,
                                 Instant createdAt, Instant expiresAt, String sha256, String sessionId) {
            this(artifactId, transferId, taskId, principalId, machineId, fileName, mimeType, bytes,
                    bytesTransferred, status, transferStatus, createdAt, expiresAt, sha256, sessionId,
                    "task-bound", false);
        }
    }
    public record TransferDescriptor(String transferId, String artifactId, String direction, String taskId,
                                     String principalId, String machineId, String fileName, String mimeType, long bytes,
                                     String sha256, String status, String error, String downloadUrl,
                                     long bytesTransferred) {
        /** Construction overload when progress has not been reported yet. */
        public TransferDescriptor(String transferId, String artifactId, String direction, String taskId,
                                  String principalId, String machineId, String fileName, String mimeType, long bytes,
                                  String sha256, String status, String error, String downloadUrl) {
            this(transferId, artifactId, direction, taskId, principalId, machineId, fileName, mimeType, bytes,
                    sha256, status, error, downloadUrl, defaultProgress(bytes, status));
        }

        public TransferDescriptor {
            bytes = Math.max(0L, bytes);
            bytesTransferred = Math.max(0L, Math.min(bytes == 0L ? MAX_BYTES : bytes, bytesTransferred));
        }

        private static long defaultProgress(long bytes, String status) {
            return "delivered".equalsIgnoreCase(status) ? Math.max(0L, bytes) : 0L;
        }
    }
    public record InlineArtifact(String artifactId, String fileName, String mimeType, String sha256, byte[] data) { }
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
                                  double averageDurationSeconds, double maxDurationSeconds,
                                  long resumeCount, long partialSpoolBytes, long gcBytes) {
        /** Construction overload when resume and garbage-collection counters are zero. */
        public TransferMetrics(long active, long delivered, long failed, long canceled,
                               long bytesTransferred, long expectedBytes,
                               double averageDurationSeconds, double maxDurationSeconds) {
            this(active, delivered, failed, canceled, bytesTransferred, expectedBytes,
                    averageDurationSeconds, maxDurationSeconds, 0L, 0L, 0L);
        }
    }
    public record ArtifactGcResult(int metadataRows, int objectFiles, int deleteFailures,
                                   long objectBytes, int orphanFiles) { }
    private record Downloaded(Path path, long bytes, String sha256) { }
    private record Ids(String transferId, String artifactId) { }
    private record StoredArtifact(String objectKey, String principalId) { }
    private record TransferRow(String transferId, String artifactId, String taskId, String principalId, String machineId,
                               String direction, String sourcePath, String destinationPath, String fileName, String mimeType,
                               long bytes, long bytesTransferred, String sha256, String status, String error, String objectKey) { }
    private record PendingReservation(String transferId, String taskId, String machineId) { }
    private record OrphanPreparedTask(String taskId, String machineId) { }
    private record MemoryTransfer(TransferDescriptor descriptor, String objectKey, String destinationPath, String sourcePath,
                                  Instant expiresAt, String retentionPolicy, boolean pinned) {
        private MemoryTransfer(TransferDescriptor descriptor, String objectKey, String destinationPath, String sourcePath) {
            this(descriptor, objectKey, destinationPath, sourcePath,
                    Instant.now().plus(DEFAULT_ARTIFACT_RETENTION), "task-bound", false);
        }

        private MemoryTransfer(TransferDescriptor descriptor, String objectKey, String destinationPath, String sourcePath,
                               Instant expiresAt) {
            this(descriptor, objectKey, destinationPath, sourcePath, expiresAt, "task-bound", false);
        }
    }
    private record Retention(Instant expiresAt, boolean pinned) { }
    private record AdminArtifactRow(String artifactId, String fileName, String mimeType, long bytes,
                                    String sha256, String status, String objectKey) { }
    private record StoredObject(String objectKey, String status) { }
    private record ArtifactGcCandidate(String artifactId, String taskId, String objectKey, long bytes) { }
    private record AccessToken(String artifactId, long expires, String principal, String connection,
                               String session, String purpose, String kid, String signature) { }

    private static final class TransferLock {
        private final Object monitor = new Object();
        private final AtomicLong references = new AtomicLong();
    }
}
