package com.prodigalgal.remoteconnectmcp.center;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.prodigalgal.remoteconnectmcp.protocol.ExecutionContract;
import com.prodigalgal.remoteconnectmcp.protocol.LaneMode;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ExecutionLaneKeyTest {
    @Test
    void commandLanesAreMachineLocalWhileDesktopAndBrowserCoordinateByHost() {
        var commandA = contract("machine-a", "host-a", LaneMode.WRITE, "command", "session-a");
        var commandB = contract("machine-b", "host-a", LaneMode.WRITE, "command", "session-b");
        var desktopA = contract("machine-a", "host-a", LaneMode.EXCLUSIVE, "desktop", "session-a");
        var desktopB = contract("machine-b", "host-a", LaneMode.EXCLUSIVE, "desktop", "session-b");

        assertNotEquals(ExecutionLaneKey.derive("machine-a", commandA), ExecutionLaneKey.derive("machine-b", commandB));
        assertEquals(ExecutionLaneKey.derive("machine-a", desktopA), ExecutionLaneKey.derive("machine-b", desktopB));
    }

    @Test
    void browserSessionsAreIndependentButSameSessionIsSerialized() {
        var first = contract("machine-a", "host-a", LaneMode.EXCLUSIVE, "browser", "session-a");
        var same = contract("machine-b", "host-a", LaneMode.EXCLUSIVE, "browser", "session-a");
        var other = contract("machine-c", "host-a", LaneMode.EXCLUSIVE, "browser", "session-b");
        assertEquals(ExecutionLaneKey.derive("machine-a", first), ExecutionLaneKey.derive("machine-b", same));
        assertNotEquals(ExecutionLaneKey.derive("machine-a", first), ExecutionLaneKey.derive("machine-c", other));
    }

    @Test
    void pathsDoNotParticipateInScheduling() {
        var first = contract("machine-a", "host-a", LaneMode.WRITE, "command", "session-a");
        var other = contract("machine-a", "host-a", LaneMode.WRITE, "command", "session-b");
        assertEquals(ExecutionLaneKey.derive("machine-a", first), ExecutionLaneKey.derive("machine-a", other));
    }

    private static ExecutionContract contract(String machine, String host, LaneMode lane,
                                               String capability, String session) {
        return new ExecutionContract(machine, host, lane, session, capability,
                ExecutionContract.Budget.defaults(), Instant.now().plusSeconds(3600),
                "lane-test", "low", false, null);
    }
}
