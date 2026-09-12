package com.prodigalgal.remoteconnectmcp.center;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TaskChangeRegistryTest {
    @Test
    void signalWakesAWaitingVirtualThread() throws Exception {
        try (var registry = new TaskChangeRegistry(null);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var observed = registry.version();
            var waiter = executor.submit(() -> {
                registry.awaitChange(observed, Duration.ofSeconds(2).toNanos());
                return registry.version();
            });
            Thread.sleep(25);
            registry.signal("task_test");
            assertTrue(waiter.get(2, TimeUnit.SECONDS) > observed);
        }
    }

    @Test
    void signalBeforeAwaitIsNotLost() throws Exception {
        try (var registry = new TaskChangeRegistry(null)) {
            var observed = registry.version();
            registry.signal("task_test");
            registry.awaitChange(observed, Duration.ofSeconds(1).toNanos());
            assertTrue(registry.version() > observed);
        }
    }
}
