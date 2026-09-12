package com.prodigalgal.remoteconnectmcp.agent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.prodigalgal.remoteconnectmcp.protocol.OutputResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskOutputSpoolTest {
    @Test
    void pumpWaitsForProducerAndPreservesBoundedBytes(@TempDir Path stateDir) throws Exception {
        try (var spool = new TaskOutputSpool(stateDir, "task-1", 1024 * 1024)) {
            var received = new CopyOnWriteArrayList<byte[]>();
            var pump = Thread.startVirtualThread(() -> {
                try {
                    TaskOutputPump.upload(java.util.logging.Logger.getAnonymousLogger(), "test upload", spool,
                            (offset, data) -> {
                                received.add(data);
                                return new OutputResponse(offset + data.length);
                            });
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            });
            spool.append("hello ".getBytes(StandardCharsets.UTF_8), 0, 6);
            spool.append("world".getBytes(StandardCharsets.UTF_8), 0, 5);
            spool.complete();
            pump.join();

            var result = received.stream().reduce(new byte[0], (left, right) -> {
                var merged = java.util.Arrays.copyOf(left, left.length + right.length);
                System.arraycopy(right, 0, merged, left.length, right.length);
                return merged;
            });
            assertArrayEquals("hello world".getBytes(StandardCharsets.UTF_8), result);
            assertTrue(received.size() >= 1);
        }
    }

    @Test
    void marksTruncationWithoutBlockingProducer(@TempDir Path stateDir) throws Exception {
        try (var spool = new TaskOutputSpool(stateDir, "task-2", 1024 * 1024)) {
            var bytes = new byte[1024 * 1024 + 17];
            spool.append(bytes, 0, bytes.length);
            spool.complete();
            assertTrue(spool.truncated());
            assertTrue(spool.size() <= 1024 * 1024);
        }
    }

    @Test
    void stopsCleanlyWhenCenterAcknowledgesOnlyARetainedPrefix(@TempDir Path stateDir) throws Exception {
        try (var spool = new TaskOutputSpool(stateDir, "task-3", 1024 * 1024)) {
            var calls = new CopyOnWriteArrayList<byte[]>();
            spool.append("abcdef".getBytes(StandardCharsets.UTF_8), 0, 6);
            spool.complete();
            TaskOutputPump.upload(java.util.logging.Logger.getAnonymousLogger(), "test ceiling", spool,
                    (offset, data) -> {
                        calls.add(data);
                        return new OutputResponse(offset + 3);
                    });
            assertTrue(calls.size() == 1);
        }
    }
}
