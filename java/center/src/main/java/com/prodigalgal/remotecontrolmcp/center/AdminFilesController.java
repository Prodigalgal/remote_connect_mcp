package com.prodigalgal.remotecontrolmcp.center;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.prodigalgal.remotecontrolmcp.protocol.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Console filesystem and transfer API, backed by the same durable Agent tasks as MCP. */
@RestController
@RequestMapping("/api/v1/admin")
public final class AdminFilesController {
    private final CenterTokenConfig tokens;
    private final AgentRegistry agents;
    private final TaskService tasks;
    private final ArtifactTransferService transfers;
    private final CenterAsyncExecutor async;

    public AdminFilesController(CenterTokenConfig tokens, AgentRegistry agents, TaskService tasks,
                                ArtifactTransferService transfers, CenterAsyncExecutor async) {
        this.tokens = tokens; this.agents = agents; this.tasks = tasks; this.transfers = transfers; this.async = async;
    }

    @PostMapping("/machines/{machineId}/files")
    public CompletableFuture<ResponseEntity<?>> operate(@RequestHeader(value="Authorization", required=false) String auth,
                                                         @PathVariable String machineId, @RequestBody OperationBody body) {
        return execute(() -> {
            authenticate(auth);
            var request = FileRequest.from(body.request());
            var wait = body.waitMs() == null ? 2000 : body.waitMs();
            if (wait < 0 || wait > 15000) throw new IllegalArgumentException("wait_ms must be between 0 and 15000");
            var task = FileOperationService.create(agents, tasks, TaskOrigin.configured(), machineId, request, body.cwd(), body.idempotencyKey());
            if (wait > 0) task = tasks.waitForTerminal(TaskOrigin.configured(), task.id(), Duration.ofMillis(wait));
            return ok(payload(task));
        });
    }

    @GetMapping("/tasks/{taskId}/files")
    public CompletableFuture<ResponseEntity<?>> result(@RequestHeader(value="Authorization", required=false) String auth,
                                                        @PathVariable String taskId, @RequestParam(name="wait_ms", defaultValue="0") int wait,
                                                        @RequestParam(name="change_seq", defaultValue="-1") long sequence) {
        return execute(() -> {
            authenticate(auth);
            if (wait < 0 || wait > 20000) throw new IllegalArgumentException("wait_ms must be between 0 and 20000");
            var state = tasks.findFor(TaskOrigin.configured(), taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
            var task = wait == 0 ? new TaskView(state) : tasks.waitForChange(TaskOrigin.configured(), taskId, Long.MAX_VALUE, sequence, Duration.ofMillis(wait));
            if (!Set.of("files", "file_transfer").contains(task.kind())) throw new IllegalArgumentException("task is not a file operation");
            return ok(payload(task));
        });
    }

    @GetMapping("/machines/{machineId}/files/requests/{key}")
    public CompletableFuture<ResponseEntity<?>> recover(@RequestHeader(value="Authorization", required=false) String auth,
                                                         @PathVariable String machineId, @PathVariable String key) {
        return execute(() -> {
            authenticate(auth);
            var task = tasks.findIdempotent(TaskOrigin.configured(), machineId, key);
            if (task.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).cacheControl(CacheControl.noStore())
                    .body(Map.of("error", "task creation is not confirmed; inspect task records before repeating the operation"));
            if (!Set.of("files", "file_transfer").contains(task.get().kind())) throw new IllegalArgumentException("task is not a file operation");
            return ok(payload(task.get()));
        });
    }

    @PostMapping("/machines/{machineId}/files/download")
    public CompletableFuture<ResponseEntity<?>> download(@RequestHeader(value="Authorization", required=false) String auth,
                                                          @PathVariable String machineId, @RequestBody DownloadBody body) {
        return execute(() -> {
            authenticate(auth);
            var origin = TaskOrigin.configured();
            var command = new TaskCommand("", TaskKind.FILE_TRANSFER, "file_transfer", null, body.cwd(), Map.of(), 0, null, Instant.now());
            var request = new CreateTaskRequest(machineId, command, retryKey(body.idempotencyKey()));
            var created = transfers.createAgentToWeb(origin, request, body.sourcePath(), null, null);
            return ok(payload(tasks.waitForTerminal(origin, created.task().id(), Duration.ofSeconds(2))));
        });
    }

    @PostMapping(value="/machines/{machineId}/files/upload", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public CompletableFuture<ResponseEntity<?>> upload(@RequestHeader(value="Authorization", required=false) String auth,
                                                        @PathVariable String machineId, @RequestParam("file") MultipartFile file,
                                                        @RequestParam("destination_path") String destination,
                                                        @RequestParam(name="overwrite", defaultValue="false") boolean overwrite,
                                                        @RequestParam(name="idempotency_key") String key) {
        return execute(() -> {
            authenticate(auth);
            if (file.getSize() > 64L * 1024 * 1024) throw new IllegalArgumentException("Console upload limit is 64 MiB; use MCP file transfer for larger files");
            var origin = TaskOrigin.configured();
            var command = new TaskCommand("", TaskKind.FILE_TRANSFER, "file_transfer", null, null, Map.of(), 0, null, Instant.now());
            var request = new CreateTaskRequest(machineId, command, retryKey(key));
            ArtifactTransferService.TransferCreated created;
            try (var input = file.getInputStream()) {
                created = transfers.createConsoleToAgent(origin, request, destination, file.getOriginalFilename(), file.getContentType(), input, file.getSize(), overwrite);
            }
            return ok(payload(tasks.waitForTerminal(origin, created.task().id(), Duration.ofSeconds(2))));
        });
    }

    private Map<String, Object> payload(TaskView task) {
        var result = new LinkedHashMap<String, Object>(); result.put("task", task);
        var files = FileOperationService.result(tasks, TaskOrigin.configured(), task);
        if (files != null) result.put("result", files);
        var transfer = transfers.findByTask(task.id(), TaskOrigin.configured()).orElse(null);
        if (transfer != null) {
            result.put("transfer", transfer);
            if (Set.of("ready", "delivered").contains(transfer.status()) && !transfer.downloadUrl().isBlank()) {
                result.put("file", Map.of("file_name", transfer.fileName(), "mime_type", transfer.mimeType(), "bytes", transfer.bytes(), "sha256", transfer.sha256(), "download_url", transfer.downloadUrl()));
            }
        }
        return result;
    }

    private CompletableFuture<ResponseEntity<?>> execute(java.util.concurrent.Callable<ResponseEntity<?>> call) {
        return async.submit(call).exceptionally(error -> {
            var cause = error; while (cause.getCause() != null) cause = cause.getCause();
            var status = cause instanceof SecurityException ? HttpStatus.UNAUTHORIZED : cause instanceof IllegalArgumentException ? HttpStatus.BAD_REQUEST : HttpStatus.INTERNAL_SERVER_ERROR;
            return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("error", status == HttpStatus.INTERNAL_SERVER_ERROR ? "file operation request failed" : SensitiveValueRedactor.redact(cause.getMessage() == null ? "request failed" : cause.getMessage())));
        });
    }

    private static ResponseEntity<?> ok(Object body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }

    private void authenticate(String auth) {
        var parts = auth == null ? new String[0] : auth.trim().split("\\s+", 2);
        if (parts.length != 2 || !"Bearer".equalsIgnoreCase(parts[0]) || !tokens.acceptsAdmin(parts[1])) throw new SecurityException("invalid admin credentials");
    }

    private static String retryKey(String key) { return key == null || key.isBlank() ? UUID.randomUUID().toString() : key; }

    public record OperationBody(Map<String, Object> request, String cwd,
                                @JsonProperty("idempotency_key") String idempotencyKey,
                                @JsonProperty("wait_ms") Integer waitMs) { }
    public record DownloadBody(@JsonProperty("source_path") String sourcePath, String cwd,
                               @JsonProperty("idempotency_key") String idempotencyKey) { }
}
