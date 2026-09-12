package com.prodigalgal.remoteconnectmcp.agent;

import java.io.IOException;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bounded-backoff retry helper for Agent-to-Center delivery.  The operation is
 * invoked on a virtual thread, so waiting for a transient network failure does
 * not consume a platform thread.  Client errors and credential failures are
 * deliberately not retried: they require a new task or re-enrollment.
 */
final class AgentRetry {
    private static final Duration MAX_DELAY = Duration.ofSeconds(30);

    @FunctionalInterface
    interface IoCall<T> {
        T invoke() throws IOException, InterruptedException;
    }

    private AgentRetry() {
    }

    static <T> T call(Logger logger, String operation, IoCall<T> call) throws IOException, InterruptedException {
        var delay = Duration.ofSeconds(1);
        while (true) {
            try {
                return call.invoke();
            } catch (CenterTransportException exception) {
                if (!retryable(exception.statusCode())) {
                    throw exception;
                }
                logger.log(Level.WARNING, operation + " failed; retrying", exception);
            } catch (IOException exception) {
                logger.log(Level.WARNING, operation + " connection failed; retrying", exception);
            }
            Thread.sleep(delay.toMillis());
            delay = Duration.ofMillis(Math.min(MAX_DELAY.toMillis(), Math.max(1000, delay.toMillis() * 2)));
        }
    }

    private static boolean retryable(int status) {
        return status == 408 || status == 425 || status == 429 || status >= 500;
    }
}
