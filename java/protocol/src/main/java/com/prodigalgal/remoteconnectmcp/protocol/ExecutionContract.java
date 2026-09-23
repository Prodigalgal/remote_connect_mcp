package com.prodigalgal.remoteconnectmcp.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Center-issued execution contract carried with every newly-created task.
 * The Agent uses it only to verify identity, capability, lifetime and
 * resource limits; it is not an authentication credential and must never
 * contain a token or cookie.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExecutionContract(
        @JsonProperty("machine_id") String machineId,
        @JsonProperty("host_id") String hostId,
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

    }

    public boolean expired(Instant now) {
        return expiresAt == null || now == null || !now.isBefore(expiresAt);
    }

    /** Compare only caller intent; generated session/expiry/lease values vary across retries. */
    public boolean sameIntent(ExecutionContract other) {
        if (other == null) return false;
        return Objects.equals(machineId, other.machineId)
                && Objects.equals(hostId, other.hostId)
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

    public record Budget(
            @JsonProperty("max_duration_seconds") int maxDurationSeconds,
            @JsonProperty("max_output_bytes") long maxOutputBytes,
            @JsonProperty("max_artifact_bytes") long maxArtifactBytes,
            @JsonProperty("max_child_processes") int maxChildProcesses,
            @JsonProperty("max_rss_bytes") long maxRssBytes,
            @JsonProperty("max_cpu_seconds") long maxCpuSeconds) {

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

        /** Local construction overload with no RSS/CPU budget. */
        public Budget(int maxDurationSeconds, long maxOutputBytes, long maxArtifactBytes,
                      int maxChildProcesses) {
            this(maxDurationSeconds, maxOutputBytes, maxArtifactBytes, maxChildProcesses, 0L, 0L);
        }

        public static Budget defaults() {
            return new Budget(0, 64L * 1024 * 1024, 8L * 1024 * 1024, 32, 0L, 0L);
        }
    }
}
