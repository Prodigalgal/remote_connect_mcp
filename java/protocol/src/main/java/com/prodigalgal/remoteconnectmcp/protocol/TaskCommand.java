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
        ExecutionContract contract) {

    /** Compatibility constructor for the pre-contract wire shape. */
    public TaskCommand(String id, TaskKind kind, String requiredCapability, String command, String cwd,
                       Map<String, String> env, int timeoutSeconds, DesktopAction desktop, Instant createdAt) {
        this(id, kind, requiredCapability, command, cwd, env, timeoutSeconds, desktop, createdAt, null);
    }

    public TaskCommand {
        kind = kind == null ? TaskKind.COMMAND : kind;
        env = env == null ? Map.of() : Map.copyOf(env);
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
    }

    public TaskCommand withContract(ExecutionContract value) {
        return new TaskCommand(id, kind, requiredCapability, command, cwd, env, timeoutSeconds, desktop, createdAt, value);
    }

    public record DesktopAction(String operation, String executable, List<String> args, String cwd,
                                String text, Integer x, Integer y, String key,
                                Integer x2, Integer y2, Integer durationMs, Integer screen,
                                String windowTitle) {
        /** Compatibility constructor for launch/screenshot callers. */
        public DesktopAction(String operation, String executable, List<String> args, String cwd) {
            this(operation, executable, args, cwd, null, null, null, null, null, null, null, null, null);
        }

        /** Compatibility constructor for click/key/type callers. */
        public DesktopAction(String operation, String executable, List<String> args, String cwd,
                             String text, Integer x, Integer y, String key) {
            this(operation, executable, args, cwd, text, x, y, key, null, null, null, null, null);
        }

        public DesktopAction {
            args = args == null ? List.of() : List.copyOf(args);
        }
    }
}
