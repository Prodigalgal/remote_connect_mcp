package com.prodigalgal.remoteconnectmcp.center;

import com.prodigalgal.remoteconnectmcp.protocol.ExecutionContract;
import com.prodigalgal.remoteconnectmcp.protocol.LaneMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Derives a stable, low-cardinality lane identity from the Center-issued
 * execution contract. Lanes coordinate contention; they are not permission
 * boundaries and never encode a project, workspace or path.
 */
final class ExecutionLaneKey {
    private ExecutionLaneKey() {
    }

    static String derive(String machineId, ExecutionContract contract) {
        var machine = machineId == null ? "" : machineId.trim();
        if (contract == null) return "lane_" + digest(machine + "\u0000host");
        var host = value(contract.hostId());
        // A host can have more than one physical command Agent.  Host-scoped
        // writes and Desktop operations therefore share one durable lane even
        // when their machine_id values differ.  This is the Center-side fence;
        // the Agent still keeps each process tree and cwd isolated.
        if ((contract.laneMode() == LaneMode.EXCLUSIVE
                && "desktop".equalsIgnoreCase(value(contract.capability())))) {
            return "lane_" + digest(host + "\u0000host");
        }
        // Browser profiles are isolated by execution session.  Serialize only
        // work that targets the same session/profile while allowing independent
        // browser contexts on one host to proceed in parallel (subject to the
        // Agent's own browser worker budget).
        if ("browser".equalsIgnoreCase(value(contract.capability()))) {
            return "lane_" + digest(host + "\u0000browser\u0000" + value(contract.sessionId()));
        }
        // All tasks on the same machine share one host lane. A lane only
        // serializes conflicting work; it does not restrict where that work
        // may operate.
        return "lane_" + digest(machine + "\u0000host");
    }

    /** Whether this lane is shared by multiple physical Agent identities on one host. */
    static boolean crossesMachineBoundary(ExecutionContract contract) {
        if (contract == null) return false;
        return "browser".equalsIgnoreCase(value(contract.capability()))
                || (contract.laneMode() == LaneMode.EXCLUSIVE
                && "desktop".equalsIgnoreCase(value(contract.capability())));
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
