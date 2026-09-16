package com.prodigalgal.remoteconnectmcp.center;

import com.prodigalgal.remoteconnectmcp.protocol.ExecutionContract;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Derives a stable, low-cardinality lane identity from the Center-issued
 * execution contract. The raw path is never used as a metric label or
 * returned to MCP; only the bounded digest is persisted for scheduling.
 */
final class ExecutionLaneKey {
    private ExecutionLaneKey() {
    }

    static String derive(String machineId, ExecutionContract contract) {
        var machine = machineId == null ? "" : machineId.trim();
        if (contract == null) return "lane_" + digest(machine + "\u0000host");
        var target = contract.scopeMode().wireValue() + "\u0000"
                + value(contract.projectId()) + "\u0000"
                + value(contract.worktreeId()) + "\u0000"
                + value(contract.scopeRoot());
        // All tasks in the same explicit host/environment scope share a lane;
        // project/worktree scopes naturally separate when their identities do.
        return "lane_" + digest(machine + "\u0000" + target);
    }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }

    private static String digest(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
