package com.prodigalgal.remoteconnectmcp.center;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;

/**
 * Bounded lifecycle wrapper for blocking integrations used by the servlet
 * endpoints.  JDBC and Liquibase are intentionally blocking APIs; putting
 * them on virtual threads keeps the MVC request and container carrier free
 * while preserving a simple transactional implementation.
 */
@Component
public final class CenterAsyncExecutor implements AutoCloseable {
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public <T> CompletableFuture<T> submit(Callable<T> action) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return action.call();
            } catch (RuntimeException | Error exception) {
                throw exception;
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }, executor);
    }

    @Override
    public void close() {
        executor.close();
    }
}
