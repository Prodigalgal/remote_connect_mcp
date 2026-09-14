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
import com.prodigalgal.remoteconnectmcp.protocol.ScopeMode;
import com.prodigalgal.remoteconnectmcp.protocol.ExecutionContract;
import com.prodigalgal.remoteconnectmcp.protocol.WorkspacePolicy;
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
    public static final int MAX_OUTPUT_PAGE = 64 * 1024;
    static final Duration LEASE_DURATION = Duration.ofMinutes(2);
    static final Duration DEFAULT_CONTRACT_LIFETIME = Duration.ofDays(30);
    private final AgentRegistry agents;
    private final Map<String, TaskState> tasks = new ConcurrentHashMap<>();
    private final Map<String, String> idempotency = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final AtomicLong localChangeSequence = new AtomicLong();
    private final JdbcTaskStore jdbcStore;
    private final AgentWakeRegistry wakes;
    private final TaskChangeRegistry taskChanges;

    @Autowired
    public TaskService(AgentRegistry agents, ObjectProvider<JdbcTemplate> jdbcProvider,
                       ObjectProvider<TransactionTemplate> transactionProvider,
                       ObjectProvider<AgentWakeRegistry> wakeProvider,
                       ObjectProvider<TaskChangeRegistry> taskChangeProvider,
                       ObjectProvider<ArtifactStore> artifactProvider) {
        this.agents = agents;
        var jdbc = jdbcProvider.getIfAvailable();
        var artifactStore = artifactProvider.getIfAvailable();
        this.jdbcStore = jdbc == null ? null : new JdbcTaskStore(jdbc, transactionProvider.getIfAvailable(),
                artifactStore == null ? new InMemoryArtifactStore() : artifactStore);
        this.wakes = wakeProvider.getIfAvailable();
        this.taskChanges = taskChangeProvider.getIfAvailable();
    }

    TaskService(AgentRegistry agents) {
        this.agents = agents;
        this.jdbcStore = null;
        this.wakes = null;
        this.taskChanges = null;
    }

    /** Package-private constructor used by the PostgreSQL contract tests. */
    TaskService(AgentRegistry agents, JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.agents = agents;
        this.jdbcStore = jdbc == null ? null : new JdbcTaskStore(jdbc, transactions, new InMemoryArtifactStore());
        this.wakes = null;
        this.taskChanges = null;
    }

    public TaskView create(CreateTaskRequest request) {
        if (request == null || request.machineId().isBlank()) {
            throw new IllegalArgumentException("machineId is required");
        }
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
        var id = "task_" + UUID.randomUUID().toString().replace("-", "");
        var createdAt = Instant.now();
        var contract = buildContract(request, machine, original, capability, id, createdAt);
        var command = new TaskCommand(id, kind, capability, original.command(), original.cwd(), original.env(), original.timeoutSeconds(), original.desktop(), createdAt, contract);
        ProtocolValidation.validateTask(command);

        if (jdbcStore != null) {
            var created = jdbcStore.create(id, request.machineId(), command, request.idempotencyKey(), command.createdAt());
            signalChanged(id);
            signalWake(request.machineId());
            return created;
        }

        lock.lock();
        try {
            if (!request.idempotencyKey().isBlank()) {
                var key = idempotencyKey(request.machineId(), request.idempotencyKey());
                var existingId = idempotency.get(key);
                if (existingId != null) {
                    var existing = tasks.get(existingId);
                    if (existing != null && sameCommand(existing.command(), command)) {
                        return new TaskView(existing);
                    }
                    throw new IllegalArgumentException("idempotency key is already used with different task parameters");
                }
                idempotency.put(key, id);
            }
            var state = new TaskState(id, request.machineId(), command, request.idempotencyKey(), command.createdAt());
            tasks.put(id, state);
            signalChanged();
            var created = new TaskView(state);
            signalWake(request.machineId());
            return created;
        } finally {
            lock.unlock();
        }
    }

    public Optional<TaskState> find(String taskId) {
        if (jdbcStore != null) {
            return jdbcStore.find(taskId);
        }
        return Optional.ofNullable(tasks.get(taskId == null ? "" : taskId.trim()));
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

    public TaskView cancel(String taskId) {
        if (jdbcStore != null) {
            var view = jdbcStore.cancel(taskId);
            signalChanged(taskId);
            signalWake(view.machineId());
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
            }
            signalChanged();
            var view = new TaskView(task);
            signalWake(view.machineId());
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
                        .sorted(Comparator.comparing(TaskState::createdAt))
                        .findFirst();
                if (candidate.isPresent()) {
                    var selected = candidate.get();
                    selected.status(TaskStatus.DISPATCHING);
                    selected.attempt(selected.attempt() + 1);
                    selected.dispatchedAt(now);
                    selected.leaseUntil(now.plus(LEASE_DURATION));
                    task = selected.command();
                    signalChanged();
                }
            }
            return new PollResponse(task, cancelIds, null);
        } finally {
            lock.unlock();
        }
    }

    public TaskView updateState(String machineId, String taskId, TaskUpdateRequest update) {
        if (update == null || update.status() == null || update.status().isBlank()) {
            throw new IllegalArgumentException("status is required");
        }
        if (jdbcStore != null) {
            var view = jdbcStore.updateState(machineId, taskId, update);
            signalChanged(taskId);
            return view;
        }
        lock.lock();
        try {
            var task = required(taskId);
            assertMachine(task, machineId);
            var status = update.status().trim().toLowerCase();
            if (!validStatus(status)) {
                throw new IllegalArgumentException("unsupported task status: " + status);
            }
            if (!allowedTransition(task.status(), status)) {
                throw new IllegalArgumentException("invalid task transition: " + task.status() + " -> " + status);
            }
            task.status(status);
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
            }
            signalChanged();
            return new TaskView(task);
        } finally {
            lock.unlock();
        }
    }

    public OutputResponse appendOutput(String machineId, String taskId, long offset, byte[] data) {
        if (offset < 0 || data == null) {
            throw new IllegalArgumentException("offset and data are required");
        }
        if (jdbcStore != null) {
            var response = jdbcStore.appendOutput(machineId, taskId, offset, data);
            signalOutputChanged(taskId);
            return response;
        }
        lock.lock();
        try {
            var task = required(taskId);
            assertMachine(task, machineId);
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
            var remaining = Math.max(0, MAX_OUTPUT_BYTES - current.length);
            var appendLength = Math.min(data.length - appendFrom, remaining);
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
        var normalizedMime = normalizeMimeType(mimeType);
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("artifact mimeType and data are required");
        }
        if (data.length > 8 * 1024 * 1024) {
            throw new IllegalArgumentException("artifact exceeds 8388608 bytes");
        }
        var digest = sha256(data);
        if (sha256 == null || !digest.equalsIgnoreCase(sha256.trim())) {
            throw new IllegalArgumentException("artifact sha256 does not match data");
        }
        if (jdbcStore != null) {
            var response = jdbcStore.appendArtifact(machineId, taskId, normalizedMime, digest, data);
            signalOutputChanged(taskId);
            return response;
        }
        lock.lock();
        try {
            var task = required(taskId);
            assertMachine(task, machineId);
            if (task.artifactData() != null && task.artifactData().length > 0 && !digest.equals(task.artifactSha256())) {
                throw new IllegalArgumentException("task already has a different artifact");
            }
            task.artifactData(data);
            task.artifactBytes(data.length);
            task.artifactMime(normalizedMime);
            task.artifactSha256(digest);
            signalOutputChanged(taskId);
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

    public Optional<ArtifactData> readArtifact(String taskId) {
        if (jdbcStore != null) {
            return jdbcStore.readArtifact(taskId);
        }
        lock.lock();
        try {
            var task = required(taskId);
            var data = task.artifactData();
            return data.length == 0 ? Optional.empty() : Optional.of(new ArtifactData(task.artifactMime(), task.artifactSha256(), data));
        } finally {
            lock.unlock();
        }
    }

    public TaskView waitForChange(String taskId, long cursor, Duration timeout) throws InterruptedException {
        if (jdbcStore != null) {
            var deadline = System.nanoTime() + timeout.toNanos();
            while (true) {
                var task = jdbcStore.find(taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
                if (task.outputBytes() > cursor || TaskStatus.terminal(task.status())) {
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
                        if (task.outputBytes() > cursor || TaskStatus.terminal(task.status())) {
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
                if (task.outputBytes() > cursor || TaskStatus.terminal(task.status())) {
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

    private static ExecutionContract buildContract(CreateTaskRequest request, MachineView machine,
                                                    TaskCommand original, String capability,
                                                    String taskId, Instant createdAt) {
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
        var sessionId = firstNonBlank(request.sessionId(), supplied == null ? null : supplied.sessionId());
        if (sessionId == null) {
            sessionId = request.idempotencyKey().isBlank()
                    ? "session_" + taskId.substring(Math.max(0, taskId.length() - 24))
                    : "session_" + sha256(request.machineId() + ":" + request.idempotencyKey()).substring(0, 32);
        }
        var risk = firstNonBlank(request.risk(), supplied == null ? null : supplied.risk());
        if (risk == null) risk = "low";
        var budget = supplied == null ? new ExecutionContract.Budget(
                Math.max(0, original.timeoutSeconds()), MAX_OUTPUT_BYTES, 8L * 1024 * 1024, 32) : supplied.budget();
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
        var expiresAt = supplied != null && supplied.expiresAt() != null
                ? supplied.expiresAt() : createdAt.plus(DEFAULT_CONTRACT_LIFETIME);
        var contract = new ExecutionContract(machine.id(), machine.hostId(), mode, projectId, worktreeId,
                scopeRoot, sessionId, capability, budget, expiresAt, request.idempotencyKey(), risk,
                request.elevationRequired(), null);
        if (contract.expired(createdAt)) throw new IllegalArgumentException("execution contract expires before task creation");
        return contract;
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) return primary.trim();
        if (fallback != null && !fallback.isBlank()) return fallback.trim();
        return null;
    }

    private List<String> recoverExpiredLeases(Instant now) {
        var changed = new ArrayList<String>();
        tasks.values().stream()
                .filter(task -> TaskStatus.DISPATCHING.equals(task.status()) && task.leaseUntil() != null && !now.isBefore(task.leaseUntil()))
                .forEach(task -> {
                    task.status(TaskStatus.QUEUED);
                    task.leaseUntil(null);
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
                    } else {
                        // Timed processes are attached to the old Agent and
                        // cannot be safely replayed after its lease expires.
                        task.status(TaskStatus.FAILED);
                        task.error("agent lease expired before timed command completed");
                        task.finishedAt(now);
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
            return command.requiredCapability().trim();
        }
        return switch (kind) {
            case DESKTOP -> AgentCapability.DESKTOP.wireValue();
            case BROWSER -> AgentCapability.BROWSER.wireValue();
            case COMMAND -> AgentCapability.COMMAND.wireValue();
        };
    }

    private static String idempotencyKey(String machineId, String key) {
        return machineId + "\u0000" + key;
    }

    private static boolean sameCommand(TaskCommand left, TaskCommand right) {
        return left.kind() == right.kind()
                && java.util.Objects.equals(left.requiredCapability(), right.requiredCapability())
                && java.util.Objects.equals(left.command(), right.command())
                && java.util.Objects.equals(left.cwd(), right.cwd())
                && java.util.Objects.equals(left.env(), right.env())
                && left.timeoutSeconds() == right.timeoutSeconds()
                && java.util.Objects.equals(left.desktop(), right.desktop())
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
        var value = error.trim();
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
        if (jdbcStore != null) return jdbcStore.gcArtifacts(cutoff, limit);
        return new ArtifactGcResult(0, 0, 0);
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    public record ArtifactData(String mimeType, String sha256, byte[] data) {
        public ArtifactData {
            data = data == null ? new byte[0] : data.clone();
        }
    }

    public record ArtifactGcResult(int metadataRows, int objectFiles, int deleteFailures) {
    }
}
