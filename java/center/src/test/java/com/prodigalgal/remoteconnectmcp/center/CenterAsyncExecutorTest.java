package com.prodigalgal.remoteconnectmcp.center;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CenterAsyncExecutorTest {
    @Test
    void runsBlockingIntegrationOnASeparateVirtualThread() throws Exception {
        try (var executor = new CenterAsyncExecutor()) {
            var caller = Thread.currentThread();
            var result = executor.submit(() -> new Execution(Thread.currentThread(), Thread.currentThread().isVirtual()))
                    .get(2, TimeUnit.SECONDS);

            assertNotEquals(caller, result.thread());
            assertTrue(result.virtual());
        }
    }

    private record Execution(Thread thread, boolean virtual) {
    }
}
