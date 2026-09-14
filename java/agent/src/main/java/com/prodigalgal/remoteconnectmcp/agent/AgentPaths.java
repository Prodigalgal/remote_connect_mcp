package com.prodigalgal.remoteconnectmcp.agent;

import com.prodigalgal.remoteconnectmcp.protocol.ScopeMode;
import com.prodigalgal.remoteconnectmcp.protocol.ExecutionContract;
import com.prodigalgal.remoteconnectmcp.protocol.TaskCommand;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/** Shared cwd resolution for command, desktop and browser execution. */
final class AgentPaths {
    private AgentPaths() {
    }

    static Path resolveCwd(AgentConfig config, String requested) throws IOException {
        var raw = requested == null || requested.isBlank() ? config.defaultCwd() : requested.trim();
        final Path input;
        try {
            input = Path.of(raw);
        } catch (RuntimeException exception) {
            throw new IOException("cwd is not a valid path", exception);
        }
        var candidate = input.isAbsolute() ? input : Path.of(config.defaultCwd()).resolve(input);
        candidate = candidate.toAbsolutePath().normalize();
        if (config.scopeMode().bounded()) {
            var root = Path.of(config.workspaceRoot()).toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) {
                throw new IOException("configured workspace root is not a directory: " + root);
            }
            var rootReal = root.toRealPath();
            candidate = resolveThroughExistingParents(candidate);
            if (!candidate.startsWith(rootReal)) {
                throw new IOException("cwd is outside the configured workspace");
            }
        }
        if (!Files.isDirectory(candidate)) {
            throw new IOException("cwd is not a directory: " + candidate);
        }
        return candidate;
    }

    /** Resolve and locally re-check a Center-issued task execution contract. */
    static Path resolveCwd(AgentConfig config, String machineId, TaskCommand task, String requested) throws IOException {
        var contract = task == null ? null : task.contract();
        if (contract == null) return resolveCwd(config, requested);
        validateContract(config, machineId, task, contract);

        var bounded = contract.scopeMode().bounded();
        var base = bounded ? Path.of(contract.scopeRoot()) : Path.of(config.defaultCwd());
        var raw = requested == null || requested.isBlank()
                ? (task.cwd() == null || task.cwd().isBlank() ? base.toString() : task.cwd().trim())
                : requested.trim();
        final Path input;
        try {
            input = Path.of(raw);
        } catch (RuntimeException exception) {
            throw new IOException("cwd is not a valid path", exception);
        }
        var candidate = input.isAbsolute() ? input : base.resolve(input);
        candidate = candidate.toAbsolutePath().normalize();
        if (!bounded) {
            if (!Files.isDirectory(candidate)) throw new IOException("cwd is not a directory: " + candidate);
            return candidate;
        }
        var root = base.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) throw new IOException("configured execution scope root is not a directory: " + root);
        var rootReal = root.toRealPath();
        candidate = resolveThroughExistingParents(candidate);
        if (!candidate.startsWith(rootReal)) throw new IOException("cwd is outside the execution contract scope");
        if (!Files.isDirectory(candidate)) throw new IOException("cwd is not a directory: " + candidate);
        return candidate;
    }

    private static void validateContract(AgentConfig config, String machineId, TaskCommand task,
                                         ExecutionContract contract) throws IOException {
        if (machineId == null || !machineId.equals(contract.machineId())) {
            throw new IOException("execution contract machine identity does not match this Agent");
        }
        if (!config.hostId().equals(contract.hostId())) {
            throw new IOException("execution contract host identity does not match this Agent");
        }
        if (!contract.capability().equals(task.requiredCapability())
                || !config.capabilities().contains(contract.capability())) {
            throw new IOException("execution contract capability is not available");
        }
        if (contract.expired(Instant.now())) throw new IOException("execution contract has expired");
        if (config.scopeMode().bounded() && !contract.scopeMode().bounded()) {
            throw new IOException("execution contract exceeds the Agent scope");
        }
        if (config.scopeMode().bounded() && contract.scopeMode().bounded()) {
            // Validate the contract root against the machine-level root before
            // resolving the requested cwd. Real-path checks below close the
            // symlink/junction gap on the target filesystem.
            var machineRoot = Path.of(config.workspaceRoot()).toAbsolutePath().normalize();
            var requestedRoot = Path.of(contract.scopeRoot()).toAbsolutePath().normalize();
            if (!Files.isDirectory(machineRoot)) throw new IOException("configured workspace root is not a directory");
            var machineReal = machineRoot.toRealPath();
            var contractReal = resolveThroughExistingParents(requestedRoot);
            if (!contractReal.startsWith(machineReal)) {
                throw new IOException("execution contract root is outside the Agent scope");
            }
        }
        if (contract.scopeMode() == ScopeMode.PROJECT && contract.projectId() == null) {
            throw new IOException("project execution contract is missing project identity");
        }
        if (contract.scopeMode() == ScopeMode.WORKTREE
                && (contract.projectId() == null || contract.worktreeId() == null)) {
            throw new IOException("worktree execution contract is missing identity");
        }
    }

    /** Resolves symlinks/junctions in the existing prefix of a path. */
    private static Path resolveThroughExistingParents(Path candidate) throws IOException {
        var missing = new ArrayDeque<Path>();
        var existing = candidate;
        while (!Files.exists(existing)) {
            var name = existing.getFileName();
            if (name == null) {
                throw new IOException("cwd has no existing parent");
            }
            missing.addFirst(name);
            existing = existing.getParent();
            if (existing == null) {
                throw new IOException("cwd has no existing parent");
            }
        }
        var resolved = existing.toRealPath();
        for (var name : missing) {
            resolved = resolved.resolve(name);
        }
        return resolved.normalize();
    }
}
