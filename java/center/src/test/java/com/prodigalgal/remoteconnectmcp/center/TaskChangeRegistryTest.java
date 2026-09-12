package com.prodigalgal.remoteconnectmcp.center;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

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

    @Test
    void taskOnlySignalDoesNotAdvanceGlobalCursor() throws Exception {
        try (var registry = new TaskChangeRegistry(null);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var global = registry.version();
            var taskVersion = registry.version("task_output");
            try {
                var waiter = executor.submit(() -> {
                    registry.awaitChange("task_output", taskVersion, Duration.ofSeconds(1).toNanos());
                    return null;
                });
                registry.signalTaskOnly("task_output");
                waiter.get(1, TimeUnit.SECONDS);
                assertEquals(global, registry.version());
            } finally {
                registry.release("task_output");
            }
        }
    }
}
