package com.prodigalgal.remoteconnectmcp.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Center-issued execution authority carried with every newly-created task.
 * The Agent treats it as a second, local policy check; it is not an
 * authentication credential and must never contain a token or cookie.
 */
public record ExecutionContract(
        @JsonProperty("machine_id") String machineId,
        @JsonProperty("host_id") String hostId,
        @JsonProperty("scope_mode") ScopeMode scopeMode,
        @JsonProperty("project_id") String projectId,
        @JsonProperty("worktree_id") String worktreeId,
        @JsonProperty("scope_root") String scopeRoot,
        @JsonProperty("workspace_policy") WorkspacePolicyMode workspacePolicy,
        @JsonProperty("lane_mode") LaneMode laneMode,
        @JsonProperty("session_id") String sessionId,
        String capability,
        Budget budget,
        @JsonProperty("expires_at") Instant expiresAt,
        @JsonProperty("idempotency_key") String idempotencyKey,
        String risk,
        @JsonProperty("elevation_required") boolean elevationRequired,
        @JsonProperty("lease_id") String leaseId) {

    public ExecutionContract {
        machineId = required(machineId, "machineId", 256);
        hostId = required(hostId, "hostId", 512);
        scopeMode = scopeMode == null ? ScopeMode.WORKSPACE : scopeMode;
        projectId = optional(projectId, "projectId", 256);
        worktreeId = optional(worktreeId, "worktreeId", 256);
        scopeRoot = optional(scopeRoot, "scopeRoot", ProtocolValidation.MAX_CWD_BYTES);
        workspacePolicy = workspacePolicy == null ? defaultWorkspacePolicy(scopeMode) : workspacePolicy;
        laneMode = laneMode == null ? LaneMode.WRITE : laneMode;
        sessionId = required(sessionId, "sessionId", 256);
        capability = required(capability, "capability", 128);
        budget = budget == null ? Budget.defaults() : budget;
        if (expiresAt == null) throw new IllegalArgumentException("expiresAt is required");
        idempotencyKey = optional(idempotencyKey, "idempotencyKey", 256);
        risk = risk == null || risk.isBlank() ? "low" : risk.trim().toLowerCase(Locale.ROOT);
        if (!List.of("low", "high", "critical").contains(risk)) {
            throw new IllegalArgumentException("risk must be low, high, or critical");
        }
        leaseId = optional(leaseId, "leaseId", 256);

        if (scopeMode == ScopeMode.PROJECT && projectId == null) {
            throw new IllegalArgumentException("project scope requires projectId");
        }
        if (scopeMode == ScopeMode.PROJECT && worktreeId != null) {
            throw new IllegalArgumentException("project scope cannot carry worktreeId");
        }
        if (scopeMode == ScopeMode.WORKTREE && (projectId == null || worktreeId == null)) {
            throw new IllegalArgumentException("worktree scope requires projectId and worktreeId");
        }
        if ((scopeMode == ScopeMode.PATH || scopeMode == ScopeMode.WORKSPACE)
                && (projectId != null || worktreeId != null)) {
            throw new IllegalArgumentException("path/workspace scope cannot carry project identity");
        }
        if (scopeMode.bounded() && (scopeRoot == null || scopeRoot.isBlank())) {
            throw new IllegalArgumentException("bounded scope requires scopeRoot");
        }
        if (scopeMode == ScopeMode.UNRESTRICTED && (projectId != null || worktreeId != null || scopeRoot != null)) {
            throw new IllegalArgumentException("unrestricted scope cannot carry project, worktree, or scopeRoot");
        }
        if (workspacePolicy == WorkspacePolicyMode.HOST && scopeMode != ScopeMode.UNRESTRICTED) {
            throw new IllegalArgumentException("host workspace policy requires explicit unrestricted scope");
        }
        if (workspacePolicy == WorkspacePolicyMode.ISOLATED
                && scopeMode == ScopeMode.UNRESTRICTED) {
            throw new IllegalArgumentException("isolated workspace policy requires a bounded scope");
        }
        if (workspacePolicy == WorkspacePolicyMode.ISOLATED && scopeMode != ScopeMode.WORKTREE) {
            throw new IllegalArgumentException("isolated workspace policy requires worktree scope");
        }
    }

    /** Compatibility constructor for the pre-workspace/lane contract shape. */
    public ExecutionContract(String machineId, String hostId, ScopeMode scopeMode,
                             String projectId, String worktreeId, String scopeRoot,
                             String sessionId, String capability, Budget budget,
                             Instant expiresAt, String idempotencyKey, String risk,
                             boolean elevationRequired, String leaseId) {
        this(machineId, hostId, scopeMode, projectId, worktreeId, scopeRoot,
                defaultWorkspacePolicy(scopeMode), LaneMode.WRITE, sessionId, capability,
                budget, expiresAt, idempotencyKey, risk, elevationRequired, leaseId);
    }

    public boolean expired(Instant now) {
        return expiresAt == null || now == null || !now.isBefore(expiresAt);
    }

    /** Compare only caller intent; generated session/expiry/lease values vary across retries. */
    public boolean sameIntent(ExecutionContract other) {
        if (other == null) return false;
        return Objects.equals(machineId, other.machineId)
                && Objects.equals(hostId, other.hostId)
                && scopeMode == other.scopeMode
                && Objects.equals(projectId, other.projectId)
                && Objects.equals(worktreeId, other.worktreeId)
                && Objects.equals(scopeRoot, other.scopeRoot)
                && workspacePolicy == other.workspacePolicy
                && laneMode == other.laneMode
                && Objects.equals(capability, other.capability)
                && Objects.equals(budget, other.budget)
                && Objects.equals(idempotencyKey, other.idempotencyKey)
                && Objects.equals(risk, other.risk)
                && elevationRequired == other.elevationRequired;
    }

    private static String required(String value, String field, int max) {
        var normalized = optional(value, field, max);
        if (normalized == null) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    private static String optional(String value, String field, int max) {
        if (value == null || value.isBlank()) return null;
        var normalized = value.trim();
        if (normalized.length() > max || normalized.indexOf('\u0000') >= 0
                || normalized.indexOf('\r') >= 0 || normalized.indexOf('\n') >= 0
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static WorkspacePolicyMode defaultWorkspacePolicy(ScopeMode mode) {
        if (mode == ScopeMode.UNRESTRICTED) return WorkspacePolicyMode.HOST;
        if (mode == ScopeMode.WORKTREE) return WorkspacePolicyMode.ISOLATED;
        return WorkspacePolicyMode.SHARED_SERIAL;
    }

    public record Budget(
            @JsonProperty("max_duration_seconds") int maxDurationSeconds,
            @JsonProperty("max_output_bytes") long maxOutputBytes,
            @JsonProperty("max_artifact_bytes") long maxArtifactBytes,
            @JsonProperty("max_child_processes") int maxChildProcesses,
            @JsonProperty("max_rss_bytes") long maxRssBytes,
            @JsonProperty("max_cpu_seconds") long maxCpuSeconds) {

        /** Compatibility constructor for the original four-field budget. */
        public Budget(int maxDurationSeconds, long maxOutputBytes, long maxArtifactBytes,
                      int maxChildProcesses) {
            this(maxDurationSeconds, maxOutputBytes, maxArtifactBytes, maxChildProcesses, 0L, 0L);
        }

        public Budget {
            if (maxDurationSeconds < 0 || maxDurationSeconds > ProtocolValidation.MAX_TIMEOUT_SECONDS) {
                throw new IllegalArgumentException("maxDurationSeconds is outside the allowed range");
            }
            if (maxOutputBytes < 1024L * 1024 || maxOutputBytes > 1024L * 1024 * 1024) {
                throw new IllegalArgumentException("maxOutputBytes is outside the allowed range");
            }
            if (maxArtifactBytes < 0 || maxArtifactBytes > 64L * 1024 * 1024) {
                throw new IllegalArgumentException("maxArtifactBytes is outside the allowed range");
            }
            if (maxChildProcesses < 1 || maxChildProcesses > 256) {
                throw new IllegalArgumentException("maxChildProcesses is outside the allowed range");
            }
            if (maxRssBytes < 0 || maxRssBytes > 16L * 1024 * 1024 * 1024) {
                throw new IllegalArgumentException("maxRssBytes is outside the allowed range");
            }
            if (maxCpuSeconds < 0 || maxCpuSeconds > 30L * 24 * 60 * 60) {
                throw new IllegalArgumentException("maxCpuSeconds is outside the allowed range");
            }
        }

        public static Budget defaults() {
            return new Budget(0, 64L * 1024 * 1024, 8L * 1024 * 1024, 32, 0L, 0L);
        }
    }
}
