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

    /** Resolve a file-transfer endpoint and repeat the Center contract check locally. */
    static Path resolveFilePath(AgentConfig config, String machineId, TaskCommand task,
                                String requested, boolean source) throws IOException {
        if (task == null || task.fileTransfer() == null) throw new IOException("file transfer action is missing");
        var contract = task.contract();
        if (contract == null) throw new IOException("file transfer execution contract is missing");
        validateContract(config, machineId, task, contract);
        var bounded = contract.scopeMode().bounded();
        var base = (bounded ? Path.of(contract.scopeRoot()) : Path.of(config.defaultCwd())).toAbsolutePath().normalize();
        var raw = requested == null || requested.isBlank() ? null : requested.trim();
        if (raw == null) throw new IOException(source ? "source_path is required" : "destination_path is required");
        final Path input;
        try {
            input = Path.of(raw);
        } catch (RuntimeException exception) {
            throw new IOException("file path is not valid", exception);
        }
        var candidate = (input.isAbsolute() ? input : base.resolve(input)).toAbsolutePath().normalize();
        if (!bounded) {
            if (source) {
                if (!Files.isRegularFile(candidate)) throw new IOException("source file is not a regular file: " + candidate);
                return candidate.toRealPath();
            }
            var parent = candidate.getParent();
            if (parent == null || !Files.isDirectory(parent)) throw new IOException("destination parent is not a directory: " + parent);
            return candidate;
        }
        if (!Files.isDirectory(base)) throw new IOException("configured execution scope root is not a directory: " + base);
        var rootReal = base.toRealPath();
        if (source) {
            var real = candidate.toRealPath();
            if (!real.startsWith(rootReal) || !Files.isRegularFile(real)) {
                throw new IOException("source file is outside the execution contract scope");
            }
            return real;
        }
        var parent = candidate.getParent();
        if (parent == null) throw new IOException("destination has no parent");
        var parentReal = resolveThroughExistingParents(parent);
        if (!parentReal.startsWith(rootReal) || !Files.isDirectory(parentReal)) {
            throw new IOException("destination parent is outside the execution contract scope");
        }
        return parentReal.resolve(candidate.getFileName()).normalize();
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
