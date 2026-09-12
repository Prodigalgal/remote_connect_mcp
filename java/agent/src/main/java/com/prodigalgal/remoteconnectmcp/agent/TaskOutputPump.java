package com.prodigalgal.remoteconnectmcp.agent;

import com.prodigalgal.remoteconnectmcp.protocol.OutputResponse;
import java.io.IOException;
import java.util.logging.Logger;

/** Uploads a local output spool independently from the child-process reader. */
final class TaskOutputPump {
    @FunctionalInterface
    interface Upload {
        OutputResponse upload(long offset, byte[] data) throws IOException, InterruptedException;
    }

    private TaskOutputPump() {
    }

    static long upload(Logger logger, String operation, TaskOutputSpool spool, Upload upload)
            throws IOException, InterruptedException {
        long offset = 0;
        while (true) {
            var chunk = spool.awaitChunk(offset, 16 * 1024);
            if (chunk.data().length > 0) {
                var uploadOffset = offset;
                var data = chunk.data();
                var ack = AgentRetry.call(logger, operation, () -> upload.upload(uploadOffset, data));
                var next = ack.nextOffset();
                if (next < uploadOffset || next > uploadOffset + data.length) {
                    throw new IOException("Center returned an invalid output cursor: " + next);
                }
                if (next < uploadOffset + data.length) {
                    // Center may have reached its retained-output ceiling.
                    // The bytes were accepted/consumed but not retained; do
                    // not advance past the server cursor and then turn a
                    // deliberate truncation into a permanent 400 retry loop.
                    logger.warning(operation + " reached Center output ceiling; retaining the local prefix only");
                    offset = next;
                    return offset;
                }
                offset = Math.max(uploadOffset + data.length, next);
            }
            if (chunk.endOfStream()) {
                return offset;
            }
        }
    }
}
