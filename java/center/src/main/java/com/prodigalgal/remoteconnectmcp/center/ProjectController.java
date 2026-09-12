package com.prodigalgal.remoteconnectmcp.center;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Versioned project/worktree management API for the React console. */
@RestController
@RequestMapping("/api/v1/admin/projects")
public final class ProjectController {
    private final CenterTokenConfig tokens;
    private final ProjectService projects;
    private final CenterAsyncExecutor async;

    public ProjectController(CenterTokenConfig tokens, ProjectService projects, CenterAsyncExecutor async) {
        this.tokens = tokens;
        this.projects = projects;
        this.async = async;
    }

    @GetMapping
    public CompletableFuture<ResponseEntity<?>> list(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "machine_id", required = false) String machineId,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit) {
        return execute(() -> {
            authenticate(authorization);
            return ResponseEntity.ok(Map.of("items", projects.list(machineId, offset, limit), "offset", offset, "limit", limit));
        });
    }

    @PostMapping
    public CompletableFuture<ResponseEntity<?>> register(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) ProjectRegistrationRequest request) {
        return execute(() -> {
            authenticate(authorization);
            return ResponseEntity.status(HttpStatus.CREATED).body(projects.register(request));
        });
    }

    @GetMapping("/{projectId}")
    public CompletableFuture<ResponseEntity<?>> get(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String projectId) {
        return execute(() -> {
            authenticate(authorization);
            return ResponseEntity.ok(projects.find(projectId));
        });
    }

    @PostMapping("/{projectId}/worktrees")
    public CompletableFuture<ResponseEntity<?>> createWorktree(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String projectId,
            @RequestBody(required = false) ProjectWorktreeRequest request) {
        return execute(() -> {
            authenticate(authorization);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(projects.createWorktree(projectId, request));
        });
    }

    @DeleteMapping("/{projectId}/worktrees/{worktreeId}")
    public CompletableFuture<ResponseEntity<?>> removeWorktree(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String projectId,
            @PathVariable String worktreeId,
            @RequestParam(value = "idempotency_key", required = false) String idempotencyKey) {
        return execute(() -> {
            authenticate(authorization);
            return ResponseEntity.accepted().body(projects.removeWorktree(projectId, worktreeId, idempotencyKey));
        });
    }

    private CompletableFuture<ResponseEntity<?>> execute(java.util.concurrent.Callable<ResponseEntity<?>> action) {
        return async.submit(action).exceptionally(failure -> error(unwrap(failure)));
    }

    private void authenticate(String authorization) {
        if (!tokens.acceptsAdmin(bearerValue(authorization))) throw new SecurityException("invalid admin credentials");
    }

    private static String bearerValue(String authorization) {
        if (authorization == null) return "";
        var parts = authorization.trim().split("\\s+", 2);
        return parts.length == 2 && "Bearer".equalsIgnoreCase(parts[0]) ? parts[1].trim() : "";
    }

    private static ResponseEntity<Map<String, String>> error(Throwable exception) {
        if (exception instanceof SecurityException) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", message(exception)));
        if (exception instanceof IllegalArgumentException) return ResponseEntity.badRequest().body(Map.of("error", message(exception)));
        return ResponseEntity.internalServerError().body(Map.of("error", "internal error"));
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure instanceof CompletionException && failure.getCause() != null) return unwrap(failure.getCause());
        return failure;
    }

    private static String message(Throwable exception) {
        return exception == null || exception.getMessage() == null || exception.getMessage().isBlank()
                ? "request failed" : exception.getMessage();
    }
}
