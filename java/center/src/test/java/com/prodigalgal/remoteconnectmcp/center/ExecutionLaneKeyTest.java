package com.prodigalgal.remoteconnectmcp.center;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.prodigalgal.remoteconnectmcp.protocol.ExecutionContract;
import com.prodigalgal.remoteconnectmcp.protocol.LaneMode;
import com.prodigalgal.remoteconnectmcp.protocol.ScopeMode;
import com.prodigalgal.remoteconnectmcp.protocol.WorkspacePolicyMode;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ExecutionLaneKeyTest {
    @Test
    void hostWritesAndDesktopOperationsShareThePhysicalHostLane() {
        var hostWriteA = contract("machine-a", "host-a", WorkspacePolicyMode.HOST,
                LaneMode.WRITE, "command", "session-a", ScopeMode.UNRESTRICTED, null, null, null);
        var hostWriteB = contract("machine-b", "host-a", WorkspacePolicyMode.HOST,
                LaneMode.WRITE, "command", "session-b", ScopeMode.UNRESTRICTED, null, null, null);
        var desktopA = contract("machine-a", "host-a", WorkspacePolicyMode.SHARED_SERIAL,
                LaneMode.EXCLUSIVE, "desktop", "session-a", ScopeMode.WORKSPACE, null, null, "/desktop");
        var desktopB = contract("machine-b", "host-a", WorkspacePolicyMode.SHARED_SERIAL,
                LaneMode.EXCLUSIVE, "desktop", "session-b", ScopeMode.WORKSPACE, null, null, "/desktop");

        assertEquals(ExecutionLaneKey.derive("machine-a", hostWriteA),
                ExecutionLaneKey.derive("machine-b", hostWriteB));
        assertEquals(ExecutionLaneKey.derive("machine-a", desktopA),
                ExecutionLaneKey.derive("machine-b", desktopB));
    }

    @Test
    void browserSessionsAreIndependentButSameSessionIsSerialized() {
        var first = contract("machine-a", "host-a", WorkspacePolicyMode.SHARED_SERIAL,
                LaneMode.EXCLUSIVE, "browser", "session-a", ScopeMode.WORKSPACE, null, null, "/browser");
        var same = contract("machine-b", "host-a", WorkspacePolicyMode.SHARED_SERIAL,
                LaneMode.EXCLUSIVE, "browser", "session-a", ScopeMode.WORKSPACE, null, null, "/browser");
        var other = contract("machine-c", "host-a", WorkspacePolicyMode.SHARED_SERIAL,
                LaneMode.EXCLUSIVE, "browser", "session-b", ScopeMode.WORKSPACE, null, null, "/browser");

        assertEquals(ExecutionLaneKey.derive("machine-a", first),
                ExecutionLaneKey.derive("machine-b", same));
        assertNotEquals(ExecutionLaneKey.derive("machine-a", first),
                ExecutionLaneKey.derive("machine-c", other));
    }

    @Test
    void worktreesRemainIndependent() {
        var first = contract("machine-a", "host-a", WorkspacePolicyMode.ISOLATED,
                LaneMode.WRITE, "command", "session-a", ScopeMode.WORKTREE, "project", "wt-a", "/workspace/wt-a");
        var other = contract("machine-a", "host-a", WorkspacePolicyMode.ISOLATED,
                LaneMode.WRITE, "command", "session-b", ScopeMode.WORKTREE, "project", "wt-b", "/workspace/wt-b");
        assertNotEquals(ExecutionLaneKey.derive("machine-a", first),
                ExecutionLaneKey.derive("machine-a", other));
    }

    private static ExecutionContract contract(String machine, String host, WorkspacePolicyMode policy,
                                               LaneMode lane, String capability, String session, ScopeMode scope,
                                               String project, String worktree, String root) {
        return new ExecutionContract(machine, host, scope, project, worktree, root, policy, lane, session,
                capability, ExecutionContract.Budget.defaults(), Instant.now().plusSeconds(3600),
                "lane-test", "low", false, null);
    }
}
