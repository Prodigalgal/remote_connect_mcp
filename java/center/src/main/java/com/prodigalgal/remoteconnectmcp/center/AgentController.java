package com.prodigalgal.remoteconnectmcp.center;

import com.prodigalgal.remoteconnectmcp.protocol.AgentMetadata;
import com.prodigalgal.remoteconnectmcp.protocol.JsonCodec;
import com.prodigalgal.remoteconnectmcp.protocol.PollRequest;
import com.prodigalgal.remoteconnectmcp.protocol.PollResponse;
import com.prodigalgal.remoteconnectmcp.protocol.ProtocolValidation;
import com.prodigalgal.remoteconnectmcp.protocol.SensitiveValueRedactor;
import com.prodigalgal.remoteconnectmcp.protocol.RegisterRequest;
import com.prodigalgal.remoteconnectmcp.protocol.RegisterResponse;
import com.prodigalgal.remoteconnectmcp.protocol.TransportNegotiation;
import com.prodigalgal.remoteconnectmcp.protocol.OutputRequest;
import com.prodigalgal.remoteconnectmcp.protocol.TaskUpdateRequest;
import com.prodigalgal.remoteconnectmcp.protocol.ArtifactRequest;
import com.prodigalgal.remoteconnectmcp.protocol.UpgradeStatusRequest;
import com.prodigalgal.remoteconnectmcp.protocol.FileTransferResponse;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import java.time.Duration;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ContentDisposition;

@RestController
@RequestMapping("/agent/v1")
public final class AgentController {
    private static final int MAX_OUTPUT_REQUEST_BASE64 = 256 * 1024;
    private static final int MAX_ARTIFACT_REQUEST_BASE64 = 12 * 1024 * 1024;
    private static final int MAX_AGENT_METADATA_HEADER = 16 * 1024;
    private static final long MAX_LONG_POLL_MS = 25_000L;
    private final AgentRegistry registry;
    private final TaskService tasks;
    private final UpgradeService upgrades;
    private final AgentConfigurationService configurations;
    private final CenterAsyncExecutor async;
    private final AgentWakeRegistry wakes;
    private final ArtifactTransferService transfers;

    @Autowired
    public AgentController(AgentRegistry registry, TaskService tasks, UpgradeService upgrades,
                           AgentConfigurationService configurations, CenterAsyncExecutor async,
                           ArtifactTransferService transfers,
                           org.springframework.beans.factory.ObjectProvider<AgentWakeRegistry> wakeProvider) {
        this.registry = registry;
        this.tasks = tasks;
        this.upgrades = upgrades;
        this.configurations = configurations;
        this.async = async;
        this.transfers = transfers;
        this.wakes = wakeProvider == null ? null : wakeProvider.getIfAvailable();
    }

    /** Compatibility constructor for direct protocol/controller tests. */
    AgentController(AgentRegistry registry, TaskService tasks, UpgradeService upgrades,
                    AgentConfigurationService configurations, CenterAsyncExecutor async) {
        this(registry, tasks, upgrades, configurations, async, null, null);
    }

    @PostMapping("/register")
    public CompletableFuture<ResponseEntity<?>> register(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                          @RequestBody(required = false) RegisterRequest request) {
        return execute(() -> {
            RegisterResponse response = registry.register(request, bearerValue(authorization));
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        });
    }

    @PostMapping("/poll")
    public CompletableFuture<ResponseEntity<?>> poll(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                      @RequestHeader(value = "X-Machine-ID", required = false) String machineId,
                                                      @RequestHeader(value = "X-Agent-Metadata", required = false) String metadataHeader,
                                                      @RequestHeader(value = TransportNegotiation.HEADER_CAPABILITIES, required = false) String transportCapabilities,
                                                      @RequestHeader(value = TransportNegotiation.HEADER_PREFERRED, required = false) String preferredTransport,
                                                      @RequestBody(required = false) PollRequest request,
                                                      @RequestParam(value = "wait_ms", defaultValue = "0") long waitMs) {
        return execute(() -> {
            var pollRequest = withHeaderMetadata(request, metadataHeader);
            var normalizedWait = normalizeLongPoll(waitMs);
            var response = pollUntilChange(machineId, bearerValue(authorization), pollRequest, normalizedWait);
            var selectedTransport = TransportNegotiation.select(transportCapabilities, preferredTransport,
                    java.util.Set.of(TransportNegotiation.HTTPS));
            if (normalizedWait > 0 && wakes != null) {
                return ResponseEntity.ok().header("X-RCM-Long-Poll", "accepted")
                        .header(TransportNegotiation.HEADER_CAPABILITIES, TransportNegotiation.SERVER_CAPABILITIES)
                        .header(TransportNegotiation.HEADER_SELECTED, selectedTransport).body(response);
            }
            return ResponseEntity.ok().header(TransportNegotiation.HEADER_CAPABILITIES, TransportNegotiation.SERVER_CAPABILITIES)
                    .header(TransportNegotiation.HEADER_SELECTED, selectedTransport).body(response);
        });
    }

    /**
     * The Go Agent sends its heartbeat metadata in an optional base64url
     * header so it can talk to older Centers whose JSON decoder only knows the
     * original PollRequest fields.  Decode it at the protocol boundary and
     * carry it through the normal JDBC heartbeat path.  Java Agents may send
     * the metadata in the JSON body; that body remains authoritative when it
     * is present.
     */
    static PollRequest withHeaderMetadata(PollRequest request, String metadataHeader) {
        var base = request == null ? new PollRequest(java.util.List.of(), 0, java.util.List.of()) : request;
        if (base.metadata() != null || metadataHeader == null || metadataHeader.isBlank()) return base;
        var encoded = metadataHeader.trim();
        if (encoded.length() > MAX_AGENT_METADATA_HEADER) {
            throw new IllegalArgumentException("X-Agent-Metadata header is too large");
        }
        try {
            var metadata = JsonCodec.read(Base64.getUrlDecoder().decode(encoded), AgentMetadata.class);
            ProtocolValidation.validateMetadata(metadata);
            return new PollRequest(base.runningTaskIds(), base.availableSlots(), base.availableCapabilities(),
                    metadata, base.configGeneration());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid X-Agent-Metadata header", exception);
        }
    }

    private PollResponse pollUntilChange(String machineId, String token, PollRequest request, long waitMs)
            throws Exception {
        if (waitMs <= 0 || wakes == null) {
            return pollOnce(machineId, token, request);
        }
        var deadline = System.nanoTime() + Duration.ofMillis(waitMs).toNanos();
        while (true) {
            // Capture before the row read. If a task is created between the
            // read and await, the sequence has changed and await returns
            // immediately instead of losing the wake-up race.
            var observed = wakes.version(machineId);
            try {
                var response = pollOnce(machineId, token, request);
                if (hasWork(response)) return response;
                var remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    // A missed wake must not make the deadline return the
                    // snapshot from before the event. One final authoritative
                    // poll is tied to this request boundary, not a timer loop.
                    return pollOnce(machineId, token, request);
                }
                wakes.awaitChange(machineId, observed, remaining);
                if (System.nanoTime() >= deadline) {
                    // Same deadline re-read for a notification lost in the
                    // database/listener path.
                    return pollOnce(machineId, token, request);
                }
            } finally {
                // Wait-state references are per request, not a machine cache.
                // Releasing here lets a removed/offline machine disappear
                // without a periodic cleanup sweep.
                wakes.release(machineId);
            }
        }
    }

    private PollResponse pollOnce(String machineId, String token, PollRequest request) {
        registry.poll(machineId, token, request);
        var config = configurations.current(machineId);
        if (config != null && config.generation() <= request.configGeneration()) config = null;
        // Fetch cancellation requests without claiming a new task. This keeps
        // upgrade offers ahead of queued work and guarantees a remote cancel
        // is observable even when the Agent has no free execution slot.
        var cancellations = tasks.poll(machineId, new PollRequest(request.runningTaskIds(), 0,
                request.availableCapabilities(), request.metadata(), request.configGeneration()));
        if (!cancellations.cancelTaskIds().isEmpty()) {
            return new PollResponse(cancellations.task(), cancellations.cancelTaskIds(), cancellations.upgrade(), config);
        }
        var upgrade = upgrades.offer(machineId, request);
        if (upgrade != null) return new PollResponse(null, java.util.List.of(), upgrade, config);
        var response = tasks.poll(machineId, request);
        return new PollResponse(response.task(), response.cancelTaskIds(), response.upgrade(), config);
    }

    private static boolean hasWork(PollResponse response) {
        return response != null && (response.task() != null || response.upgrade() != null
                || !response.cancelTaskIds().isEmpty() || response.config() != null);
    }

    private static long normalizeLongPoll(long waitMs) {
        if (waitMs < 0 || waitMs > MAX_LONG_POLL_MS) {
            throw new IllegalArgumentException("wait_ms must be between 0 and " + MAX_LONG_POLL_MS);
        }
        return waitMs;
    }

    @PostMapping("/upgrade/status")
    public CompletableFuture<ResponseEntity<?>> upgradeStatus(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                               @RequestHeader(value = "X-Machine-ID", required = false) String machineId,
                                                               @RequestBody(required = false) UpgradeStatusRequest request) {
        return execute(() -> {
            authenticate(machineId, authorization);
            return ResponseEntity.ok(upgrades.updateStatus(machineId, request));
        });
    }

    @PostMapping("/tasks/{taskId}/state")
    public CompletableFuture<ResponseEntity<?>> taskState(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                          @RequestHeader(value = "X-Machine-ID", required = false) String machineId,
                                                          @RequestHeader(value = "X-Task-Output-Truncated", required = false) String outputTruncated,
                                                          @RequestHeader(value = "X-Task-Attempt", required = false) String attemptHeader,
                                                          @PathVariable String taskId,
                                                          @RequestBody(required = false) TaskUpdateRequest request) {
        return execute(() -> {
            authenticate(machineId, authorization);
            var update = request;
            if (update != null && "1".equals(outputTruncated)) {
                update = new TaskUpdateRequest(update.status(), update.exitCode(), update.error(), update.startedAt(), update.finishedAt(), true);
            }
            return ResponseEntity.ok(tasks.updateState(machineId, taskId, update, parseAttempt(attemptHeader)));
        });
    }

    @PostMapping("/tasks/{taskId}/output")
    public CompletableFuture<ResponseEntity<?>> taskOutput(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                           @RequestHeader(value = "X-Machine-ID", required = false) String machineId,
                                                           @RequestHeader(value = "X-Task-Attempt", required = false) String attemptHeader,
                                                           @PathVariable String taskId,
                                                           @RequestBody(required = false) OutputRequest request) {
        return execute(() -> {
            authenticate(machineId, authorization);
            if (request == null || request.data() == null) {
                throw new IllegalArgumentException("output body is required");
            }
            if (request.data().length() > MAX_OUTPUT_REQUEST_BASE64) {
                throw new IllegalArgumentException("output request exceeds 256 KiB");
            }
            var data = Base64.getDecoder().decode(request.data());
            return ResponseEntity.ok(tasks.appendOutput(machineId, taskId, request.offset(), data, parseAttempt(attemptHeader)));
        });
    }

    @PostMapping("/tasks/{taskId}/artifact")
    public CompletableFuture<ResponseEntity<?>> taskArtifact(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                             @RequestHeader(value = "X-Machine-ID", required = false) String machineId,
                                                             @RequestHeader(value = "X-Task-Attempt", required = false) String attemptHeader,
                                                             @PathVariable String taskId,
                                                             @RequestBody(required = false) ArtifactRequest request) {
        return execute(() -> {
            authenticate(machineId, authorization);
            if (request == null || request.data() == null) {
                throw new IllegalArgumentException("artifact body is required");
            }
            if (request.data().length() > MAX_ARTIFACT_REQUEST_BASE64) {
                throw new IllegalArgumentException("artifact request exceeds 12 MiB");
            }
            var data = Base64.getDecoder().decode(request.data());
            return ResponseEntity.ok(tasks.appendArtifact(machineId, taskId, request.mimeType(), request.sha256(), data,
                    parseAttempt(attemptHeader)));
        });
    }

    @GetMapping("/transfers/{transferId}/content")
    public ResponseEntity<StreamingResponseBody> transferDownload(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Machine-ID", required = false) String machineId,
            @RequestHeader(value = "X-Task-Attempt", required = false) String attemptHeader,
            @RequestHeader(value = "Range", required = false) String rangeHeader,
            @PathVariable String transferId) {
        authenticate(machineId, authorization);
        var requestedOffset = parseRangeStart(rangeHeader);
        var download = transfers.openForAgent(machineId, transferId, parseAttempt(attemptHeader), requestedOffset);
        StreamingResponseBody body = output -> {
            try (var input = download.body()) {
                input.transferTo(output);
            }
        };
        var headers = new HttpHeaders();
        headers.setContentType(safeMediaType(download.mimeType()));
        headers.setContentLength(download.bytes());
        headers.setContentDisposition(ContentDisposition.attachment().filename(download.fileName()).build());
        headers.set("X-RCM-SHA256", download.sha256());
        headers.set("Accept-Ranges", "bytes");
        if (requestedOffset > 0) {
            headers.set("Content-Range", "bytes " + requestedOffset + "-" + (download.totalBytes() - 1)
                    + "/" + download.totalBytes());
            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT).headers(headers).body(body);
        }
        return ResponseEntity.ok().headers(headers).body(body);
    }

    /** Metadata is user supplied; keep an invalid MIME from breaking a download. */
    private static MediaType safeMediaType(String value) {
        if (value == null || value.isBlank()) return MediaType.APPLICATION_OCTET_STREAM;
        try {
            return MediaType.parseMediaType(value.trim());
        } catch (IllegalArgumentException ignored) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    @RequestMapping(value = "/transfers/{transferId}/content", method = RequestMethod.HEAD)
    public ResponseEntity<Void> transferResumeProbe(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Machine-ID", required = false) String machineId,
            @RequestHeader(value = "X-Task-Attempt", required = false) String attemptHeader,
            @PathVariable String transferId) {
        authenticate(machineId, authorization);
        var resume = transfers.resumeFromAgent(machineId, transferId, parseAttempt(attemptHeader));
        var headers = new HttpHeaders();
        headers.set("X-RCM-Resume-Offset", Long.toString(resume.offset()));
        headers.set("X-RCM-Expected-Bytes", Long.toString(resume.expectedBytes()));
        headers.set("X-RCM-Transfer-Status", resume.status());
        return ResponseEntity.ok().headers(headers).build();
    }

    @PutMapping("/transfers/{transferId}/content")
    public CompletableFuture<ResponseEntity<?>> transferUpload(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Machine-ID", required = false) String machineId,
            @RequestHeader(value = "X-Task-Attempt", required = false) String attemptHeader,
            @RequestHeader(value = "X-RCM-Expected-SHA256", required = false) String expectedSha256,
            @RequestHeader(value = "X-RCM-Expected-Bytes", required = false) String expectedBytesHeader,
            @RequestHeader(value = "Content-Range", required = false) String contentRangeHeader,
            @RequestHeader(value = "X-RCM-File-Name", required = false) String fileName,
            @RequestHeader(value = "Content-Type", required = false) String mimeType,
            @PathVariable String transferId, HttpServletRequest request) {
        return execute(() -> {
            authenticate(machineId, authorization);
            var length = request.getContentLengthLong();
            var range = parseContentRange(contentRangeHeader);
            if (range != null) {
                var rangeLength = range.end() - range.start() + 1;
                if (rangeLength < 0 || rangeLength > ArtifactTransferService.MAX_BYTES
                        || (length >= 0 && length != rangeLength)) {
                    throw new IllegalArgumentException("Content-Range does not match the request body");
                }
                var response = transfers.receiveFromAgentChunk(machineId, transferId, request.getInputStream(),
                        length < 0 ? rangeLength : length, range.start(), range.total(), expectedSha256, fileName,
                        mimeType, parseAttempt(attemptHeader));
                return ResponseEntity.ok(response);
            }
            if (expectedBytesHeader != null && !expectedBytesHeader.isBlank()) {
                long declared;
                try {
                    declared = Long.parseLong(expectedBytesHeader.trim());
                } catch (NumberFormatException exception) {
                    throw new IllegalArgumentException("X-RCM-Expected-Bytes must be a non-negative integer", exception);
                }
                if (declared < 0 || (length >= 0 && length != declared)) {
                    throw new IllegalArgumentException("declared transfer size does not match the request body");
                }
                if (length < 0) length = declared;
            }
            var response = transfers.receiveFromAgent(machineId, transferId, request.getInputStream(), length,
                    expectedSha256, fileName, mimeType, parseAttempt(attemptHeader));
            return ResponseEntity.ok(response);
        });
    }

    @PostMapping("/transfers/{transferId}/ack")
    public CompletableFuture<ResponseEntity<?>> transferAcknowledgement(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Machine-ID", required = false) String machineId,
            @RequestHeader(value = "X-Task-Attempt", required = false) String attemptHeader,
            @PathVariable String transferId,
            @RequestBody(required = false) FileTransferResponse acknowledgement) {
        return execute(() -> {
            authenticate(machineId, authorization);
            return ResponseEntity.ok(transfers.acknowledgeFromAgent(machineId, transferId, acknowledgement,
                    parseAttempt(attemptHeader)));
        });
    }

    private CompletableFuture<ResponseEntity<?>> execute(java.util.concurrent.Callable<ResponseEntity<?>> action) {
        return async.submit(action).exceptionally(failure -> error(unwrap(failure)));
    }

    private void authenticate(String machineId, String authorization) {
        if (!registry.acceptsAgent(machineId, bearerValue(authorization))) {
            throw new SecurityException("invalid agent credentials");
        }
    }

    private static String bearerValue(String authorization) {
        if (authorization == null) {
            return "";
        }
        var parts = authorization.trim().split("\\s+", 2);
        return parts.length == 2 && "Bearer".equalsIgnoreCase(parts[0]) ? parts[1].trim() : "";
    }

    private static Integer parseAttempt(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            var parsed = Integer.parseInt(value.trim());
            if (parsed < 1) throw new IllegalArgumentException("X-Task-Attempt must be a positive integer");
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("X-Task-Attempt must be a positive integer", exception);
        }
    }

    private static long parseRangeStart(String value) {
        if (value == null || value.isBlank()) return 0L;
        var trimmed = value.trim();
        if (!trimmed.matches("bytes=\\d+-")) {
            throw new IllegalArgumentException("Range must use the bytes=start- form");
        }
        try {
            return Long.parseLong(trimmed.substring("bytes=".length(), trimmed.length() - 1));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Range start is invalid", exception);
        }
    }

    private static ContentRange parseContentRange(String value) {
        if (value == null || value.isBlank()) return null;
        var trimmed = value.trim();
        var matcher = java.util.regex.Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)").matcher(trimmed);
        if (!matcher.matches()) throw new IllegalArgumentException("Content-Range must use bytes start-end/total");
        try {
            var start = Long.parseLong(matcher.group(1));
            var end = Long.parseLong(matcher.group(2));
            var total = Long.parseLong(matcher.group(3));
            if (end < start || total <= end) throw new IllegalArgumentException("Content-Range values are invalid");
            return new ContentRange(start, end, total);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Content-Range values are invalid", exception);
        }
    }

    private record ContentRange(long start, long end, long total) { }

    private static ResponseEntity<Map<String, String>> error(Throwable exception) {
        if (exception instanceof SecurityException) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(java.util.Map.of("error", message(exception)));
        }
        if (exception instanceof ArtifactTransferService.TransferTemporaryException) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(java.util.Map.of("error", message(exception)));
        }
        if (exception instanceof IllegalArgumentException) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", message(exception)));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(java.util.Map.of("error", "internal error"));
    }

    private static String message(Throwable exception) {
        var message = exception == null || exception.getMessage() == null || exception.getMessage().isBlank()
                ? "request failed" : exception.getMessage();
        return SensitiveValueRedactor.redact(message);
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure instanceof CompletionException && failure.getCause() != null) {
            return unwrap(failure.getCause());
        }
        return failure;
    }
}
