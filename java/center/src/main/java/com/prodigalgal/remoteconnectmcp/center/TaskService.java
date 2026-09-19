package com.prodigalgal.remoteconnectmcp.center;

import com.prodigalgal.remoteconnectmcp.protocol.AgentCapability;
import com.prodigalgal.remoteconnectmcp.protocol.ArtifactResponse;
import com.prodigalgal.remoteconnectmcp.protocol.PollRequest;
import com.prodigalgal.remoteconnectmcp.protocol.PollResponse;
import com.prodigalgal.remoteconnectmcp.protocol.OutputResponse;
import com.prodigalgal.remoteconnectmcp.protocol.ProtocolValidation;
import com.prodigalgal.remoteconnectmcp.protocol.TaskCommand;
import com.prodigalgal.remoteconnectmcp.protocol.TaskKind;
import com.prodigalgal.remoteconnectmcp.protocol.TaskUpdateRequest;
import com.prodigalgal.remoteconnectmcp.protocol.TaskProgressUpdate;
import com.prodigalgal.remoteconnectmcp.protocol.ScopeMode;
import com.prodigalgal.remoteconnectmcp.protocol.ExecutionContract;
import com.prodigalgal.remoteconnectmcp.protocol.WorkspacePolicy;
import com.prodigalgal.remoteconnectmcp.protocol.WorkspacePolicyMode;
import com.prodigalgal.remoteconnectmcp.protocol.LaneMode;
import com.prodigalgal.remoteconnectmcp.protocol.SensitiveValueRedactor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;

/**
 * Durable-task facade with one selected storage adapter per Center process.
 * Memory mode is only for protocol tests and local development; when a JDBC
 * adapter is present PostgreSQL is the sole source of truth for task state,
 * leases, attempts, output cursors and artifacts. The two adapters are never
 * dual-written.
 */
@Service
public final class TaskService {
    public static final int MAX_OUTPUT_BYTES = 64 * 1024 * 1024;
    private static final long MAX_ARTIFACT_BYTES = 8L * 1024 * 1024;
    private static final int MAX_CHILD_PROCESSES = 32;
    static final int MAX_OUTPUT_CHUNK_BYTES = 256 * 1024;
    public static final int MAX_OUTPUT_PAGE = 64 * 1024;
    static final Duration LEASE_DURATION = Duration.ofMinutes(2);
    static final Duration DEFAULT_CONTRACT_LIFETIME = Duration.ofDays(30);
    static final Duration DEFAULT_TASK_METADATA_RETENTION = Duration.ofDays(30);
    static final Duration DEFAULT_TASK_OUTPUT_RETENTION = Duration.ofDays(7);
    private final AgentRegistry agents;
    private final Map<String, TaskState> tasks = new ConcurrentHashMap<>();
    private final Map<String, String> idempotency = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final AtomicLong localChangeSequence = new AtomicLong();
    private final AtomicLong artifactGcBytes = new AtomicLong();
    private final AtomicLong progressUpdates = new AtomicLong();
    private final AtomicLong progressRejected = new AtomicLong();
    private final JdbcTaskStore jdbcStore;
    private final AgentWakeRegistry wakes;
    private final TaskChangeRegistry taskChanges;
    private final AuditService audit;
    private final ExecutionSessionService sessions;
    private final McpQuotaService quota;

    @Autowired
    public TaskService(AgentRegistry agents, ObjectProvider<JdbcTemplate> jdbcProvider,
                       ObjectProvider<TransactionTemplate> transactionProvider,
                       ObjectProvider<AgentWakeRegistry> wakeProvider,
                       ObjectProvider<TaskChangeRegistry> taskChangeProvider,
                       ObjectProvider<ArtifactStore> artifactProvider,
                       ObjectProvider<AuditService> auditProvider,
                       ObjectProvider<ExecutionSessionService> sessionProvider,
                       ObjectProvider<McpQuotaService> quotaProvider) {
        this.agents = agents;
        var jdbc = jdbcProvider.getIfAvailable();
        var artifactStore = artifactProvider.getIfAvailable();
        this.quota = quotaProvider == null ? null : quotaProvider.getIfAvailable();
        this.jdbcStore = jdbc == null ? null : new JdbcTaskStore(jdbc, transactionProvider.getIfAvailable(),
                artifactStore == null ? new InMemoryArtifactStore() : artifactStore, this.quota);
        this.wakes = wakeProvider.getIfAvailable();
        this.taskChanges = taskChangeProvider.getIfAvailable();
        this.audit = auditProvider == null ? null : auditProvider.getIfAvailable();
        this.sessions = sessionProvider == null ? null : sessionProvider.getIfAvailable();
    }

    TaskService(AgentRegistry agents) {
        this.agents = agents;
        this.jdbcStore = null;
        this.wakes = null;
        this.taskChanges = null;
        this.audit = null;
        this.sessions = null;
        this.quota = null;
    }

    /** Package-private constructor used by the PostgreSQL contract tests. */
    TaskService(AgentRegistry agents, JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.agents = agents;
        this.jdbcStore = jdbc == null ? null : new JdbcTaskStore(jdbc, transactions, new InMemoryArtifactStore());
        this.wakes = null;
        this.taskChanges = null;
        this.audit = null;
        this.sessions = null;
        this.quota = null;
    }

    /** Package-private constructor for PostgreSQL session contract tests. */
    TaskService(AgentRegistry agents, JdbcTemplate jdbc, TransactionTemplate transactions,
                ExecutionSessionService sessions) {
        this.agents = agents;
        this.jdbcStore = jdbc == null ? null : new JdbcTaskStore(jdbc, transactions, new InMemoryArtifactStore());
        this.wakes = null;
        this.taskChanges = null;
        this.audit = null;
        this.sessions = sessions;
        this.quota = null;
    }

    /** Enqueue a task using the default admin audit source. */
    public TaskView create(CreateTaskRequest request) {
        return create(request, "admin");
    }

    /** Enqueue a task and retain only a bounded, normalized audit source. */
    public TaskView create(CreateTaskRequest request, String auditActor) {
        return create(request, auditActor, request == null ? null : request.origin());
    }

    /** Enqueue a task with an explicit MCP principal/connection owner. */
    public TaskView create(CreateTaskRequest request, String auditActor, TaskOrigin requestedOrigin) {
        var actor = normalizeAuditActor(auditActor);
        if (request == null || request.machineId().isBlank()) {
            throw new IllegalArgumentException("machineId is required");
        }
        var origin = requestedOrigin == null ? TaskOrigin.configured() : requestedOrigin;
        var id = "task_" + UUID.randomUUID().toString().replace("-", "");
        // This is an early, low-cost rejection so a session is not created
        // for an obviously exhausted principal. JDBC repeats the admission
        // authoritatively inside JdbcTaskStore's transaction, where the count
        // and task INSERT share one database lock.
        if (quota != null) {
            quota.assertTaskAdmission(origin, id, request.machineId(), request.idempotencyKey());
        }
        try {
            var machine = agents.findMachine(request.machineId(), Instant.now()).orElseThrow(() -> new IllegalArgumentException("machine not found"));
            var original = request.command();
            var kind = original.kind() == null ? TaskKind.COMMAND : original.kind();
            var capability = requiredCapability(original, kind);
            if (!machine.capabilities().contains(capability)) {
                throw new IllegalArgumentException("machine does not advertise capability: " + capability);
            }
            if (kind == TaskKind.COMMAND && (original.command() == null || original.command().isBlank())) {
                throw new IllegalArgumentException("command is required for command tasks");
            }
            if (request.idempotencyKey().length() > 256) {
                throw new IllegalArgumentException("idempotencyKey is too long");
            }
            // Environment values are persisted in the task command so a durable
            // Agent can receive them after a Center restart.  Never let a caller
            // put a credential-shaped value into that durable record: the Agent
            // also strips these names before spawning a child, so dropping them at
            // the Center boundary preserves execution semantics while preventing
            // accidental token/password persistence or MCP echoing.
            var safeEnvironment = sanitizeEnvironment(original.env());
            original = new TaskCommand(original.id(), original.kind(), original.requiredCapability(), original.command(),
                    original.cwd(), safeEnvironment, original.timeoutSeconds(), original.desktop(), original.createdAt(),
                    original.contract(), original.attempt(), original.fileTransfer());
            var createdAt = Instant.now();
            var contract = buildContract(request, machine, original, capability, id, createdAt, origin);
            var command = new TaskCommand(id, kind, capability, original.command(), original.cwd(), original.env(), original.timeoutSeconds(), original.desktop(), createdAt, contract, 0, original.fileTransfer());
            ProtocolValidation.validateTask(command);
            if (sessions != null) sessions.ensure(origin, contract);

            if (jdbcStore != null) {
                var created = jdbcStore.create(id, request.machineId(), command, request.idempotencyKey(), command.createdAt(), origin);
                signalChanged(id);
                signalWake(request.machineId());
                audit("task.created", actor, request.machineId(), created.id(), command, "accepted",
                        "attempt=0,elevation=" + command.contract().elevationRequired());
                if (quota != null && !id.equals(created.id())) quota.releaseTask(origin, id);
                return created;
            }

            lock.lock();
            try {
                if (!request.idempotencyKey().isBlank()) {
                    var key = idempotencyKey(request.machineId(), origin.principalId(), request.idempotencyKey());
                    var existingId = idempotency.get(key);
                    if (existingId != null) {
                        var existing = tasks.get(existingId);
                        if (existing != null && sameCommand(existing.command(), command)) {
                            if (quota != null) quota.releaseTask(origin, id);
                            return new TaskView(existing);
                        }
                        throw new IllegalArgumentException("idempotency key is already used with different task parameters");
                    }
                    idempotency.put(key, id);
                }
                var state = new TaskState(id, request.machineId(), command, request.idempotencyKey(), command.createdAt(), origin);
                tasks.put(id, state);
                signalChanged();
                var created = new TaskView(state);
                signalWake(request.machineId());
                audit("task.created", actor, request.machineId(), created.id(), command, "accepted",
                        "attempt=0,elevation=" + command.contract().elevationRequired());
                return created;
            } finally {
                lock.unlock();
            }
        } catch (RuntimeException | Error failure) {
            if (quota != null) quota.releaseTask(origin, id);
            throw failure;
        }
    }

    public Optional<TaskState> find(String taskId) {
        if (jdbcStore != null) {
            return jdbcStore.find(taskId);
        }
        return Optional.ofNullable(tasks.get(taskId == null ? "" : taskId.trim()));
    }

    /**
     * Return a task only when it belongs to the authenticated MCP principal.
     * Admin/Agent callers intentionally keep using {@link #find(String)} so
     * their existing machine/control-plane semantics remain unchanged.
     */
    Optional<TaskState> findFor(TaskOrigin origin, String taskId) {
        var task = find(taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
        assertOwner(task, origin);
        if (sessions != null && task.command() != null && task.command().contract() != null) {
            sessions.authorize(origin, task.executionSessionId(), task.command().requiredCapability());
        }
        return Optional.of(task);
    }

    TaskView cancel(TaskOrigin origin, String taskId) {
        findFor(origin, taskId);
        return cancel(taskId);
    }

    OutputPage readOutput(TaskOrigin origin, String taskId, long cursor, int limit) {
        findFor(origin, taskId);
        return readOutput(taskId, cursor, limit);
    }

    Optional<ArtifactData> readArtifact(TaskOrigin origin, String taskId) {
        findFor(origin, taskId);
        return readArtifact(taskId);
    }

    TaskView waitForChange(TaskOrigin origin, String taskId, long cursor, Duration timeout)
            throws InterruptedException {
        return waitForChange(origin, taskId, cursor, -1L, timeout);
    }

    TaskView waitForChange(TaskOrigin origin, String taskId, long cursor, long changeSequence, Duration timeout)
            throws InterruptedException {
        findFor(origin, taskId);
        var result = waitForChange(taskId, cursor, changeSequence, timeout);
        assertOwner(find(taskId).orElseThrow(() -> new IllegalArgumentException("task not found")), origin);
        return result;
    }

    TaskView waitForTerminal(TaskOrigin origin, String taskId, Duration timeout)
            throws InterruptedException {
        findFor(origin, taskId);
        var result = waitForTerminal(taskId, timeout);
        assertOwner(find(taskId).orElseThrow(() -> new IllegalArgumentException("task not found")), origin);
        return result;
    }

    /** Lightweight restart-safety probe that never loads output or artifacts. */
    boolean isDurableTask(String machineId, String taskId) {
        if (machineId == null || taskId == null || machineId.isBlank() || taskId.isBlank()) return false;
        if (jdbcStore != null) return jdbcStore.isDurableTask(machineId.trim(), taskId.trim());
        var task = tasks.get(taskId.trim());
        return task != null && machineId.trim().equals(task.machineId())
                && !TaskStatus.terminal(task.status()) && task.command().timeoutSeconds() <= 0;
    }

    public List<TaskView> list(int offset, int limit) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be non-negative");
        }
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
        if (jdbcStore != null) {
            return jdbcStore.list(offset, limit);
        }
        var all = new ArrayList<>(tasks.values());
        all.sort(Comparator.comparing(TaskState::createdAt).reversed());
        if (offset >= all.size()) {
            return List.of();
        }
        return all.subList(offset, Math.min(all.size(), offset + limit)).stream().map(TaskView::new).toList();
    }

    private static String normalizeAuditActor(String value) {
        if (value == null || value.isBlank()) return "system";
        var normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.length() > 32 || !normalized.matches("[a-z0-9][a-z0-9._:-]*")) {
            return "system";
        }
        return normalized;
    }

    /**
     * Project lifecycle guard.  Deleting a project while one of its tasks is
     * still queued/running would leave an execution contract pointing at a
     * removed identity.  Keep the query metadata-only so large output blobs
     * are never loaded just to decide whether deletion is safe.
     */
    boolean hasActiveProjectTasks(String projectId) {
        if (projectId == null || projectId.isBlank()) return false;
        var normalized = projectId.trim();
        if (jdbcStore != null) return jdbcStore.hasActiveProjectTasks(normalized);
        lock.lock();
        try {
            return tasks.values().stream().anyMatch(task -> {
                var contract = task.command().contract();
                return contract != null && normalized.equals(contract.projectId())
                        && !TaskStatus.terminal(task.status());
            });
        } finally {
            lock.unlock();
        }
    }

    /** Return status counters only; never materialize commands or output. */
    public Map<String, Long> statusCounts() {
        if (jdbcStore != null) return jdbcStore.taskStatusCounts();
        lock.lock();
        try {
            var counts = new java.util.TreeMap<String, Long>();
            tasks.values().forEach(task -> counts.merge(task.status(), 1L, Long::sum));
            return Map.copyOf(counts);
        } finally {
            lock.unlock();
        }
    }

    /** Sum persisted output byte counters without reading the blobs. */
    public long outputBytesTotal() {
        if (jdbcStore != null) return jdbcStore.outputBytesTotal();
        lock.lock();
        try {
            return tasks.values().stream().mapToLong(TaskState::outputBytes).sum();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Return a bounded, low-cardinality task SLO projection.  The projection
     * is calculated on demand for a metrics scrape; it never loads command
     * text, environment values, output or artifact bytes.  PostgreSQL remains
     * the sole source of truth when the JDBC adapter is active.
     */
    public TaskSloMetrics sloMetrics(Instant now) {
        var reference = now == null ? Instant.now() : now;
        if (jdbcStore != null) return jdbcStore.sloMetrics(reference);
        lock.lock();
        try {
            var queued = 0L;
            var active = 0L;
            var terminal = 0L;
            var completed = 0L;
            var failed = 0L;
            var canceled = 0L;
            var expiredLeases = 0L;
            var oldestQueued = 0L;
            for (var task : tasks.values()) {
                switch (task.status()) {
                    case TaskStatus.QUEUED -> {
                        queued++;
                        oldestQueued = Math.max(oldestQueued, ageSeconds(reference, task.createdAt()));
                    }
                    case TaskStatus.DISPATCHING, TaskStatus.RUNNING, TaskStatus.CANCEL_REQUESTED -> {
                        active++;
                        if (task.leaseUntil() != null && !reference.isBefore(task.leaseUntil())) expiredLeases++;
                    }
                    case TaskStatus.COMPLETED -> {
                        terminal++;
                        completed++;
                    }
                    case TaskStatus.FAILED -> {
                        terminal++;
                        failed++;
                    }
                    case TaskStatus.CANCELED -> {
                        terminal++;
                        canceled++;
                    }
                    default -> {
                        // Unknown persisted states are intentionally excluded
                        // from SLO ratios rather than guessed.
                    }
                }
            }
            var artifacts = tasks.values().stream().mapToLong(TaskState::artifactBytes).sum();
            return new TaskSloMetrics(queued, active, terminal, completed, failed, canceled,
                    oldestQueued, expiredLeases, tasks.values().stream().mapToLong(TaskState::outputBytes).sum(),
                    tasks.values().stream().filter(value -> value.artifactBytes() > 0).count(), artifacts);
        } finally {
            lock.unlock();
        }
    }

    private static long ageSeconds(Instant now, Instant createdAt) {
        if (createdAt == null || now == null || now.isBefore(createdAt)) return 0L;
        return Math.max(0L, Duration.between(createdAt, now).toSeconds());
    }

    /** Low-cardinality task and artifact values used by the metrics endpoint. */
    public record TaskSloMetrics(long queued, long active, long terminal, long completed, long failed,
                                 long canceled, long oldestQueuedAgeSeconds, long expiredLeases,
                                 long outputBytes, long artifactObjects, long artifactBytes) {
    }

    public TaskView cancel(String taskId) {
        if (jdbcStore != null) {
            var view = jdbcStore.cancel(taskId);
            signalChanged(taskId);
            signalWake(view.machineId());
            audit("task.cancel", "admin", view.machineId(), view, view.status(), null);
            return view;
        }
        lock.lock();
        try {
            var task = required(taskId);
            if (TaskStatus.terminal(task.status())) {
                return new TaskView(task);
            }
            task.status(TaskStatus.QUEUED.equals(task.status()) ? TaskStatus.CANCELED : TaskStatus.CANCEL_REQUESTED);
            if (TaskStatus.CANCELED.equals(task.status())) {
                task.finishedAt(Instant.now());
                task.leaseUntil(null);
                releaseQuota(task);
            }
            signalChanged(task.id());
            var view = new TaskView(task);
            signalWake(view.machineId());
            audit("task.cancel", "admin", view.machineId(), view.id(), task.command(), view.status(), null);
            return view;
        } finally {
            lock.unlock();
        }
    }

    public PollResponse poll(String machineId, PollRequest request) {
        if (jdbcStore != null) {
            var result = jdbcStore.pollWithRecovery(machineId, request);
            var response = result.response();
            // Lease recovery changes are committed in the JDBC transaction;
            // wake only the affected task waiters after commit.  Without this
            // hand-off, a timed task that is failed (or a durable task that is
            // re-queued) would remain invisible to task_wait until its caller
            // deadline even though the row was already authoritative.
            result.recoveredTaskIds().forEach(this::signalChanged);
            // A claim changes the authoritative task state from queued to
            // dispatching. Wake task_wait callers after the transaction so
            // they do not wait for an output chunk or the long-poll deadline.
            if (response.task() != null) signalChanged(response.task().id());
            return response;
        }
        lock.lock();
        try {
            var now = Instant.now();
            var recoveredTaskIds = recoverExpiredLeases(now);
            recoveredTaskIds.forEach(this::signalChanged);
            renewRunningLeases(machineId, request, now);
            var cancelIds = tasks.values().stream()
                    .filter(task -> task.machineId().equals(machineId) && TaskStatus.CANCEL_REQUESTED.equals(task.status()))
                    .sorted(Comparator.comparing(TaskState::createdAt))
                    .limit(64)
                    .map(TaskState::id)
                    .toList();
            TaskCommand task = null;
            var availableSlots = request == null || request.availableSlots() == null ? 0 : request.availableSlots();
            var capabilities = request == null ? List.<String>of() : request.availableCapabilities();
            if (availableSlots > 0) {
                var candidate = tasks.values().stream()
                        .filter(value -> value.machineId().equals(machineId) && TaskStatus.QUEUED.equals(value.status()))
                        .filter(value -> capabilities.contains(value.command().requiredCapability()))
                        .filter(TaskService::fileTransferReady)
                        .filter(value -> quota == null || quota.permitsActivation(value.origin(), value.id()))
                        .filter(value -> !laneBusy(value))
                        .sorted(Comparator.comparing(TaskState::createdAt))
                        .findFirst();
                if (candidate.isPresent()) {
                    var selected = candidate.get();
                    selected.status(TaskStatus.DISPATCHING);
                    selected.attempt(selected.attempt() + 1);
                    selected.dispatchedAt(now);
                    selected.leaseUntil(now.plus(LEASE_DURATION));
                    if (quota != null) quota.markTaskActive(selected.origin(), selected.id());
                    // Carry the monotonically increasing lease attempt on the
                    // wire. Agent state/output delivery can then be fenced if
                    // a stale process wakes after its lease was reclaimed.
                    task = selected.command().withAttempt(selected.attempt());
                    signalChanged(selected.id());
                }
            }
            return new PollResponse(task, cancelIds, null, null);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Replace the pending metadata marker of an asynchronously ingested
     * Web-to-Agent transfer.  The task must still be queued; once this method
     * commits, the normal event wake makes it eligible for the next Agent
     * long-poll without a timer loop.
     */
    public TaskView updateFileTransferAction(String machineId, String taskId,
                                             com.prodigalgal.remoteconnectmcp.protocol.FileTransferAction action) {
        if (action == null) throw new IllegalArgumentException("file transfer action is required");
        // Validate the action against the durable task identity.  The async
        // Web->Agent ingest calls this method after the task has already been
        // created; using an empty id here makes the protocol validator reject
        // every successfully downloaded file before it can be dispatched.
        ProtocolValidation.validateTask(new TaskCommand(taskId, TaskKind.FILE_TRANSFER,
                AgentCapability.FILE_TRANSFER.wireValue(), null, ".", Map.of(), 0, null, Instant.now(), null, 0, action));
        if (jdbcStore != null) {
            var view = jdbcStore.updateFileTransferAction(machineId, taskId, action);
            signalChanged(taskId);
            signalWake(machineId);
            return view;
        }
        lock.lock();
        try {
            var task = required(taskId);
            assertMachine(task, machineId);
            if (!TaskStatus.QUEUED.equals(task.status())) throw new IllegalStateException("file transfer task is no longer queued");
            if (task.command().kind() != TaskKind.FILE_TRANSFER) throw new IllegalArgumentException("task is not a file transfer");
            var original = task.command();
            task.command(new TaskCommand(original.id(), original.kind(), original.requiredCapability(), original.command(), original.cwd(),
                    original.env(), original.timeoutSeconds(), original.desktop(), original.createdAt(), original.contract(),
                    original.attempt(), action));
            signalChanged(taskId);
            signalWake(machineId);
            return new TaskView(task);
        } finally {
            lock.unlock();
        }
    }

    /** Mark a queued asynchronous ingest as failed without pretending an Agent ran it. */
    public TaskView failPreparedFileTransfer(String machineId, String taskId, String error) {
        if (jdbcStore != null) {
            var view = jdbcStore.failPreparedFileTransfer(machineId, taskId, error);
            signalChanged(taskId);
            signalWake(machineId);
            return view;
        }
        lock.lock();
        try {
            var task = required(taskId);
            assertMachine(task, machineId);
            if (TaskStatus.QUEUED.equals(task.status())) {
                task.status(TaskStatus.FAILED);
                task.error(compactError(error == null || error.isBlank() ? "file transfer preparation failed" : error));
                task.finishedAt(Instant.now());
                task.leaseUntil(null);
                releaseQuota(task);
                signalChanged(taskId);
                signalWake(machineId);
            }
            return new TaskView(task);
        } finally {
            lock.unlock();
        }
    }

    private static boolean fileTransferReady(TaskState task) {
        var action = task.command() == null ? null : task.command().fileTransfer();
        return action == null || !action.webToAgent()
                || (action.expectedBytes() >= 0 && action.expectedSha256().matches("(?i)[0-9a-f]{64}"));
    }

    /**
     * Fence a streaming side-channel operation against the task lease
     * attempt.  File transfers can spend minutes outside the task-state
     * endpoint; checking the attempt immediately before committing metadata
     * prevents a stale Agent from publishing bytes after a lease reclaim.
     * The positive attempt fence is required for every streaming operation.
     */
    public void assertCurrentAttempt(String machineId, String taskId, Integer attempt) {
        if (jdbcStore != null) {
            jdbcStore.assertCurrentAttempt(machineId, taskId, attempt);
            return;
        }
        lock.lock();
        try {
            var task = required(taskId);
            assertMachine(task, machineId);
            assertAttempt(task, attempt);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Apply a state update with the dispatch-attempt fence.
     */
    public TaskView updateState(String machineId, String taskId, TaskUpdateRequest update, Integer attempt) {
        if (update == null || update.status() == null || update.status().isBlank()) {
            throw new IllegalArgumentException("status is required");
        }
        if (jdbcStore != null) {
            var view = jdbcStore.updateState(machineId, taskId, update, attempt);
            signalChanged(taskId);
            audit("task.state", "agent", machineId, view, view.status(),
                    "attempt=" + attempt);
            return view;
        }
        lock.lock();
        try {
            var task = required(taskId);
            assertMachine(task, machineId);
            assertAttempt(task, attempt);
            var status = update.status().trim().toLowerCase();
            if (!validStatus(status)) {
                throw new IllegalArgumentException("unsupported task status: " + status);
            }
            if (!allowedTransition(task.status(), status)) {
                throw new IllegalArgumentException("invalid task transition: " + task.status() + " -> " + status);
            }
            task.status(status);
            if ((TaskStatus.DISPATCHING.equals(status) || TaskStatus.RUNNING.equals(status)) && quota != null) {
                quota.markTaskActive(task.origin(), task.id());
            }
            if (update.exitCode() != null) {
                task.exitCode(update.exitCode());
            }
            if (update.error() != null && !update.error().isBlank()) {
                task.error(compactError(update.error()));
            }
            if (update.startedAt() != null) {
                task.startedAt(update.startedAt());
            } else if (TaskStatus.RUNNING.equals(status) && task.startedAt() == null) {
                task.startedAt(Instant.now());
            }
            if (update.finishedAt() != null) {
                task.finishedAt(update.finishedAt());
            } else if (TaskStatus.terminal(status) && task.finishedAt() == null) {
                task.finishedAt(Instant.now());
            }
            task.outputTruncated(task.outputTruncated() || update.outputTruncated());
            if (TaskStatus.terminal(status)) {
                task.leaseUntil(null);
                releaseQuota(task);
            }
            signalChanged(taskId);
            audit("task.state", "agent", machineId, taskId, task.command(), task.status(),
                    "attempt=" + attempt);
            return new TaskView(task);
        } finally {
            lock.unlock();
        }
    }

    /** Service-internal shorthand; resolves the current durable attempt. */
    public TaskView updateState(String machineId, String taskId, TaskUpdateRequest update) {
        return updateState(machineId, taskId, update, currentAttempt(machineId, taskId));
    }

    /** Apply an advisory progress snapshot with the dispatch-attempt fence. */
    public TaskView updateProgress(String machineId, String taskId, TaskProgressUpdate progress, Integer attempt) {
        if (progress == null) throw new IllegalArgumentException("progress is required");
        try {
            if (jdbcStore != null) {
                var view = jdbcStore.updateProgress(machineId, taskId, progress, attempt);
                progressUpdates.incrementAndGet();
                signalChanged(taskId);
                return view;
            }
            lock.lock();
            try {
                var task = required(taskId);
                assertMachine(task, machineId);
                assertAttempt(task, attempt);
                task.progressPhase(progress.phase());
                task.progressPercent(progress.percent());
                task.progressMessage(progress.message());
                task.progressCurrent(progress.current());
                task.progressTotal(progress.total());
                task.progressUnit(progress.unit());
                task.progressUpdatedAt(Instant.now());
                progressUpdates.incrementAndGet();
                signalChanged(taskId);
                return new TaskView(task);
            } finally {
                lock.unlock();
            }
        } catch (RuntimeException failure) {
            progressRejected.incrementAndGet();
            throw failure;
        }
    }

    public long progressUpdates() {
        return progressUpdates.get();
    }

    public long progressRejected() {
        return progressRejected.get();
    }

    public TaskView updateProgress(String machineId, String taskId, TaskProgressUpdate progress) {
        return updateProgress(machineId, taskId, progress, currentAttempt(machineId, taskId));
    }

    /** Append output while fencing the dispatch attempt. */
    public OutputResponse appendOutput(String machineId, String taskId, long offset, byte[] data, Integer attempt) {
        if (offset < 0 || data == null || data.length > MAX_OUTPUT_CHUNK_BYTES) {
            throw new IllegalArgumentException("offset and data are required; output chunks are limited to 256 KiB");
        }
        if (jdbcStore != null) {
            var response = jdbcStore.appendOutput(machineId, taskId, offset, data, attempt);
            signalOutputChanged(taskId);
            return response;
        }
        lock.lock();
        try {
            var task = required(taskId);
            assertMachine(task, machineId);
            assertAttempt(task, attempt);
            var current = task.output().toByteArray();
            if (offset > current.length) {
                throw new IllegalArgumentException("output offset is ahead of the confirmed cursor");
            }
            var overlap = (int) Math.min(data.length, current.length - offset);
            for (var index = 0; index < overlap; index++) {
                if (current[(int) offset + index] != data[index]) {
                    throw new IllegalArgumentException("output replay does not match the confirmed bytes");
                }
            }
            var appendFrom = overlap;
            var maxOutputBytes = outputLimit(task);
            var remaining = Math.max(0L, maxOutputBytes - current.length);
            var appendLength = (int) Math.min((long) data.length - appendFrom, remaining);
            if (appendLength > 0) {
                task.output().write(data, appendFrom, appendLength);
            }
            if (appendFrom + appendLength < data.length) {
                task.outputTruncated(true);
            }
            task.outputBytes(task.output().size());
            signalOutputChanged(taskId);
            return new OutputResponse(task.outputBytes());
        } finally {
            lock.unlock();
        }
    }

    /** Service-internal shorthand; resolves the current durable attempt. */
    public OutputResponse appendOutput(String machineId, String taskId, long offset, byte[] data) {
        return appendOutput(machineId, taskId, offset, data, currentAttempt(machineId, taskId));
    }

    public OutputPage readOutput(String taskId, long cursor, int limit) {
        if (cursor < 0) {
            throw new IllegalArgumentException("cursor must be non-negative");
        }
        if (limit <= 0) {
            limit = 16 * 1024;
        }
        if (limit > MAX_OUTPUT_PAGE) {
            throw new IllegalArgumentException("limit must be at most " + MAX_OUTPUT_PAGE);
        }
        if (jdbcStore != null) {
            return jdbcStore.readOutput(taskId, cursor, limit);
        }
        lock.lock();
        try {
            var task = required(taskId);
            var bytes = task.output().toByteArray();
            if (cursor > bytes.length) {
                throw new IllegalArgumentException("cursor is ahead of output");
            }
            var end = Math.min(bytes.length, Math.toIntExact(cursor) + limit);
            var data = java.util.Arrays.copyOfRange(bytes, Math.toIntExact(cursor), end);
            return new OutputPage(data, cursor, end, end < bytes.length || (!TaskStatus.terminal(task.status()) && task.outputTruncated()));
        } finally {
            lock.unlock();
        }
    }

    public ArtifactResponse appendArtifact(String machineId, String taskId, String mimeType, String sha256, byte[] data) {
        return appendArtifact(machineId, taskId, mimeType, sha256, data, currentAttempt(machineId, taskId));
    }

    /** Append an artifact while fencing the dispatch attempt. */
    public ArtifactResponse appendArtifact(String machineId, String taskId, String mimeType, String sha256,
                                           byte[] data, Integer attempt) {
        var normalizedMime = normalizeMimeType(mimeType);
        if (data == null) {
            throw new IllegalArgumentException("artifact data is required");
        }
        if (data.length > MAX_ARTIFACT_BYTES) {
            throw new IllegalArgumentException("artifact exceeds " + MAX_ARTIFACT_BYTES + " bytes");
        }
        var digest = sha256(data);
        if (sha256 == null || !digest.equalsIgnoreCase(sha256.trim())) {
            throw new IllegalArgumentException("artifact sha256 does not match data");
        }
        if (jdbcStore != null) {
            var response = jdbcStore.appendArtifact(machineId, taskId, normalizedMime, digest, data, attempt);
            signalOutputChanged(taskId);
            find(taskId).map(TaskView::new).ifPresent(view -> audit("task.artifact", "agent", machineId, view,
                    "accepted", "bytes=" + data.length + ",sha256=" + digest));
            return response;
        }
        lock.lock();
        try {
            var task = required(taskId);
            assertMachine(task, machineId);
            assertAttempt(task, attempt);
            if (data.length > artifactLimit(task)) {
                throw new IllegalArgumentException("artifact exceeds the execution contract limit of " + artifactLimit(task) + " bytes");
            }
            if (task.artifactData() != null && task.artifactData().length > 0 && !digest.equals(task.artifactSha256())) {
                throw new IllegalArgumentException("task already has a different artifact");
            }
            task.artifactData(data);
            task.artifactBytes(data.length);
            task.artifactMime(normalizedMime);
            task.artifactSha256(digest);
            signalOutputChanged(taskId);
            audit("task.artifact", "agent", machineId, taskId, task.command(), "accepted",
                    "bytes=" + data.length + ",sha256=" + digest);
            return new ArtifactResponse(data.length, digest);
        } finally {
            lock.unlock();
        }
    }

    private static String normalizeMimeType(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("artifact mimeType and data are required");
        }
        var normalized = value.trim();
        if (normalized.length() > 128 || normalized.indexOf('\r') >= 0 || normalized.indexOf('\n') >= 0
                || normalized.indexOf('\u0000') >= 0 || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("artifact mimeType is invalid");
        }
        return normalized;
    }

    static long outputLimit(TaskState task) {
        if (task == null || task.command() == null || task.command().contract() == null) return MAX_OUTPUT_BYTES;
        return Math.min(MAX_OUTPUT_BYTES, task.command().contract().budget().maxOutputBytes());
    }

    static long artifactLimit(TaskState task) {
        if (task == null || task.command() == null || task.command().contract() == null) return MAX_ARTIFACT_BYTES;
        return Math.min(MAX_ARTIFACT_BYTES, task.command().contract().budget().maxArtifactBytes());
    }

    public Optional<ArtifactData> readArtifact(String taskId) {
        if (jdbcStore != null) {
            return jdbcStore.readArtifact(taskId);
        }
        lock.lock();
        try {
            var task = required(taskId);
            var data = task.artifactData();
            return data == null ? Optional.empty() : Optional.of(new ArtifactData(task.artifactMime(), task.artifactSha256(), data));
        } finally {
            lock.unlock();
        }
    }

    public TaskView waitForChange(String taskId, long cursor, Duration timeout) throws InterruptedException {
        return waitForChange(taskId, cursor, -1L, timeout);
    }

    public TaskView waitForChange(String taskId, long cursor, long changeSequence, Duration timeout) throws InterruptedException {
        if (jdbcStore != null) {
            var deadline = System.nanoTime() + timeout.toNanos();
            while (true) {
                var task = jdbcStore.find(taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
                if (changedAfter(task, changeSequence) || task.outputBytes() > cursor || TaskStatus.terminal(task.status())) {
                    return new TaskView(task);
                }
                var remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return new TaskView(task);
                }
                // LISTEN/NOTIFY is the normal wake path.  Do not add a
                // fixed-interval row poll: if the notification is lost, the
                // bounded deadline returns the current projection and the
                // next explicit task_wait can observe it.  The in-memory
                // condition is retained only for protocol tests and the
                // no-PostgreSQL development adapter.
                if (taskChanges != null) {
                    // Validate before allocating a per-task waiter state,
                    // then close the find/version race with one second
                    // authoritative read. This prevents arbitrary task IDs
                    // from becoming unbounded memory keys and prevents a
                    // notification between the two reads from being lost.
                    var observed = taskChanges.version(taskId);
                    try {
                        task = jdbcStore.find(taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
                        if (changedAfter(task, changeSequence) || task.outputBytes() > cursor || TaskStatus.terminal(task.status())) {
                            return new TaskView(task);
                        }
                        remaining = deadline - System.nanoTime();
                        if (remaining <= 0) return new TaskView(task);
                        taskChanges.awaitChange(taskId, observed, remaining);
                    } finally {
                        taskChanges.release(taskId);
                    }
                } else {
                    var observed = localChangeSequence.get();
                    awaitLocalChange(observed, remaining);
                }
            }
        }
        var deadline = System.nanoTime() + timeout.toNanos();
        lock.lockInterruptibly();
        try {
            while (true) {
                var task = required(taskId);
                if (changedAfter(task, changeSequence) || task.outputBytes() > cursor || TaskStatus.terminal(task.status())) {
                    return new TaskView(task);
                }
                var remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return new TaskView(task);
                }
                changed.awaitNanos(remaining);
            }
        } finally {
            lock.unlock();
        }
    }

    /** Waits only for a terminal result, used by short-lived desktop actions. */
    public TaskView waitForTerminal(String taskId, Duration timeout) throws InterruptedException {
        var deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            var task = find(taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
            if (TaskStatus.terminal(task.status())) {
                return new TaskView(task);
            }
            var remaining = deadline - System.nanoTime();
            if (remaining <= 0) return new TaskView(task);
            if (jdbcStore != null && taskChanges != null) {
                var observedExternal = taskChanges.version(taskId);
                try {
                    task = find(taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
                    if (TaskStatus.terminal(task.status())) return new TaskView(task);
                    remaining = deadline - System.nanoTime();
                    if (remaining <= 0) return new TaskView(task);
                    taskChanges.awaitChange(taskId, observedExternal, remaining);
                } finally {
                    taskChanges.release(taskId);
                }
            } else {
                var observedLocal = localChangeSequence.get();
                awaitLocalChange(observedLocal, remaining);
            }
        }
    }

    private void awaitLocalChange(long observed, long timeoutNanos) throws InterruptedException {
        if (timeoutNanos <= 0) return;
        lock.lockInterruptibly();
        try {
            var deadline = System.nanoTime() + timeoutNanos;
            while (localChangeSequence.get() == observed) {
                var remaining = deadline - System.nanoTime();
                if (remaining <= 0) return;
                changed.awaitNanos(remaining);
            }
        } finally {
            lock.unlock();
        }
    }

    private TaskState required(String taskId) {
        var task = tasks.get(taskId == null ? "" : taskId.trim());
        if (task == null) {
            throw new IllegalArgumentException("task not found");
        }
        return task;
    }

    private static void assertMachine(TaskState task, String machineId) {
        if (machineId == null || !task.machineId().equals(machineId.trim())) {
            throw new SecurityException("task does not belong to this machine");
        }
    }

    private static boolean changedAfter(TaskView task, long changeSequence) {
        return changeSequence >= 0 && task.changeSequence() > changeSequence;
    }

    private static boolean changedAfter(TaskState task, long changeSequence) {
        return changeSequence >= 0 && task.changeSequence() > changeSequence;
    }

    /**
     * Publish metadata for a streamed Agent-to-Web transfer on the compact
     * task projection as well.  The payload itself remains in ArtifactStore;
     * this method only makes the task list/detail views aware of the file.
     * JDBC updates run in the caller's transaction, while memory mode keeps
     * the same lock discipline as the rest of the in-memory task state.
     */
    void recordTransferArtifact(String machineId, String taskId, String mimeType, long bytes, String sha256) {
        if (taskId == null || taskId.isBlank() || machineId == null || machineId.isBlank()) {
            throw new IllegalArgumentException("transfer artifact task and machine are required");
        }
        if (bytes < 0 || bytes > ArtifactStore.MAX_STREAM_BYTES) {
            throw new IllegalArgumentException("transfer artifact size is outside the allowed range");
        }
        var safeMime = mimeType == null || mimeType.isBlank()
                ? "application/octet-stream" : mimeType.trim().toLowerCase(java.util.Locale.ROOT);
        if (safeMime.length() > 128 || safeMime.indexOf('\r') >= 0 || safeMime.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("transfer artifact mime type is invalid");
        }
        if (sha256 == null || !sha256.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("transfer artifact sha256 is invalid");
        }
        if (jdbcStore != null) {
            jdbcStore.recordTransferArtifact(machineId.trim(), taskId.trim(), safeMime, bytes, sha256.toLowerCase(java.util.Locale.ROOT));
            return;
        }
        lock.lock();
        try {
            var task = required(taskId.trim());
            assertMachine(task, machineId.trim());
            task.artifactBytes(bytes);
            task.artifactMime(safeMime);
            task.artifactSha256(sha256.toLowerCase(java.util.Locale.ROOT));
            task.artifactData(new byte[0]);
            signalChanged(task.id());
            signalOutputChanged(task.id());
        } finally {
            lock.unlock();
        }
    }

    private static void assertOwner(TaskState task, TaskOrigin origin) {
        if (task == null || origin == null || !java.util.Objects.equals(task.origin().principalId(), origin.principalId())) {
            throw new SecurityException("task is not visible to this MCP principal");
        }
    }

    private void releaseQuota(TaskState task) {
        if (quota != null && task != null && TaskStatus.terminal(task.status())) {
            quota.releaseTask(task.origin(), task.id());
        }
    }

    private boolean laneBusy(TaskState candidate) {
        return tasks.values().stream()
                .filter(value -> !value.id().equals(candidate.id()))
                .filter(value -> value.laneKey().equals(candidate.laneKey()))
                .filter(value -> sameLaneHost(candidate, value))
                .anyMatch(value -> (TaskStatus.DISPATCHING.equals(value.status())
                        || TaskStatus.RUNNING.equals(value.status())
                        || TaskStatus.CANCEL_REQUESTED.equals(value.status()))
                        && laneConflicts(candidate.command().contract(), valueContract(value)));
    }

    private static boolean sameLaneHost(TaskState candidate, TaskState active) {
        var contract = valueContract(candidate);
        if (!ExecutionLaneKey.crossesMachineBoundary(contract)) {
            return active.machineId().equals(candidate.machineId());
        }
        var activeContract = valueContract(active);
        return activeContract != null
                && java.util.Objects.equals(contract == null ? null : contract.hostId(), activeContract.hostId());
    }

    private static ExecutionContract valueContract(TaskState task) {
        return task == null || task.command() == null ? null : task.command().contract();
    }

    private static boolean laneConflicts(ExecutionContract candidate, ExecutionContract active) {
        var candidateMode = candidate == null || candidate.laneMode() == null ? LaneMode.WRITE : candidate.laneMode();
        var activeMode = active == null || active.laneMode() == null ? LaneMode.WRITE : active.laneMode();
        return !(candidateMode == LaneMode.READ && activeMode == LaneMode.READ);
    }

    private static ExecutionContract buildContract(CreateTaskRequest request, MachineView machine,
                                                    TaskCommand original, String capability,
                                                    String taskId, Instant createdAt, TaskOrigin origin) {
        var supplied = original.contract();
        var mode = request.scopeMode() != null ? request.scopeMode()
                : supplied != null ? supplied.scopeMode()
                : (!request.worktreeId().isBlank() ? ScopeMode.WORKTREE
                : !request.projectId().isBlank() ? ScopeMode.PROJECT
                : ScopeMode.fromWireValue(machine.scopeMode()));
        var projectId = firstNonBlank(request.projectId(), supplied == null ? null : supplied.projectId());
        var worktreeId = firstNonBlank(request.worktreeId(), supplied == null ? null : supplied.worktreeId());
        var scopeRoot = firstNonBlank(request.scopeRoot(), supplied == null ? null : supplied.scopeRoot());
        if (scopeRoot == null && mode.bounded()
                && mode != ScopeMode.PROJECT && mode != ScopeMode.WORKTREE) {
            scopeRoot = machine.workspaceRoot();
        }
        // The MCP transport session is the safest default correlation value.
        // A caller-supplied session_id remains useful for admin clients,
        // but it never overrides the authenticated connection owner.
        var explicitSessionId = firstNonBlank(request.sessionId(), supplied == null ? null : supplied.sessionId());
        var connectionId = origin == null ? null : origin.connectionId();
        var sessionId = explicitSessionId;
        if (sessionId == null && connectionId != null && !connectionId.isBlank()) {
            // Preserve the configured connection id used by internal and
            // PostgreSQL integration callers.  Authenticated MCP callers get
            // a machine/scope fingerprint so one connection can safely span
            // several independent execution contexts.
            if (origin == null || origin.isConfigured()) {
                sessionId = connectionId.trim();
            } else {
                var sessionFingerprint = connectionId + "\u0000" + machine.id() + "\u0000"
                        + modeFingerprint(request, supplied, mode, projectId, worktreeId, scopeRoot, capability);
                sessionId = "session_" + sha256(sessionFingerprint).substring(0, 32);
            }
        }
        if (sessionId == null) {
            sessionId = request.idempotencyKey().isBlank()
                    ? "session_" + taskId.substring(Math.max(0, taskId.length() - 24))
                    : "session_" + sha256(request.machineId() + ":" + request.idempotencyKey()).substring(0, 32);
        }
        var risk = firstNonBlank(request.risk(), supplied == null ? null : supplied.risk());
        if (risk == null) risk = "low";
        // The execution contract is issued by Center, not trusted caller
        // input.  A nested Admin payload may carry a syntactically valid
        // contract-shaped object, but it must never enlarge the Center's
        // outer output/artifact/process ceilings.  Agent configuration may
        // narrow these values again; it can never widen them.
        var budget = boundedBudget(supplied == null ? new ExecutionContract.Budget(
                Math.max(0, original.timeoutSeconds()), MAX_OUTPUT_BYTES, MAX_ARTIFACT_BYTES,
                MAX_CHILD_PROCESSES) : supplied.budget(), machine.runtime());
        if (supplied != null && (!machine.id().equals(supplied.machineId()) || !machine.hostId().equals(supplied.hostId()))) {
            throw new SecurityException("execution contract identity does not match the selected machine");
        }
        if (mode == ScopeMode.UNRESTRICTED && machine.scopeMode() != null
                && ScopeMode.fromWireValue(machine.scopeMode()).bounded()) {
            throw new SecurityException("unrestricted task exceeds the Agent's configured scope");
        }
        if (mode.bounded()) {
            if (scopeRoot == null || scopeRoot.isBlank()) {
                throw new IllegalArgumentException("bounded task scope_root is required");
            }
            // A project/worktree root must itself remain inside a bounded
            // machine workspace. An unrestricted Agent may explicitly accept
            // a narrower contract without an outer lexical restriction.
            var machineMode = ScopeMode.fromWireValue(machine.scopeMode());
            if (machineMode.bounded()) {
                WorkspacePolicy.validateRemote(machineMode, machine.os(), machine.workspaceRoot(),
                        machine.workspaceRoot(), scopeRoot);
            }
            WorkspacePolicy.validateRemote(mode, machine.os(), scopeRoot, scopeRoot, original.cwd());
            if (original.desktop() != null) {
                WorkspacePolicy.validateRemote(mode, machine.os(), scopeRoot, scopeRoot, original.desktop().cwd());
            }
        }
        var maximumExpiry = createdAt.plus(DEFAULT_CONTRACT_LIFETIME);
        var requestedExpiry = supplied != null && supplied.expiresAt() != null
                ? supplied.expiresAt() : maximumExpiry;
        var expiresAt = requestedExpiry.isAfter(maximumExpiry) ? maximumExpiry : requestedExpiry;
        var workspacePolicy = request.workspacePolicy() != null ? request.workspacePolicy()
                : supplied != null && supplied.workspacePolicy() != null ? supplied.workspacePolicy()
                : defaultWorkspacePolicy(mode);
        var inferredLaneMode = defaultLaneMode(original);
        if (request.readOnlyLaneHint()) {
            if (original.kind() != TaskKind.COMMAND || request.projectId().isBlank()
                    || (mode != ScopeMode.PROJECT && mode != ScopeMode.WORKTREE)) {
                throw new SecurityException("read-only lane hint is not valid for this task");
            }
            inferredLaneMode = LaneMode.READ;
        }
        var requestedLaneMode = request.laneMode() != null ? request.laneMode()
                : supplied != null && supplied.laneMode() != null ? supplied.laneMode() : inferredLaneMode;
        // A caller may make a task more exclusive, but must not label an
        // arbitrary shell or a mutating transfer as READ merely to bypass the
        // scheduler's write fence.
        if (inferredLaneMode == LaneMode.EXCLUSIVE) {
            requestedLaneMode = LaneMode.EXCLUSIVE;
        } else if (inferredLaneMode == LaneMode.WRITE && requestedLaneMode == LaneMode.READ) {
            throw new SecurityException("task cannot be relaxed to a read-only execution lane");
        }
        var laneMode = requestedLaneMode;
        if (workspacePolicy == WorkspacePolicyMode.HOST && mode != ScopeMode.UNRESTRICTED) {
            throw new IllegalArgumentException("workspace_policy=host requires explicit scope_mode=unrestricted");
        }
        if (workspacePolicy == WorkspacePolicyMode.ISOLATED && mode == ScopeMode.UNRESTRICTED) {
            throw new IllegalArgumentException("workspace_policy=isolated requires a bounded scope");
        }
        if (workspacePolicy == WorkspacePolicyMode.ISOLATED
                && mode != ScopeMode.WORKTREE) {
            throw new IllegalArgumentException("workspace_policy=isolated requires an explicit worktree scope");
        }
        var contract = new ExecutionContract(machine.id(), machine.hostId(), mode, projectId, worktreeId,
                scopeRoot, workspacePolicy, laneMode, sessionId, capability, budget, expiresAt,
                request.idempotencyKey(), risk, request.elevationRequired(), null);
        if (contract.expired(createdAt)) throw new IllegalArgumentException("execution contract expires before task creation");
        return contract;
    }

    private static WorkspacePolicyMode defaultWorkspacePolicy(ScopeMode mode) {
        if (mode == ScopeMode.UNRESTRICTED) return WorkspacePolicyMode.HOST;
        if (mode == ScopeMode.WORKTREE) return WorkspacePolicyMode.ISOLATED;
        return WorkspacePolicyMode.SHARED_SERIAL;
    }

    private static String modeFingerprint(CreateTaskRequest request, ExecutionContract supplied,
                                          ScopeMode mode, String projectId, String worktreeId,
                                          String scopeRoot, String capability) {
        var policy = request.workspacePolicy() != null ? request.workspacePolicy()
                : supplied != null && supplied.workspacePolicy() != null ? supplied.workspacePolicy()
                : defaultWorkspacePolicy(mode);
        var lane = request.laneMode() != null ? request.laneMode()
                : supplied != null && supplied.laneMode() != null ? supplied.laneMode()
                : defaultLaneMode(request.command());
        return mode.wireValue() + "\u0000" + valueOrEmpty(projectId) + "\u0000"
                + valueOrEmpty(worktreeId) + "\u0000" + valueOrEmpty(scopeRoot)
                + "\u0000" + policy.wireValue() + "\u0000" + lane.wireValue()
                + "\u0000" + valueOrEmpty(capability);
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private Integer currentAttempt(String machineId, String taskId) {
        if (jdbcStore != null) return jdbcStore.currentAttempt(machineId, taskId);
        lock.lock();
        try {
            var task = required(taskId);
            assertMachine(task, machineId);
            return task.attempt();
        } finally {
            lock.unlock();
        }
    }

    private static LaneMode defaultLaneMode(TaskCommand command) {
        if (command == null) return LaneMode.WRITE;
        return switch (command.kind()) {
            case DESKTOP, BROWSER -> LaneMode.EXCLUSIVE;
            case FILE_TRANSFER -> command.fileTransfer() != null && command.fileTransfer().agentToWeb()
                    ? LaneMode.READ : LaneMode.WRITE;
            case COMMAND -> {
                // Arbitrary shell commands are treated as writes. Project
                // service Git read operations opt into READ explicitly after
                // their operation has been parsed and authorized.
                yield LaneMode.WRITE;
            }
        };
    }

    /**
     * Keep a Center-issued contract no wider than both the platform hard
     * ceiling and the last runtime descriptor advertised by the selected
     * Agent.  The Agent repeats the check locally, but carrying the narrower
     * values in the durable task makes the boundary visible after a retry or
     * Center restart.  A zero runtime limit means "not configured" and does
     * not turn an otherwise durable task into a timed contract.
     */
    private static ExecutionContract.Budget boundedBudget(ExecutionContract.Budget value,
                                                            com.prodigalgal.remoteconnectmcp.protocol.AgentRuntimeDescriptor runtime) {
        if (value == null) return ExecutionContract.Budget.defaults();
        var descriptor = runtime == null
                ? com.prodigalgal.remoteconnectmcp.protocol.AgentRuntimeDescriptor.defaults() : runtime;
        var duration = minPositive(value.maxDurationSeconds(), descriptor.maxTaskDurationSeconds());
        var rss = minPositive(value.maxRssBytes(), descriptor.maxRssBytes());
        var cpu = minPositive(value.maxCpuSeconds(), descriptor.maxCpuSeconds());
        return new ExecutionContract.Budget(
                Math.toIntExact(Math.min(duration, (long) ProtocolValidation.MAX_TIMEOUT_SECONDS)),
                Math.min(value.maxOutputBytes(), Math.min(MAX_OUTPUT_BYTES, descriptor.maxOutputBytes())),
                Math.min(value.maxArtifactBytes(), MAX_ARTIFACT_BYTES),
                Math.min(value.maxChildProcesses(), Math.min(MAX_CHILD_PROCESSES, descriptor.maxChildProcesses())),
                rss, cpu);
    }

    private static long minPositive(long requested, long outer) {
        if (requested <= 0 || outer <= 0) return Math.max(0L, requested);
        return Math.min(requested, outer);
    }

    private static void assertAttempt(TaskState task, Integer attempt) {
        if (attempt == null || attempt < 1) throw new SecurityException("task dispatch attempt is required");
        if (task.attempt() != attempt) {
            throw new SecurityException("stale task dispatch attempt");
        }
    }

    /** Total task count used by bounded, paginated console projections. */
    public int totalCount() {
        if (jdbcStore != null) return Math.toIntExact(jdbcStore.taskCount());
        lock.lock();
        try {
            return tasks.size();
        } finally {
            lock.unlock();
        }
    }

    private void audit(String eventType, String actor, String machineId, String taskId,
                       TaskCommand command, String outcome, String detail) {
        if (audit == null) return;
        var contract = command == null ? null : command.contract();
        audit.record(eventType, actor, machineId, taskId,
                contract == null ? null : contract.scopeMode().wireValue(),
                contract == null ? null : contract.risk(), outcome, detail);
    }

    private void audit(String eventType, String actor, String machineId, TaskView view,
                       String outcome, String detail) {
        if (audit == null || view == null) return;
        audit.record(eventType, actor, machineId, view.id(), view.scopeMode(), view.risk(), outcome, detail);
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) return primary.trim();
        if (fallback != null && !fallback.isBlank()) return fallback.trim();
        return null;
    }

    /** Remove credential-shaped environment entries before durable storage. */
    private static Map<String, String> sanitizeEnvironment(Map<String, String> environment) {
        if (environment == null || environment.isEmpty()) return Map.of();
        var safe = new java.util.LinkedHashMap<String, String>();
        environment.forEach((key, value) -> {
            if (key == null || value == null) return;
            var upper = key.toUpperCase(java.util.Locale.ROOT);
            if (upper.contains("TOKEN") || upper.contains("PASSWORD") || upper.contains("PASSWD")
                    || upper.contains("SECRET") || upper.contains("COOKIE") || upper.contains("AUTHORIZATION")
                    || upper.contains("API_KEY") || upper.contains("PRIVATE_KEY") || upper.contains("CREDENTIAL")) {
                return;
            }
            safe.put(key, value);
        });
        return Map.copyOf(safe);
    }

    private List<String> recoverExpiredLeases(Instant now) {
        var changed = new ArrayList<String>();
        tasks.values().stream()
                .filter(task -> TaskStatus.DISPATCHING.equals(task.status()) && task.leaseUntil() != null && !now.isBefore(task.leaseUntil()))
                .forEach(task -> {
                    task.status(TaskStatus.QUEUED);
                    task.leaseUntil(null);
                    if (quota != null) quota.markTaskQueued(task.origin(), task.id());
                    changed.add(task.id());
                });
        tasks.values().stream()
                .filter(task -> TaskStatus.RUNNING.equals(task.status()) && task.leaseUntil() != null && !now.isBefore(task.leaseUntil()))
                .forEach(task -> {
                    task.leaseUntil(null);
                    if (task.command().timeoutSeconds() <= 0) {
                        // A durable task can be reclaimed by the same Agent
                        // when its next poll carries the recovered task ID.
                        task.status(TaskStatus.QUEUED);
                        if (quota != null) quota.markTaskQueued(task.origin(), task.id());
                    } else {
                        // Timed processes are attached to the old Agent and
                        // cannot be safely replayed after its lease expires.
                        task.status(TaskStatus.FAILED);
                        task.error("agent lease expired before timed command completed");
                        task.finishedAt(now);
                        releaseQuota(task);
                    }
                    changed.add(task.id());
                });
        return List.copyOf(changed);
    }

    private void renewRunningLeases(String machineId, PollRequest request, Instant now) {
        if (request == null || request.runningTaskIds() == null) return;
        for (var taskId : request.runningTaskIds()) {
            var task = tasks.get(taskId);
            if (task == null || !machineId.equals(task.machineId()) || TaskStatus.terminal(task.status())) continue;
            if (TaskStatus.DISPATCHING.equals(task.status())
                    || (TaskStatus.QUEUED.equals(task.status()) && task.command().timeoutSeconds() <= 0)) {
                task.status(TaskStatus.RUNNING);
                if (quota != null) quota.markTaskActive(task.origin(), task.id());
                if (task.startedAt() == null) task.startedAt(now);
            }
            if (TaskStatus.RUNNING.equals(task.status())) task.leaseUntil(now.plus(LEASE_DURATION));
        }
    }

    private void signalChanged() {
        signalChanged(null);
    }

    private void signalChanged(String taskId) {
        // Condition.signalAll() is only legal while holding its associated
        // lock.  The in-memory adapter already calls this method while the
        // lock is held, but the PostgreSQL adapter invokes it after its own
        // transaction has committed.  ReentrantLock makes this helper safe in
        // both paths and prevents JDBC task updates from leaking
        // IllegalMonitorStateException to the Agent/MCP request.
        lock.lock();
        try {
            if (jdbcStore == null && taskId != null && !taskId.isBlank()) {
                var task = tasks.get(taskId.trim());
                if (task != null) task.bumpChangeSequence();
            }
            localChangeSequence.incrementAndGet();
            changed.signalAll();
        } finally {
            lock.unlock();
        }
        if (taskChanges != null && taskId != null && !taskId.isBlank()) {
            taskChanges.signal(taskId);
        }
    }

    /**
     * Output/artifact changes wake task-local waiters without advancing the
     * admin control-plane cursor. This keeps a high-throughput command from
     * forcing every console replica to refresh its full task projection.
     */
    private void signalOutputChanged(String taskId) {
        lock.lock();
        try {
            if (jdbcStore == null && taskId != null && !taskId.isBlank()) {
                var task = tasks.get(taskId.trim());
                if (task != null) task.bumpChangeSequence();
            }
            localChangeSequence.incrementAndGet();
            changed.signalAll();
        } finally {
            lock.unlock();
        }
        if (taskChanges != null && taskId != null && !taskId.isBlank()) {
            taskChanges.signalTaskOnly(taskId);
        }
    }

    private void signalWake(String machineId) {
        if (wakes == null) return;
        wakes.signal(machineId);
    }

    private static String requiredCapability(TaskCommand command, TaskKind kind) {
        if (command.requiredCapability() != null && !command.requiredCapability().isBlank()) {
            var requested = command.requiredCapability().trim().toLowerCase(java.util.Locale.ROOT);
            if (kind == TaskKind.DESKTOP && !AgentCapability.DESKTOP.wireValue().equals(requested)) {
                throw new IllegalArgumentException("desktop tasks require the desktop capability");
            }
            if (kind == TaskKind.BROWSER && !AgentCapability.BROWSER.wireValue().equals(requested)) {
                throw new IllegalArgumentException("browser tasks require the browser capability");
            }
            if (kind == TaskKind.FILE_TRANSFER && !AgentCapability.FILE_TRANSFER.wireValue().equals(requested)) {
                throw new IllegalArgumentException("file transfer tasks require the file_transfer capability");
            }
            if (kind == TaskKind.COMMAND
                    && !AgentCapability.COMMAND.wireValue().equals(requested)
                    && !AgentCapability.DURABLE_TASKS.wireValue().equals(requested)) {
                throw new IllegalArgumentException("command tasks require command or durable_tasks capability");
            }
            return requested;
        }
        return switch (kind) {
            case DESKTOP -> AgentCapability.DESKTOP.wireValue();
            case BROWSER -> AgentCapability.BROWSER.wireValue();
            case FILE_TRANSFER -> AgentCapability.FILE_TRANSFER.wireValue();
            case COMMAND -> AgentCapability.COMMAND.wireValue();
        };
    }

    private static String idempotencyKey(String machineId, String principalId, String key) {
        return machineId + "\u0000" + principalId + "\u0000" + key;
    }

    private static boolean sameCommand(TaskCommand left, TaskCommand right) {
        return left.kind() == right.kind()
                && java.util.Objects.equals(left.requiredCapability(), right.requiredCapability())
                && java.util.Objects.equals(left.command(), right.command())
                && java.util.Objects.equals(left.cwd(), right.cwd())
                && java.util.Objects.equals(left.env(), right.env())
                && left.timeoutSeconds() == right.timeoutSeconds()
                && java.util.Objects.equals(left.desktop(), right.desktop())
                && java.util.Objects.equals(left.fileTransfer(), right.fileTransfer())
                && (left.contract() == null ? right.contract() == null : left.contract().sameIntent(right.contract()));
    }

    private static boolean validStatus(String status) {
        return TaskStatus.DISPATCHING.equals(status) || TaskStatus.RUNNING.equals(status)
                || TaskStatus.COMPLETED.equals(status) || TaskStatus.FAILED.equals(status) || TaskStatus.CANCELED.equals(status);
    }

    private static boolean allowedTransition(String current, String next) {
        // Agent delivery is retried after a timeout. A request may have been
        // committed by Center even when its response was lost, so repeating
        // the same state must be harmless and idempotent.
        if (java.util.Objects.equals(current, next)) {
            return true;
        }
        if (TaskStatus.QUEUED.equals(current)) {
            return TaskStatus.CANCELED.equals(next) || TaskStatus.DISPATCHING.equals(next);
        }
        if (TaskStatus.DISPATCHING.equals(current)) {
            return TaskStatus.RUNNING.equals(next) || TaskStatus.FAILED.equals(next) || TaskStatus.CANCELED.equals(next);
        }
        if (TaskStatus.RUNNING.equals(current) || TaskStatus.CANCEL_REQUESTED.equals(current)) {
            return TaskStatus.COMPLETED.equals(next) || TaskStatus.FAILED.equals(next) || TaskStatus.CANCELED.equals(next);
        }
        return false;
    }

    private static String compactError(String error) {
        var value = SensitiveValueRedactor.redact(error.trim());
        return value.length() <= 4096 ? value : value.substring(0, 4096);
    }

    private static String sha256(byte[] data) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /** Explicit, bounded retention operation; never runs on a fixed timer. */
    public ArtifactGcResult gcArtifacts(int retentionDays, int limit) {
        if (retentionDays < 1 || retentionDays > 3650) {
            throw new IllegalArgumentException("retentionDays must be between 1 and 3650");
        }
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("limit must be between 1 and 500");
        }
        var cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        var result = jdbcStore != null ? jdbcStore.gcArtifacts(cutoff, limit)
                : new ArtifactGcResult(0, 0, 0, 0L);
        artifactGcBytes.accumulateAndGet(Math.max(0L, result.objectBytes()), TaskService::saturatingAdd);
        return result;
    }

    /** Explicit, bounded task metadata/output retention operation. */
    public TaskRetentionGcResult gcExpiredTasks(int metadataRetentionDays, int outputRetentionDays, int limit) {
        if (metadataRetentionDays < 1 || metadataRetentionDays > 3650
                || outputRetentionDays < 1 || outputRetentionDays > 3650) {
            throw new IllegalArgumentException("task retention days must be between 1 and 3650");
        }
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("limit must be between 1 and 500");
        }
        var now = Instant.now();
        if (jdbcStore != null) {
            // The absolute expiry columns are the retention source of truth;
            // do not subtract the policy a second time and accidentally keep
            // every task for twice its configured lifetime.
            return jdbcStore.gcExpiredTasks(now, now, limit);
        }
        lock.lock();
        try {
            var outputRows = 0;
            var metadataRows = 0;
            var outputCutoff = now;
            var metadataCutoff = now;
            for (var task : new ArrayList<>(tasks.values())) {
                if (outputRows < limit && TaskStatus.terminal(task.status()) && !task.pinned()
                        && task.outputExpiresAt() != null && !task.outputExpiresAt().isAfter(outputCutoff)
                        && task.outputBytes() > 0) {
                    task.output().reset();
                    task.outputBytes(0);
                    task.outputTruncated(true);
                    task.bumpChangeSequence();
                    outputRows++;
                }
                if (metadataRows < limit && TaskStatus.terminal(task.status()) && !task.pinned()
                        && task.metadataExpiresAt() != null && !task.metadataExpiresAt().isAfter(metadataCutoff)) {
                    tasks.remove(task.id());
                    if (task.idempotencyKey() != null && !task.idempotencyKey().isBlank()) {
                        idempotency.remove(idempotencyKey(task.machineId(), task.origin().principalId(), task.idempotencyKey()));
                    }
                    metadataRows++;
                }
            }
            if (outputRows > 0 || metadataRows > 0) signalChanged();
            return new TaskRetentionGcResult(metadataRows, outputRows);
        } finally {
            lock.unlock();
        }
    }

    /** Bytes reclaimed by explicit artifact retention calls in this process. */
    public long artifactGcBytes() {
        return artifactGcBytes.get();
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static long saturatingAdd(long left, long right) {
        if (right <= 0 || Long.MAX_VALUE - left < right) return Long.MAX_VALUE;
        return left + right;
    }

    public record ArtifactData(String mimeType, String sha256, byte[] data) {
        public ArtifactData {
            data = data == null ? new byte[0] : data.clone();
        }
    }

    public record ArtifactGcResult(int metadataRows, int objectFiles, int deleteFailures, long objectBytes) {
        /** Construction overload when the object byte total is not reported. */
        public ArtifactGcResult(int metadataRows, int objectFiles, int deleteFailures) {
            this(metadataRows, objectFiles, deleteFailures, 0L);
        }
    }

    public record TaskRetentionGcResult(int metadataRows, int outputRows) {
    }
}
