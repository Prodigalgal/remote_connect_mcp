package com.prodigalgal.remoteconnectmcp.protocol;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record TaskCommand(
        String id,
        TaskKind kind,
        String requiredCapability,
        String command,
        String cwd,
        Map<String, String> env,
        int timeoutSeconds,
        DesktopAction desktop,
        Instant createdAt,
        ExecutionContract contract,
        int attempt,
        FileTransferAction fileTransfer) {

    /** Local construction overload; wire payloads use the canonical record shape. */
    public TaskCommand(String id, TaskKind kind, String requiredCapability, String command, String cwd,
                       Map<String, String> env, int timeoutSeconds, DesktopAction desktop, Instant createdAt) {
        this(id, kind, requiredCapability, command, cwd, env, timeoutSeconds, desktop, createdAt, null, 0, null);
    }

    /** Local construction overload with an execution contract. */
    public TaskCommand(String id, TaskKind kind, String requiredCapability, String command, String cwd,
                       Map<String, String> env, int timeoutSeconds, DesktopAction desktop, Instant createdAt,
                       ExecutionContract contract) {
        this(id, kind, requiredCapability, command, cwd, env, timeoutSeconds, desktop, createdAt, contract, 0, null);
    }

    /** Local construction overload with a dispatch attempt fence. */
    public TaskCommand(String id, TaskKind kind, String requiredCapability, String command, String cwd,
                       Map<String, String> env, int timeoutSeconds, DesktopAction desktop, Instant createdAt,
                       ExecutionContract contract, int attempt) {
        this(id, kind, requiredCapability, command, cwd, env, timeoutSeconds, desktop, createdAt, contract, attempt, null);
    }

    /** Local construction overload for a Center-issued file transfer task. */
    public TaskCommand(String id, TaskKind kind, String requiredCapability, String command, String cwd,
                       Map<String, String> env, int timeoutSeconds, DesktopAction desktop, Instant createdAt,
                       ExecutionContract contract, FileTransferAction fileTransfer) {
        this(id, kind, requiredCapability, command, cwd, env, timeoutSeconds, desktop, createdAt, contract, 0, fileTransfer);
    }

    public TaskCommand {
        kind = kind == null ? TaskKind.COMMAND : kind;
        env = env == null ? Map.of() : Map.copyOf(env);
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
        if (attempt < 0) throw new IllegalArgumentException("attempt must be non-negative");
    }

    public TaskCommand withContract(ExecutionContract value) {
        return new TaskCommand(id, kind, requiredCapability, command, cwd, env, timeoutSeconds, desktop, createdAt, value, attempt, fileTransfer);
    }

    /** Center sets the dispatch attempt on the leased wire command. */
    public TaskCommand withAttempt(int value) {
        return new TaskCommand(id, kind, requiredCapability, command, cwd, env, timeoutSeconds, desktop, createdAt, contract, value, fileTransfer);
    }

    public record DesktopAction(String operation, String executable, List<String> args, String cwd,
                                String text, Integer x, Integer y, String key,
                                Integer x2, Integer y2, Integer durationMs, Integer screen,
                                String windowTitle) {
        /** Local construction overload for operations without extended fields. */
        public DesktopAction(String operation, String executable, List<String> args, String cwd) {
            this(operation, executable, args, cwd, null, null, null, null, null, null, null, null, null);
        }

        /** Local construction overload for text and point operations. */
        public DesktopAction(String operation, String executable, List<String> args, String cwd,
                             String text, Integer x, Integer y, String key) {
            this(operation, executable, args, cwd, text, x, y, key, null, null, null, null, null);
        }

        public DesktopAction {
            args = args == null ? List.of() : List.copyOf(args);
        }
    }
}
