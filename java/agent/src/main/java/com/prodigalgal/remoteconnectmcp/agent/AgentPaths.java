package com.prodigalgal.remoteconnectmcp.agent;

import com.prodigalgal.remoteconnectmcp.protocol.ExecutionContract;
import com.prodigalgal.remoteconnectmcp.protocol.TaskCommand;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

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

        // A task cwd is a working-directory hint, never an authorization
        // boundary. The Agent intentionally runs with the host user's/service
        // permissions and may address any existing directory on the machine.
        var base = Path.of(config.defaultCwd());
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
        var base = Path.of(config.defaultCwd()).toAbsolutePath().normalize();
        var raw = requested == null || requested.isBlank() ? null : requested.trim();
        if (raw == null) throw new IOException(source ? "source_path is required" : "destination_path is required");
        final Path input;
        try {
            input = Path.of(raw);
        } catch (RuntimeException exception) {
            throw new IOException("file path is not valid", exception);
        }
        var candidate = (input.isAbsolute() ? input : base.resolve(input)).toAbsolutePath().normalize();
        if (source) {
            var real = candidate.toRealPath();
            if (!Files.isRegularFile(real)) throw new IOException("source file is not a regular file: " + candidate);
            return real;
        }
        var parent = candidate.getParent();
        if (parent == null) throw new IOException("destination has no parent");
        if (!Files.isDirectory(parent)) throw new IOException("destination parent is not a directory: " + parent);
        if (Files.isDirectory(candidate)) throw new IOException("destination must not be a directory");
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
    }
}
