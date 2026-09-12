package com.prodigalgal.remoteconnectmcp.agent;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AgentWakeSignalTest {
    @Test
    void signalBeforeAwaitMakesPollBackoffReturnImmediately() {
        var signal = new AgentWakeSignal();
        signal.signal();
        assertTimeoutPreemptively(Duration.ofMillis(250), () -> signal.await(Duration.ofSeconds(10)));
    }
}
