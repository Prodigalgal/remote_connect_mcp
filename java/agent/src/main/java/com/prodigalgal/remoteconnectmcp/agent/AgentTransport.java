package com.prodigalgal.remoteconnectmcp.agent;

import com.prodigalgal.remoteconnectmcp.protocol.PollRequest;
import com.prodigalgal.remoteconnectmcp.protocol.PollResponse;
import com.prodigalgal.remoteconnectmcp.protocol.ArtifactResponse;
import com.prodigalgal.remoteconnectmcp.protocol.OutputResponse;
import com.prodigalgal.remoteconnectmcp.protocol.RegisterResponse;
import com.prodigalgal.remoteconnectmcp.protocol.TaskUpdateRequest;
import com.prodigalgal.remoteconnectmcp.protocol.UpgradeStatusRequest;
import java.io.IOException;

/** Transport seam used by the runtime and deterministic reconnect tests. */
public interface AgentTransport {
    RegisterResponse register(AgentConfig config) throws IOException, InterruptedException;

    PollResponse poll(String machineId, String token, PollRequest request) throws IOException, InterruptedException;

    void updateState(String machineId, String token, String taskId, TaskUpdateRequest request) throws IOException, InterruptedException;

    OutputResponse appendOutput(String machineId, String token, String taskId, long offset, byte[] data) throws IOException, InterruptedException;

    ArtifactResponse appendArtifact(String machineId, String token, String taskId, String mimeType, String sha256, byte[] data) throws IOException, InterruptedException;

    /** Optional upgrade progress channel; old test transports remain compatible. */
    default void reportUpgrade(String machineId, String token, UpgradeStatusRequest request)
            throws IOException, InterruptedException {
        throw new CenterTransportException("upgrade status is not supported by this Center", 404);
    }
}
