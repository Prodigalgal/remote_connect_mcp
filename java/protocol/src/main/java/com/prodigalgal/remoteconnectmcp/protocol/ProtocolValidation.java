package com.prodigalgal.remoteconnectmcp.protocol;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public final class ProtocolValidation {
    public static final int MAX_COMMAND_BYTES = 256 * 1024;
    public static final int MAX_CWD_BYTES = 16 * 1024;
    public static final int MAX_ENV_ENTRIES = 256;
    public static final int MAX_ENV_KEY_BYTES = 256;
    public static final int MAX_ENV_VALUE_BYTES = 16 * 1024;
    public static final int MAX_TIMEOUT_SECONDS = 30 * 24 * 60 * 60;

    private static final Pattern ENV_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private ProtocolValidation() {
    }

    public static void validateMetadata(AgentMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        requireText(metadata.name(), "name", 512);
        requireText(metadata.hostId(), "hostId", 512);
        requireText(metadata.hostname(), "hostname", 512);
        requireText(metadata.os(), "os", 64);
        requireText(metadata.arch(), "arch", 64);
        requireText(metadata.version(), "version", 128);
        requireText(metadata.defaultCwd(), "defaultCwd", MAX_CWD_BYTES);
        if (metadata.scopeMode() != null && metadata.scopeMode().bounded()) {
            requireText(metadata.workspaceRoot(), "workspaceRoot", MAX_CWD_BYTES);
            // Reject an invalid machine policy at registration time.  Tasks
            // still receive a separate contract, but a bounded Agent must
            // never advertise a default cwd outside its own outer root.
            WorkspacePolicy.validateRemote(metadata.scopeMode(), metadata.os(),
                    metadata.workspaceRoot(), metadata.defaultCwd(), null);
        }
        if (metadata.capabilities().size() > 64) {
            throw new IllegalArgumentException("too many capabilities");
        }
        metadata.capabilities().forEach(capability -> requireText(capability, "capability", 128));
    }

    public static void validateTask(TaskCommand task) {
        Objects.requireNonNull(task, "task");
        requireText(task.id(), "id", 256);
        if (task.command() != null) {
            requireBytes(task.command(), "command", MAX_COMMAND_BYTES);
        }
        if (task.cwd() != null) {
            requireBytes(task.cwd(), "cwd", MAX_CWD_BYTES);
        }
        if (task.timeoutSeconds() < 0 || task.timeoutSeconds() > MAX_TIMEOUT_SECONDS) {
            throw new IllegalArgumentException("timeoutSeconds is outside the allowed range");
        }
        validateEnv(task.env());
        if (task.contract() != null) {
            validateContract(task.contract(), task);
        }
        if (task.kind() != TaskKind.DESKTOP && task.desktop() != null) {
            throw new IllegalArgumentException("desktop action is only valid for desktop tasks");
        }
        if (task.kind() == TaskKind.DESKTOP && !AgentCapability.DESKTOP.wireValue().equalsIgnoreCase(task.requiredCapability())) {
            throw new IllegalArgumentException("desktop tasks require the desktop capability");
        }
        if (task.kind() == TaskKind.BROWSER && !AgentCapability.BROWSER.wireValue().equalsIgnoreCase(task.requiredCapability())) {
            throw new IllegalArgumentException("browser tasks require the browser capability");
        }
        if (task.kind() == TaskKind.COMMAND && task.requiredCapability() != null
                && !task.requiredCapability().isBlank()
                && !AgentCapability.COMMAND.wireValue().equalsIgnoreCase(task.requiredCapability())
                && !AgentCapability.DURABLE_TASKS.wireValue().equalsIgnoreCase(task.requiredCapability())) {
            throw new IllegalArgumentException("command tasks require command or durable_tasks capability");
        }
        if (task.kind() == TaskKind.DESKTOP) {
            if (task.desktop() == null || task.desktop().operation() == null || task.desktop().operation().isBlank()) {
                throw new IllegalArgumentException("desktop action is required for desktop tasks");
            }
            requireText(task.desktop().operation(), "desktop operation", 64);
            var operation = task.desktop().operation().trim().toLowerCase(java.util.Locale.ROOT);
            if (!operation.equals("screenshot") && !operation.equals("screens") && !operation.equals("launch")
                    && !operation.equals("click") && !operation.equals("drag") && !operation.equals("key")
                    && !operation.equals("type") && !operation.equals("clipboard_read")
                    && !operation.equals("clipboard_write") && !operation.equals("focus")) {
                throw new IllegalArgumentException("unsupported desktop operation: " + operation);
            }
            if (!operation.equals("launch") && (task.desktop().executable() != null || !task.desktop().args().isEmpty())) {
                throw new IllegalArgumentException("desktop executable and args are allowed only for launch");
            }
            if (operation.equals("launch") && (task.desktop().executable() == null || task.desktop().executable().isBlank())) {
                throw new IllegalArgumentException("launch executable is required");
            }
            if (operation.equals("click") && (task.desktop().x() == null || task.desktop().y() == null
                    || task.desktop().x() < -100000 || task.desktop().x() > 100000
                    || task.desktop().y() < -100000 || task.desktop().y() > 100000)) {
                throw new IllegalArgumentException("click requires x/y between -100000 and 100000");
            }
            if (operation.equals("drag") && (task.desktop().x() == null || task.desktop().y() == null
                    || task.desktop().x2() == null || task.desktop().y2() == null
                    || task.desktop().x() < -100000 || task.desktop().x() > 100000
                    || task.desktop().y() < -100000 || task.desktop().y() > 100000
                    || task.desktop().x2() < -100000 || task.desktop().x2() > 100000
                    || task.desktop().y2() < -100000 || task.desktop().y2() > 100000)) {
                throw new IllegalArgumentException("drag requires x/y/x2/y2 between -100000 and 100000");
            }
            if (operation.equals("key") && (task.desktop().key() == null || task.desktop().key().isBlank())) {
                throw new IllegalArgumentException("key operation requires key");
            }
            if (operation.equals("type") && (task.desktop().text() == null || task.desktop().text().isEmpty()
                    || task.desktop().text().length() > 16384)) {
                throw new IllegalArgumentException("type operation requires text up to 16384 characters");
            }
            if (operation.equals("clipboard_write") && (task.desktop().text() == null
                    || task.desktop().text().length() > 65536)) {
                throw new IllegalArgumentException("clipboard_write requires text up to 65536 characters");
            }
            if (operation.equals("focus") && (task.desktop().windowTitle() == null
                    || task.desktop().windowTitle().isBlank() || task.desktop().windowTitle().length() > 512)) {
                throw new IllegalArgumentException("focus requires a window_title up to 512 characters");
            }
            if (task.desktop().screen() != null && (task.desktop().screen() < 0 || task.desktop().screen() > 32)) {
                throw new IllegalArgumentException("screen must be between 0 and 32");
            }
            if (task.desktop().durationMs() != null && (task.desktop().durationMs() < 0 || task.desktop().durationMs() > 10000)) {
                throw new IllegalArgumentException("duration_ms must be between 0 and 10000");
            }
            if (task.desktop().executable() != null) requireText(task.desktop().executable(), "desktop executable", 4096);
            if (task.desktop().cwd() != null) requireBytes(task.desktop().cwd(), "desktop cwd", MAX_CWD_BYTES);
            if (task.desktop().args().size() > 128) throw new IllegalArgumentException("too many desktop arguments");
            task.desktop().args().forEach(argument -> requireBytes(argument, "desktop argument", 4096));
            if (task.desktop().text() != null) requireBytes(task.desktop().text(), "desktop text", 64 * 1024);
            if (task.desktop().key() != null) requireText(task.desktop().key(), "desktop key", 64);
            if (task.desktop().windowTitle() != null) {
                requireText(task.desktop().windowTitle(), "window title", 512);
                if (task.desktop().windowTitle().indexOf('\r') >= 0 || task.desktop().windowTitle().indexOf('\n') >= 0) {
                    throw new IllegalArgumentException("window title must be a single line");
                }
            }
        }
        if (task.kind() == TaskKind.BROWSER && (task.command() == null || task.command().isBlank())) {
            throw new IllegalArgumentException("browser adapter command is required for browser tasks");
        }
    }

    public static void validateContract(ExecutionContract contract, TaskCommand task) {
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(task, "task");
        if (!Objects.equals(contract.capability(), task.requiredCapability())) {
            throw new IllegalArgumentException("execution contract capability does not match task capability");
        }
        if (contract.budget().maxDurationSeconds() > 0
                && task.timeoutSeconds() > contract.budget().maxDurationSeconds()) {
            throw new IllegalArgumentException("task timeout exceeds execution contract budget");
        }
        if (contract.scopeMode() == ScopeMode.PROJECT && contract.worktreeId() != null) {
            throw new IllegalArgumentException("project contract cannot carry worktreeId");
        }
    }

    public static void validateEnv(Map<String, String> env) {
        Objects.requireNonNull(env, "env");
        if (env.size() > MAX_ENV_ENTRIES) {
            throw new IllegalArgumentException("too many environment entries");
        }
        env.forEach((key, value) -> {
            requireBytes(key, "environment key", MAX_ENV_KEY_BYTES);
            if (!ENV_KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("invalid environment key: " + key);
            }
            requireBytes(value, "environment value", MAX_ENV_VALUE_BYTES);
        });
    }

    private static void requireText(String value, String field, int maxChars) {
        if (value == null || value.isBlank() || value.indexOf('\u0000') >= 0 || value.length() > maxChars) {
            throw new IllegalArgumentException(field + " is empty, contains NUL, or is too long");
        }
    }

    private static void requireBytes(String value, String field, int maxBytes) {
        if (value == null || value.indexOf('\u0000') >= 0 || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException(field + " contains NUL or is too long");
        }
    }
}
