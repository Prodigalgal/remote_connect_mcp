package com.prodigalgal.remoteconnectmcp.agent;

import com.prodigalgal.remoteconnectmcp.protocol.PollRequest;
import com.prodigalgal.remoteconnectmcp.protocol.PollResponse;
import com.prodigalgal.remoteconnectmcp.protocol.ArtifactResponse;
import com.prodigalgal.remoteconnectmcp.protocol.OutputResponse;
import com.prodigalgal.remoteconnectmcp.protocol.RegisterResponse;
import com.prodigalgal.remoteconnectmcp.protocol.TaskUpdateRequest;
import com.prodigalgal.remoteconnectmcp.protocol.UpgradeStatusRequest;
import java.io.IOException;
import java.nio.file.Path;

/** Transport seam used by the runtime and deterministic reconnect tests. */
public interface AgentTransport {
    RegisterResponse register(AgentConfig config) throws IOException, InterruptedException;

    PollResponse poll(String machineId, String token, PollRequest request) throws IOException, InterruptedException;

    /** True after the Center confirms that the latest poll used long-polling. */
    default boolean longPollHonored() {
        return false;
    }

    /**
     * Last transport selected by the Center.  This is diagnostic metadata
     * only; task semantics and retry fencing do not depend on it.
     */
    default String selectedTransport() {
        return "https";
    }

    void updateState(String machineId, String token, String taskId, int attempt,
                     TaskUpdateRequest request) throws IOException, InterruptedException;

    /** Send a bounded progress snapshot without changing task state. */
    default void updateProgress(String machineId, String token, String taskId, int attempt,
                                com.prodigalgal.remoteconnectmcp.protocol.TaskProgressUpdate progress)
            throws IOException, InterruptedException {
        throw new CenterTransportException("task progress is not supported by this Center", 404);
    }

    OutputResponse appendOutput(String machineId, String token, String taskId, int attempt,
                                long offset, byte[] data) throws IOException, InterruptedException;

    ArtifactResponse appendArtifact(String machineId, String token, String taskId, int attempt,
                                    String mimeType, String sha256, byte[] data) throws IOException, InterruptedException;

    /** Stream a Center-owned inbound artifact to a local file. */
    default void downloadTransfer(String machineId, String token, String transferId, Path destination,
                                  long expectedBytes, String expectedSha256, int attempt, boolean overwrite)
            throws IOException, InterruptedException {
        throw new CenterTransportException("file transfer download is not supported by this Center", 404);
    }

    /** Confirm the local side of a transfer without adding a second task. */
    default void acknowledgeTransfer(String machineId, String token, String transferId,
                                     com.prodigalgal.remoteconnectmcp.protocol.FileTransferResponse acknowledgement,
                                     int attempt) throws IOException, InterruptedException {
        throw new CenterTransportException("file transfer acknowledgement is not supported by this Center", 404);
    }

    /**
     * Return the Center resume offset together with its durable transfer
     * status.  The status matters when a connection was lost after the final
     * chunk reached the Center but before the metadata transaction committed.
     */
    TransferResume queryTransferResume(String machineId, String token, String transferId, int attempt)
            throws IOException, InterruptedException;

    /** Stream a local file to the Center-owned artifact store. */
    com.prodigalgal.remoteconnectmcp.protocol.FileTransferResponse uploadTransfer(
            String machineId, String token, String transferId, Path source, String fileName,
            String mimeType, long expectedBytes, String expectedSha256, int attempt)
            throws IOException, InterruptedException;

    record TransferResume(long offset, String status) { }

    /** Upgrade progress channel. */
    default void reportUpgrade(String machineId, String token, UpgradeStatusRequest request)
            throws IOException, InterruptedException {
        throw new CenterTransportException("upgrade status is not supported by this Center", 404);
    }
}
