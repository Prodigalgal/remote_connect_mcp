package com.prodigalgal.remoteconnectmcp.center;

import com.prodigalgal.remoteconnectmcp.protocol.ScopeMode;
import com.prodigalgal.remoteconnectmcp.protocol.TaskCommand;
import com.prodigalgal.remoteconnectmcp.protocol.TaskKind;
import com.prodigalgal.remoteconnectmcp.protocol.TaskUpdateRequest;
import com.prodigalgal.remoteconnectmcp.protocol.WorkspacePolicy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Center-side project catalog and Git worktree coordinator. The process uses
 * exactly one persistence adapter: the in-memory map is a protocol-test
 * adapter, while PostgreSQL is authoritative in production. The Center stores
 * only paths and operation metadata. Git itself always runs as a normal
 * command on the target Agent, so the Agent remains authoritative for symlink
 * resolution, account permissions and the actual repository state.
 */
@Service
public final class ProjectService {
    private static final int MAX_NAME = 128;
    private static final int MAX_PATH = 2048;
    private static final int MAX_REF = 256;
    private static final int MAX_COMMIT_MESSAGE = 1024;
    private static final int WORKTREE_TIMEOUT_SECONDS = 300;

    private final AgentRegistry agents;
    private final TaskService tasks;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final TaskChangeRegistry changes;
    private final AuditService audit;
    private final Map<String, ProjectState> projects = new ConcurrentHashMap<>();
    private final Map<String, WorktreeState> worktrees = new ConcurrentHashMap<>();

    @Autowired
    public ProjectService(AgentRegistry agents, TaskService tasks,
                           ObjectProvider<JdbcTemplate> jdbcProvider,
                           ObjectProvider<TransactionTemplate> transactionProvider,
                           ObjectProvider<TaskChangeRegistry> changeProvider,
                           ObjectProvider<AuditService> auditProvider) {
        this(agents, tasks, jdbcProvider.getIfAvailable(), transactionProvider.getIfAvailable(),
                changeProvider == null ? null : changeProvider.getIfAvailable(),
                auditProvider == null ? null : auditProvider.getIfAvailable());
    }

    ProjectService(AgentRegistry agents, TaskService tasks) {
        this(agents, tasks, (JdbcTemplate) null, (TransactionTemplate) null, null, null);
    }

    ProjectService(AgentRegistry agents, TaskService tasks, JdbcTemplate jdbc, TransactionTemplate transactions) {
        this(agents, tasks, jdbc, transactions, null, null);
    }

    ProjectService(AgentRegistry agents, TaskService tasks, JdbcTemplate jdbc, TransactionTemplate transactions,
                   TaskChangeRegistry changes) {
        this(agents, tasks, jdbc, transactions, changes, null);
    }

    ProjectService(AgentRegistry agents, TaskService tasks, JdbcTemplate jdbc, TransactionTemplate transactions,
                   TaskChangeRegistry changes, AuditService audit) {
        this.agents = agents;
        this.tasks = tasks;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.changes = changes;
        this.audit = audit;
    }

    public ProjectView register(ProjectRegistrationRequest request) {
        if (request == null) throw new IllegalArgumentException("project request is required");
        var machineId = requiredText(request.machineId(), "machine_id", 128);
        var name = requiredText(request.name(), "name", MAX_NAME);
        var machine = agents.findMachine(machineId, Instant.now())
                .orElseThrow(() -> new IllegalArgumentException("machine not found"));
        var root = safePath(request.rootPath(), "root_path");
        var repository = request.repositoryPath() == null || request.repositoryPath().isBlank()
                ? root : safePath(request.repositoryPath(), "repository_path");
        var ref = normalizeRef(request.defaultRef());
        validateProjectBoundary(machine, root, repository);

        if (jdbc == null) {
            synchronized (projects) {
                var existing = projects.values().stream()
                        .filter(value -> value.machineId.equals(machineId) && value.name.equals(name))
                        .findFirst().orElse(null);
                if (existing != null) {
                    if (!existing.rootPath.equals(root) || !existing.repositoryPath.equals(repository)
                            || !existing.defaultRef.equals(ref)) {
                        throw new IllegalArgumentException("project name is already registered with different paths");
                    }
                    return view(existing);
                }
                var now = Instant.now();
                var state = new ProjectState(projectId(), machineId, name, root, repository, ref, now, now);
                projects.put(state.id, state);
                var result = view(state);
                signalChange();
                audit("project.register", "admin", machineId, state.id, "accepted", "name_registered");
                return result;
            }
        }

        var inserted = new AtomicBoolean();
        java.util.function.Supplier<ProjectView> persist = () -> {
            var existing = jdbc.query("""
                    SELECT project_id, agent_id, name, root_path, repository_path, default_ref, created_at, updated_at
                      FROM rcm_project WHERE agent_id = ? AND name = ? FOR UPDATE
                    """, ps -> { ps.setString(1, machineId); ps.setString(2, name); },
                    (rs, rowNum) -> projectState(rs));
            if (!existing.isEmpty()) {
                var value = existing.get(0);
                if (!value.rootPath.equals(root) || !value.repositoryPath.equals(repository)
                        || !value.defaultRef.equals(ref)) {
                    throw new IllegalArgumentException("project name is already registered with different paths");
                }
                return view(value);
            }
            var id = projectId();
            var now = Instant.now();
            jdbc.update("""
                    INSERT INTO rcm_project(project_id, agent_id, name, root_path, repository_path, default_ref, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, machineId, name, root, repository, ref,
                    java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
            inserted.set(true);
            return new ProjectView(id, machineId, name, root, repository, ref, now, now, List.of());
        };
        var result = transactions == null ? persist.get() : transactions.execute(status -> persist.get());
        if (inserted.get()) {
            signalChange();
            audit("project.register", "admin", machineId, result.id(), "accepted", "name_registered");
        }
        return result;
    }

    public ProjectView find(String projectId) {
        var id = requiredText(projectId, "project_id", 180);
        if (jdbc == null) {
            var project = projects.get(id);
            if (project == null) throw new IllegalArgumentException("project not found");
            return view(project);
        }
        return jdbc.query("""
                SELECT project_id, agent_id, name, root_path, repository_path, default_ref, created_at, updated_at
                  FROM rcm_project WHERE project_id = ?
                """, ps -> ps.setString(1, id), (rs, rowNum) -> projectView(rs))
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("project not found"));
    }

    public List<ProjectView> list(String machineId, int offset, int limit) {
        if (offset < 0) throw new IllegalArgumentException("offset must be non-negative");
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("limit must be between 1 and 200");
        if (jdbc == null) {
            var values = projects.values().stream()
                    .filter(value -> machineId == null || machineId.isBlank() || value.machineId.equals(machineId.trim()))
                    .sorted(Comparator.comparing((ProjectState value) -> value.name, String.CASE_INSENSITIVE_ORDER))
                    .toList();
            if (offset >= values.size()) return List.of();
            return values.subList(offset, Math.min(values.size(), offset + limit)).stream().map(this::view).toList();
        }
        var sql = """
                SELECT project_id, agent_id, name, root_path, repository_path, default_ref, created_at, updated_at
                  FROM rcm_project %s ORDER BY name, project_id OFFSET ? LIMIT ?
                """.formatted(machineId == null || machineId.isBlank() ? "" : "WHERE agent_id = ?");
        return List.copyOf(jdbc.query(sql, ps -> {
            var index = 1;
            if (machineId != null && !machineId.isBlank()) ps.setString(index++, machineId.trim());
            ps.setInt(index++, offset);
            ps.setInt(index, limit);
        }, (rs, rowNum) -> projectView(rs)));
    }

    /** Total project count for console pagination; no repository data is read. */
    public int count(String machineId) {
        if (jdbc == null) {
            var normalized = machineId == null ? "" : machineId.trim();
            return Math.toIntExact(projects.values().stream()
                    .filter(value -> normalized.isBlank() || value.machineId.equals(normalized))
                    .count());
        }
        var normalized = machineId == null ? "" : machineId.trim();
        var value = normalized.isBlank()
                ? jdbc.queryForObject("SELECT COUNT(*) FROM rcm_project", Long.class)
                : jdbc.queryForObject("SELECT COUNT(*) FROM rcm_project WHERE agent_id = ?", Long.class, normalized);
        return value == null ? 0 : Math.toIntExact(value);
    }

    /**
     * Remove a project registration without deleting user files.  Worktrees
     * must have been explicitly removed first and no task may still refer to
     * the project; this prevents a late Agent result from targeting a deleted
     * scope.  The target checkout remains untouched because Center never owns
     * repository contents.
     */
    public ProjectView remove(String projectId) {
        var project = findState(projectId);
        if (jdbc == null) {
            synchronized (projects) {
                synchronized (worktrees) {
                    assertRemovable(project.id);
                    var result = view(project);
                    projects.remove(project.id);
                    worktrees.values().removeIf(value -> value.projectId.equals(project.id));
                    signalChange();
                    audit("project.remove", "admin", project.machineId, project.id, "accepted", "registration_removed");
                    return result;
                }
            }
        }
        var operation = () -> {
            // Serialize project deletion with concurrent task/worktree
            // registrations.  Without a row lock, a task could resolve the
            // project just before this DELETE and leave an execution contract
            // pointing at a project that no longer exists.
            var lockedProject = jdbc.query("""
                    SELECT project_id, agent_id, name, root_path, repository_path, default_ref, created_at, updated_at
                      FROM rcm_project WHERE project_id = ? FOR UPDATE
                    """, ps -> ps.setString(1, project.id), (rs, rowNum) -> projectState(rs))
                    .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("project not found"));
            var activeTasks = jdbc.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM rcm_task
                         WHERE execution_contract ->> 'project_id' = ?
                           AND status NOT IN (?, ?, ?)
                    )
                    """, Boolean.class, project.id, TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.CANCELED);
            if (Boolean.TRUE.equals(activeTasks)) {
                throw new IllegalArgumentException("project has active tasks; cancel or finish them first");
            }
            var activeWorktrees = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM rcm_project_worktree
                     WHERE project_id = ? AND status <> 'removed'
                    """, Long.class, project.id);
            if (activeWorktrees != null && activeWorktrees > 0) {
                throw new IllegalArgumentException("project has active worktrees; remove them first");
            }
            var result = view(lockedProject);
            if (jdbc.update("DELETE FROM rcm_project WHERE project_id = ?", lockedProject.id) != 1) {
                throw new IllegalArgumentException("project not found");
            }
            return result;
        };
        var result = transactions == null ? operation.get() : transactions.execute(status -> operation.get());
        signalChange();
        audit("project.remove", "admin", project.machineId, project.id, "accepted", "registration_removed");
        return result;
    }

    /**
     * Queue a deterministic `git worktree add` operation.  The returned path
     * is always below the registered project root; callers cannot choose an
     * arbitrary output directory.
     */
    public WorktreeView createWorktree(String projectId, ProjectWorktreeRequest request) {
        var project = findState(projectId);
        var ref = normalizeRef(request == null ? null : request.ref());
        var idempotency = normalizeIdempotency(request == null ? null : request.idempotencyKey());
        if (jdbc == null) {
            synchronized (worktrees) {
                var existing = findMemoryByIdempotency(project.id, idempotency);
                if (existing != null) {
                    if (!existing.ref.equals(ref)) throw new IllegalArgumentException("idempotency key is already used with a different ref");
                    return refresh(existing);
                }
                var sameRef = worktrees.values().stream()
                        .filter(value -> value.projectId.equals(project.id) && value.ref.equals(ref)
                                && !WorktreeStatus.REMOVED.equals(value.status))
                        .findFirst().orElse(null);
                if (sameRef != null) throw new IllegalArgumentException("a worktree for this ref already exists");
                var id = worktreeId();
                var state = new WorktreeState(id, project.id, ref,
                        worktreePath(project.rootPath, project.machineId, id), "create", "queued", null,
                        Instant.now(), Instant.now(), idempotency);
                worktrees.put(state.id, state);
                queueOperation(project, state, false);
                var result = refresh(state);
                signalChange();
                audit("worktree.create", "admin", project.machineId, project.id, "accepted", "worktree=" + state.id);
                return result;
            }
        }

        var existing = findJdbcByIdempotency(project.id, idempotency);
        if (existing != null) {
            if (!existing.ref.equals(ref)) throw new IllegalArgumentException("idempotency key is already used with a different ref");
            return refresh(existing);
        }
        var sameRef = jdbc.query("""
                SELECT worktree_id, project_id, ref, path, operation, status, task_id, created_at, updated_at, idempotency_key
                  FROM rcm_project_worktree
                 WHERE project_id = ? AND ref = ? AND status <> 'removed'
                 ORDER BY created_at DESC LIMIT 1
                """, ps -> { ps.setString(1, project.id); ps.setString(2, ref); }, (rs, rowNum) -> worktreeState(rs))
                .stream().findFirst().orElse(null);
        if (sameRef != null) throw new IllegalArgumentException("a worktree for this ref already exists");
        var id = worktreeId();
        var state = new WorktreeState(id, project.id, ref,
                worktreePath(project.rootPath, project.machineId, id), "create", "queued", null,
                Instant.now(), Instant.now(), idempotency);
        try {
            jdbc.update("""
                    INSERT INTO rcm_project_worktree(worktree_id, project_id, ref, path, operation, status, task_id, idempotency_key, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, state.id, state.projectId, state.ref, state.path, state.operation, state.status,
                    null, nullIfBlank(state.idempotencyKey), java.sql.Timestamp.from(state.createdAt), java.sql.Timestamp.from(state.updatedAt));
        } catch (DuplicateKeyException race) {
            // Two Center replicas may pass the metadata-only pre-check at the
            // same time.  Let PostgreSQL's unique idempotency/ref indexes be
            // the final arbiter, then turn the race into the same deterministic
            // response as a serialized request.
            var raced = findJdbcByIdempotency(project.id, idempotency);
            if (raced != null) {
                if (!raced.ref.equals(ref)) {
                    throw new IllegalArgumentException("idempotency key is already used with a different ref", race);
                }
                return refresh(raced);
            }
            throw new IllegalArgumentException("a worktree for this ref already exists", race);
        }
        queueOperation(project, state, true);
        var result = refresh(state);
        signalChange();
        audit("worktree.create", "admin", project.machineId, project.id, "accepted", "worktree=" + state.id);
        return result;
    }

    public WorktreeView removeWorktree(String projectId, String worktreeId, String idempotencyKey) {
        var project = findState(projectId);
        var state = findWorktree(project.id, worktreeId);
        var requestedIdempotency = normalizeIdempotency(idempotencyKey);
        if ("remove".equals(state.operation) && !requestedIdempotency.isBlank()
                && requestedIdempotency.equals(state.idempotencyKey)) {
            return refresh(state);
        }
        var current = refresh(state);
        if (WorktreeStatus.REMOVED.equals(current.status())) throw new IllegalArgumentException("worktree is already removed");
        if (!"create".equals(state.operation) || !WorktreeStatus.READY.equals(current.status())) {
            throw new IllegalArgumentException("worktree is not ready to remove");
        }
        var idempotency = requestedIdempotency;
        state.operation = "remove";
        state.status = "queued";
        state.taskId = null;
        state.idempotencyKey = idempotency;
        state.updatedAt = Instant.now();
        persistWorktree(state);
        queueOperation(project, state, jdbc != null);
        var result = refresh(state);
        signalChange();
        audit("worktree.remove", "admin", project.machineId, project.id, "accepted", "worktree=" + state.id);
        return result;
    }

    private void signalChange() {
        if (changes != null) changes.signalGlobal();
    }

    private void audit(String eventType, String actor, String agentId, String taskId,
                       String outcome, String detail) {
        if (audit == null) return;
        var risk = eventType.startsWith("git.") ? "high" : "medium";
        audit.record(eventType, actor, agentId, taskId, null, risk, outcome, detail);
    }

    private void assertRemovable(String projectId) {
        if (tasks.hasActiveProjectTasks(projectId)) {
            throw new IllegalArgumentException("project has active tasks; cancel or finish them first");
        }
        var active = worktrees.values().stream()
                .filter(value -> value.projectId.equals(projectId))
                .anyMatch(value -> !WorktreeStatus.REMOVED.equals(refresh(value).status()));
        if (active) throw new IllegalArgumentException("project has active worktrees; remove them first");
    }

    /** Resolve a project/worktree target for an admin task before enqueueing. */
    public String resolveCwd(String machineId, String projectId, String worktreeId, String requestedCwd) {
        var project = findState(projectId);
        if (!project.machineId.equals(requiredText(machineId, "machine_id", 128))) {
            throw new SecurityException("project does not belong to this machine");
        }
        var machine = agents.findMachine(machineId, Instant.now()).orElseThrow(() -> new IllegalArgumentException("machine not found"));
        var base = project.rootPath;
        if (worktreeId != null && !worktreeId.isBlank()) {
            var worktree = findWorktree(project.id, worktreeId);
            var current = refresh(worktree);
            if (!"create".equals(worktree.operation) || !WorktreeStatus.READY.equals(current.status())) {
                throw new IllegalArgumentException("worktree is not ready");
            }
            base = worktree.path;
        }
        var requested = requestedCwd == null ? "" : requestedCwd.trim();
        var target = requested.isBlank() ? base : (isAbsolute(requested, isWindows(machine)) ? requested : join(base, requested, isWindows(machine)));
        if (!within(base, target, isWindows(machine))) {
            throw new IllegalArgumentException("working directory is outside the selected project/worktree");
        }
        // Reapply the Agent's advertised workspace policy as an additional
        // Center-side check.  The Agent repeats this check after resolving
        // real paths and remains the final authority.
        WorkspacePolicy.validateRemote(ScopeMode.fromWireValue(machine.scopeMode()), machine.os(),
                machine.workspaceRoot(), machine.defaultCwd(), target);
        return target;
    }

    /** Resolve the immutable root carried by a project/worktree execution contract. */
    public String resolveScopeRoot(String machineId, String projectId, String worktreeId) {
        var project = findState(projectId);
        if (!project.machineId.equals(requiredText(machineId, "machine_id", 128))) {
            throw new SecurityException("project does not belong to this machine");
        }
        var machine = agents.findMachine(machineId, Instant.now())
                .orElseThrow(() -> new IllegalArgumentException("machine not found"));
        var base = project.rootPath;
        if (worktreeId != null && !worktreeId.isBlank()) {
            var worktree = findWorktree(project.id, worktreeId);
            var current = refresh(worktree);
            if (!"create".equals(worktree.operation) || !WorktreeStatus.READY.equals(current.status())) {
                throw new IllegalArgumentException("worktree is not ready");
            }
            base = worktree.path;
        }
        WorkspacePolicy.validateRemote(ScopeMode.fromWireValue(machine.scopeMode()), machine.os(),
                machine.workspaceRoot(), machine.workspaceRoot(), base);
        return base;
    }

    /**
     * Queue an explicit Git read or mutation. Repository contents never pass
     * through Center: the Agent executes the quoted command under the same
     * project/worktree contract used by ordinary tasks. Mutating operations
     * require a caller idempotency key so a ChatGPT retry cannot create a
     * second commit or merge.
     */
    public TaskView gitOperation(String projectId, String operation, ProjectGitOperationRequest request) {
        var project = findState(projectId);
        var machine = agents.findMachine(project.machineId, Instant.now())
                .orElseThrow(() -> new IllegalArgumentException("machine not found"));
        var input = request == null ? new ProjectGitOperationRequest("", "", "", "", "") : request;
        var normalizedOperation = normalizeGitOperation(operation);
        var worktreeId = input.worktreeId() == null ? "" : input.worktreeId().trim();
        var scopeMode = worktreeId.isBlank() ? ScopeMode.PROJECT : ScopeMode.WORKTREE;
        var target = project.repositoryPath;
        var scopeRoot = project.rootPath;
        if (!worktreeId.isBlank()) {
            var worktree = findWorktree(project.id, worktreeId);
            var current = refresh(worktree);
            if (!"create".equals(worktree.operation) || !WorktreeStatus.READY.equals(current.status())) {
                throw new IllegalArgumentException("worktree is not ready");
            }
            target = worktree.path;
            scopeRoot = worktree.path;
        }
        // Reuse the normal Center boundary checks before constructing the
        // command, then let the Agent perform its real-path check again.
        target = resolveCwd(project.machineId, project.id, worktreeId, target);
        var command = buildGitCommand(machine, target, normalizedOperation, input);
        var mutating = "commit".equals(normalizedOperation) || "merge".equals(normalizedOperation)
                || "merge_abort".equals(normalizedOperation);
        var clientKey = normalizeIdempotency(input.idempotencyKey());
        if (mutating && clientKey.isBlank()) {
            throw new IllegalArgumentException("mutating Git operations require idempotency_key");
        }
        var taskKey = clientKey.isBlank() ? "" : "rcm-git:" + project.id + ":" + normalizedOperation + ":" + clientKey;
        var timeout = WORKTREE_TIMEOUT_SECONDS;
        var task = new TaskCommand("", TaskKind.COMMAND, "command", command, target,
                Map.of("GIT_TERMINAL_PROMPT", "0", "GIT_EDITOR", "true"), timeout, null, Instant.now());
        var session = "project-git-" + normalizedOperation + "-" + UUID.randomUUID().toString().replace("-", "");
        var result = tasks.create(new CreateTaskRequest(project.machineId, task, taskKey, project.id, worktreeId,
                scopeMode, scopeRoot, session, mutating ? "high" : "low", false));
        audit("git." + normalizedOperation, "admin", project.machineId, project.id, "accepted",
                "worktree=" + (worktreeId.isBlank() ? "project" : worktreeId));
        return result;
    }

    private static String buildGitCommand(MachineView machine, String target, String operation,
                                          ProjectGitOperationRequest request) {
        var git = "git -C " + quote(machine, target);
        return switch (operation) {
            case "status" -> git + " status --short --branch --no-renames";
            case "diff" -> {
                var mode = request.mode() == null || request.mode().isBlank() ? "stat" : request.mode().trim().toLowerCase(Locale.ROOT);
                if (!("stat".equals(mode) || "patch".equals(mode))) {
                    throw new IllegalArgumentException("Git diff mode must be stat or patch");
                }
                var args = "stat".equals(mode) ? " --stat" : " --binary";
                var ref = request.ref() == null || request.ref().isBlank() ? "" : normalizeRef(request.ref());
                yield git + " diff --no-ext-diff" + args + (ref.isBlank() ? "" : " " + gitArgument(machine, ref)) + " --";
            }
            case "log" -> git + " log --oneline --decorate -n 50";
            case "commit" -> git + " add --all && " + git + " commit -m " + gitArgument(machine, requiredCommitMessage(request.message()));
            case "merge" -> git + " merge --no-edit --no-ff " + gitArgument(machine, requiredRef(request.ref()));
            case "merge_abort" -> git + " merge --abort";
            default -> throw new IllegalArgumentException("unsupported Git operation: " + operation);
        };
    }

    private static String normalizeGitOperation(String value) {
        var operation = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!List.of("status", "diff", "log", "commit", "merge", "merge_abort").contains(operation)) {
            throw new IllegalArgumentException("unsupported Git operation: " + operation);
        }
        return operation;
    }

    private static String requiredCommitMessage(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("commit message is required");
        var message = value.trim();
        if (message.length() > MAX_COMMIT_MESSAGE || message.indexOf('\u0000') >= 0
                || message.indexOf('\r') >= 0 || message.indexOf('\n') >= 0
                || message.indexOf('"') >= 0 || message.indexOf('%') >= 0
                || message.indexOf('&') >= 0 || message.indexOf('|') >= 0
                || message.indexOf('<') >= 0 || message.indexOf('>') >= 0
                || message.indexOf('^') >= 0 || message.indexOf(';') >= 0) {
            throw new IllegalArgumentException("commit message contains unsupported shell characters");
        }
        return message;
    }

    private static String requiredRef(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("merge ref is required");
        return normalizeRef(value);
    }

    private static String gitArgument(MachineView machine, String value) {
        var normalized = requiredText(value, "git argument", MAX_COMMIT_MESSAGE);
        return isWindows(machine) ? quoteWindowsArgument(normalized) : quotePosix(normalized);
    }

    private static String quoteWindowsArgument(String value) {
        validateWindowsShellValue(value, "Git argument");
        return "\"" + value + "\"";
    }

    private void queueOperation(ProjectState project, WorktreeState state, boolean persisted) {
        var machine = agents.findMachine(project.machineId, Instant.now())
                .orElseThrow(() -> new IllegalArgumentException("machine not found"));
        var commandText = state.operation.equals("remove")
                ? gitRemove(machine, project.repositoryPath, state.path)
                : gitCreate(machine, project.repositoryPath, state.path, state.ref);
        try {
            var task = tasks.create(new CreateTaskRequest(project.machineId,
                    new TaskCommand("", TaskKind.COMMAND, "command", commandText, project.rootPath,
                            Map.of(), WORKTREE_TIMEOUT_SECONDS, null, Instant.now()),
                    "rcm-worktree:" + state.id + ":" + state.operation,
                    project.id, null, ScopeMode.PROJECT, project.rootPath, "", "low", false));
            state.taskId = task.id();
            state.status = "queued";
            state.updatedAt = Instant.now();
            if (persisted) persistWorktree(state);
        } catch (RuntimeException failure) {
            state.status = WorktreeStatus.FAILED;
            state.updatedAt = Instant.now();
            if (persisted) persistWorktree(state);
            throw failure;
        }
    }

    private WorktreeView refresh(WorktreeState state) {
        if (state.taskId != null && !state.taskId.isBlank()) {
            var task = tasks.find(state.taskId).orElse(null);
            if (task != null) {
                var next = switch (task.status()) {
                    case TaskStatus.COMPLETED -> "create".equals(state.operation) ? WorktreeStatus.READY : WorktreeStatus.REMOVED;
                    case TaskStatus.FAILED, TaskStatus.CANCELED -> WorktreeStatus.FAILED;
                    case TaskStatus.RUNNING, TaskStatus.DISPATCHING, TaskStatus.CANCEL_REQUESTED -> "running";
                    default -> "queued";
                };
                state.status = next;
                state.updatedAt = Instant.now();
                if (jdbc != null) persistWorktree(state);
            }
        }
        return view(state);
    }

    private ProjectState findState(String projectId) {
        var id = requiredText(projectId, "project_id", 180);
        if (jdbc == null) {
            var value = projects.get(id);
            if (value == null) throw new IllegalArgumentException("project not found");
            return value;
        }
        return jdbc.query("""
                SELECT project_id, agent_id, name, root_path, repository_path, default_ref, created_at, updated_at
                  FROM rcm_project WHERE project_id = ?
                """, ps -> ps.setString(1, id), (rs, rowNum) -> projectState(rs))
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("project not found"));
    }

    private WorktreeState findWorktree(String projectId, String worktreeId) {
        var id = requiredText(worktreeId, "worktree_id", 180);
        if (jdbc == null) {
            var value = worktrees.get(id);
            if (value == null || !value.projectId.equals(projectId)) throw new IllegalArgumentException("worktree not found");
            return value;
        }
        return jdbc.query("""
                SELECT worktree_id, project_id, ref, path, operation, status, task_id, created_at, updated_at, idempotency_key
                  FROM rcm_project_worktree WHERE worktree_id = ? AND project_id = ?
                """, ps -> { ps.setString(1, id); ps.setString(2, projectId); }, (rs, rowNum) -> worktreeState(rs))
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("worktree not found"));
    }

    private ProjectView view(ProjectState state) {
        var values = new ArrayList<WorktreeView>();
        if (jdbc == null) {
            worktrees.values().stream().filter(value -> value.projectId.equals(state.id))
                    .sorted(Comparator.comparing(value -> value.createdAt))
                    .forEach(value -> values.add(refresh(value)));
        } else {
            jdbc.query("""
                    SELECT worktree_id, project_id, ref, path, operation, status, task_id, created_at, updated_at, idempotency_key
                      FROM rcm_project_worktree WHERE project_id = ? ORDER BY created_at, worktree_id
                    """, ps -> ps.setString(1, state.id), (rs, rowNum) -> worktreeState(rs))
                    .forEach(value -> values.add(refresh(value)));
        }
        return new ProjectView(state.id, state.machineId, state.name, state.rootPath, state.repositoryPath,
                state.defaultRef, state.createdAt, state.updatedAt, values);
    }

    private ProjectView projectView(java.sql.ResultSet rs) throws java.sql.SQLException {
        return view(projectState(rs));
    }

    private void persistWorktree(WorktreeState state) {
        if (jdbc == null) return;
        jdbc.update("""
                UPDATE rcm_project_worktree SET operation = ?, status = ?, task_id = ?, idempotency_key = ?, updated_at = ?
                 WHERE worktree_id = ?
                """, state.operation, state.status, state.taskId, nullIfBlank(state.idempotencyKey),
                java.sql.Timestamp.from(state.updatedAt), state.id);
    }

    private WorktreeState findMemoryByIdempotency(String projectId, String key) {
        if (key.isBlank()) return null;
        return worktrees.values().stream()
                .filter(value -> value.projectId.equals(projectId) && key.equals(value.idempotencyKey))
                .findFirst().orElse(null);
    }

    private WorktreeState findJdbcByIdempotency(String projectId, String key) {
        if (key.isBlank()) return null;
        return jdbc.query("""
                SELECT worktree_id, project_id, ref, path, operation, status, task_id, created_at, updated_at, idempotency_key
                  FROM rcm_project_worktree WHERE project_id = ? AND idempotency_key = ?
                """, ps -> { ps.setString(1, projectId); ps.setString(2, key); }, (rs, rowNum) -> worktreeState(rs))
                .stream().findFirst().orElse(null);
    }

    private static ProjectState projectState(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ProjectState(rs.getString("project_id"), rs.getString("agent_id"), rs.getString("name"),
                rs.getString("root_path"), rs.getString("repository_path"), rs.getString("default_ref"),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static WorktreeState worktreeState(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new WorktreeState(rs.getString("worktree_id"), rs.getString("project_id"), rs.getString("ref"),
                rs.getString("path"), rs.getString("operation"), rs.getString("status"), rs.getString("task_id"),
                instant(rs, "created_at"), instant(rs, "updated_at"), rs.getString("idempotency_key"));
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? Instant.EPOCH : value.toInstant();
    }

    private static String gitCreate(MachineView machine, String repository, String path, String ref) {
        var root = join(path, "..", isWindows(machine));
        var mkdir = isWindows(machine)
                ? "if not exist " + quoteWindows(root) + " mkdir " + quoteWindows(root)
                : "mkdir -p " + quotePosix(root);
        return mkdir + (isWindows(machine) ? " && " : " && ") + "git -C "
                + quote(machine, repository) + " worktree add --detach " + quote(machine, path) + " " + quote(machine, ref);
    }

    private static String gitRemove(MachineView machine, String repository, String path) {
        return "git -C " + quote(machine, repository) + " worktree remove --force " + quote(machine, path);
    }

    private static String quote(MachineView machine, String value) {
        return isWindows(machine) ? quoteWindows(value) : quotePosix(value);
    }

    private static String quotePosix(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String quoteWindows(String value) {
        validateWindowsShellValue(value, "Windows path");
        return "\"" + value + "\"";
    }

    /**
     * Values in a Windows command are passed through {@code cmd.exe} by the
     * Agent.  Quoting protects most separators, but percent expansion and
     * delayed expansion still happen inside quotes; reject the complete set of
     * shell metacharacters before constructing a command string.
     */
    private static void validateWindowsShellValue(String value, String field) {
        if (value == null || value.isBlank()
                || value.indexOf('\u0000') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
                || value.indexOf('"') >= 0 || value.indexOf('%') >= 0 || value.indexOf('!') >= 0
                || value.indexOf('&') >= 0 || value.indexOf('|') >= 0
                || value.indexOf('<') >= 0 || value.indexOf('>') >= 0
                || value.indexOf('^') >= 0 || value.indexOf(';') >= 0
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " contains unsupported shell characters");
        }
    }

    private static String worktreePath(String root, String machineId, String worktreeId) {
        // Machine identity is not used as a path component; the parameter is
        // retained in the signature to make it explicit that the path is
        // derived for a target Agent, not on the Center host.
        var windows = root.matches("^[A-Za-z]:.*") || root.startsWith("\\\\");
        return join(root, ".rcm-worktrees" + (windows ? "\\" : "/") + worktreeId, windows);
    }

    private static String requiredText(String value, String field, int max) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        var normalized = value.trim();
        if (normalized.length() > max || normalized.indexOf('\r') >= 0 || normalized.indexOf('\n') >= 0
                || normalized.indexOf('\u0000') >= 0 || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static String safePath(String value, String field) {
        var path = requiredText(value, field, MAX_PATH);
        if (path.contains("\"") || path.contains("'") || path.contains("%")
                || path.contains("&") || path.contains("|") || path.contains("<") || path.contains(">")
                || path.contains("^") || path.contains(";")) {
            throw new IllegalArgumentException(field + " contains unsupported shell characters");
        }
        if (!isAbsolute(path, path.matches("^[A-Za-z]:.*") || path.startsWith("\\\\"))) {
            throw new IllegalArgumentException(field + " must be absolute");
        }
        return path;
    }

    private static String normalizeRef(String value) {
        var ref = value == null || value.isBlank() ? "HEAD" : value.trim();
        if (ref.length() > MAX_REF || !ref.matches("[A-Za-z0-9][A-Za-z0-9._/@+\\-]{0,255}")
                || ref.contains("..") || ref.endsWith("/") || ref.endsWith(".")) {
            throw new IllegalArgumentException("default_ref/ref is invalid");
        }
        return ref;
    }

    private static String normalizeIdempotency(String value) {
        if (value == null || value.isBlank()) return "";
        return requiredText(value, "idempotency_key", 256);
    }

    private static void validateProjectBoundary(MachineView machine, String root, String repository) {
        var windows = isWindows(machine);
        if (!within(root, repository, windows)) throw new IllegalArgumentException("repository_path must be inside root_path");
        WorkspacePolicy.validateRemote(ScopeMode.fromWireValue(machine.scopeMode()), machine.os(),
                machine.workspaceRoot(), machine.defaultCwd(), root);
    }

    private static boolean isWindows(MachineView machine) {
        return machine != null && "windows".equalsIgnoreCase(machine.os());
    }

    private static boolean isAbsolute(String value, boolean windows) {
        if (value == null || value.isBlank()) return false;
        return value.startsWith("/") || value.startsWith("\\\\")
                || (windows && value.length() >= 3 && Character.isLetter(value.charAt(0)) && value.charAt(1) == ':'
                && (value.charAt(2) == '/' || value.charAt(2) == '\\'));
    }

    private static String join(String base, String child, boolean windows) {
        var separator = windows ? "\\" : "/";
        var left = base.replace(windows ? '/' : '\\', windows ? '\\' : '/');
        var right = child.replace(windows ? '/' : '\\', windows ? '\\' : '/');
        if (left.endsWith(separator)) return normalizePath(left + right, windows);
        return normalizePath(left + separator + right, windows);
    }

    private static String normalizePath(String value, boolean windows) {
        var raw = value.replace(windows ? '/' : '\\', windows ? '\\' : '/');
        var separator = windows ? '\\' : '/';
        var prefix = "";
        var absolute = raw.startsWith(String.valueOf(separator));
        if (windows && raw.matches("^[A-Za-z]:[\\\\/].*")) {
            prefix = raw.substring(0, 3);
            raw = raw.substring(3);
            absolute = true;
        } else if (absolute) {
            prefix = String.valueOf(separator);
            raw = raw.substring(1);
        }
        Deque<String> parts = new ArrayDeque<>();
        for (var part : raw.split(java.util.regex.Pattern.quote(String.valueOf(separator)))) {
            if (part.isBlank() || ".".equals(part)) continue;
            if ("..".equals(part)) {
                if (!parts.isEmpty() && !"..".equals(parts.peekLast())) parts.removeLast();
                else if (!absolute) parts.addLast(part);
            } else parts.addLast(part);
        }
        var body = String.join(String.valueOf(separator), parts);
        if (prefix.isEmpty()) return body;
        return body.isEmpty() ? prefix : prefix + body;
    }

    private static boolean within(String root, String candidate, boolean windows) {
        var normalizedRoot = normalizePath(root, windows);
        var normalizedCandidate = normalizePath(candidate, windows);
        if (windows) {
            normalizedRoot = normalizedRoot.toLowerCase(Locale.ROOT);
            normalizedCandidate = normalizedCandidate.toLowerCase(Locale.ROOT);
        }
        var separator = windows ? "\\" : "/";
        return normalizedCandidate.equals(normalizedRoot)
                || normalizedCandidate.startsWith(normalizedRoot.endsWith(separator) ? normalizedRoot : normalizedRoot + separator);
    }

    private static String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String projectId() {
        return "project_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String worktreeId() {
        return "worktree_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static final class ProjectState {
        private final String id;
        private final String machineId;
        private final String name;
        private final String rootPath;
        private final String repositoryPath;
        private final String defaultRef;
        private final Instant createdAt;
        private final Instant updatedAt;

        private ProjectState(String id, String machineId, String name, String rootPath, String repositoryPath,
                              String defaultRef, Instant createdAt, Instant updatedAt) {
            this.id = id;
            this.machineId = machineId;
            this.name = name;
            this.rootPath = rootPath;
            this.repositoryPath = repositoryPath;
            this.defaultRef = defaultRef;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }
    }

    private static final class WorktreeState {
        private final String id;
        private final String projectId;
        private final String ref;
        private final String path;
        private String operation;
        private String status;
        private String taskId;
        private final Instant createdAt;
        private Instant updatedAt;
        private String idempotencyKey;

        private WorktreeState(String id, String projectId, String ref, String path, String operation,
                              String status, String taskId, Instant createdAt, Instant updatedAt, String idempotencyKey) {
            this.id = id;
            this.projectId = projectId;
            this.ref = ref;
            this.path = path;
            this.operation = operation;
            this.status = status;
            this.taskId = taskId;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.idempotencyKey = idempotencyKey == null ? "" : idempotencyKey;
        }
    }

    private static final class WorktreeStatus {
        private static final String READY = "ready";
        private static final String REMOVED = "removed";
        private static final String FAILED = "failed";
    }

    private static WorktreeView view(WorktreeState state) {
        return new WorktreeView(state.id, state.projectId, state.ref, state.path, state.operation, state.status,
                state.taskId, state.createdAt, state.updatedAt);
    }
}
