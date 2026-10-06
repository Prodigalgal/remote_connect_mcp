package com.prodigalgal.remotecontrolmcp.center;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityValidator;
import io.modelcontextprotocol.spec.McpSchema;
import com.prodigalgal.remotecontrolmcp.protocol.ArtifactRequest;
import com.prodigalgal.remotecontrolmcp.protocol.ArtifactResponse;
import com.prodigalgal.remotecontrolmcp.protocol.AgentConfigUpdate;
import com.prodigalgal.remotecontrolmcp.protocol.AgentMetadata;
import com.prodigalgal.remotecontrolmcp.protocol.AgentRuntimeDescriptor;
import com.prodigalgal.remotecontrolmcp.protocol.OutputRequest;
import com.prodigalgal.remotecontrolmcp.protocol.OutputResponse;
import com.prodigalgal.remotecontrolmcp.protocol.PollRequest;
import com.prodigalgal.remotecontrolmcp.protocol.PollResponse;
import com.prodigalgal.remotecontrolmcp.protocol.RegisterRequest;
import com.prodigalgal.remotecontrolmcp.protocol.RegisterResponse;
import com.prodigalgal.remotecontrolmcp.protocol.SensitiveValueRedactor;
import com.prodigalgal.remotecontrolmcp.protocol.TaskCommand;
import com.prodigalgal.remotecontrolmcp.protocol.TaskKind;
import com.prodigalgal.remotecontrolmcp.protocol.TaskUpdateRequest;
import com.prodigalgal.remotecontrolmcp.protocol.UpgradeArtifact;
import com.prodigalgal.remotecontrolmcp.protocol.UpgradePlan;
import com.prodigalgal.remotecontrolmcp.protocol.UpgradeStatusRequest;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;

/** Streamable HTTP MCP endpoint with a deliberately bounded tool surface. */
@Configuration
@RegisterReflectionForBinding({McpConfiguration.MachinesCoreArgs.class, McpConfiguration.MachineInfoCoreArgs.class,
        McpConfiguration.CommandCoreArgs.class, McpConfiguration.DesktopCoreArgs.class,
        McpConfiguration.BrowserCoreArgs.class, McpConfiguration.TaskCancelCoreArgs.class,
        McpConfiguration.ArtifactFileCore.class, McpConfiguration.ArtifactPutCoreArgs.class,
        McpConfiguration.ArtifactGetCoreArgs.class, McpConfiguration.ArtifactReadCoreArgs.class,
        // accessors declared up front (otherwise initialize returns HTTP 500).
        McpSchema.JSONRPCRequest.class, McpSchema.JSONRPCResponse.class,
        McpSchema.JSONRPCResponse.JSONRPCError.class, McpSchema.JSONRPCNotification.class,
        McpSchema.InitializeRequest.class, McpSchema.InitializeResult.class,
        McpSchema.ClientCapabilities.class, McpSchema.Implementation.class,
        McpSchema.ServerCapabilities.class, McpSchema.ListToolsResult.class,
        McpSchema.CallToolRequest.class, McpSchema.CallToolResult.class,
        McpSchema.Tool.class, McpSchema.ToolAnnotations.class,
        McpSchema.Content.class, McpSchema.TextContent.class,
        McpSchema.ImageContent.class, McpSchema.AudioContent.class,
        McpSchema.EmbeddedResource.class, McpSchema.ResourceLink.class,
        McpSchema.Resource.class, McpSchema.ResourceContent.class,
        McpSchema.ResourceContents.class, McpSchema.TextResourceContents.class,
        McpSchema.BlobResourceContents.class, McpSchema.ReadResourceRequest.class,
        McpSchema.ReadResourceResult.class,
        McpSchema.Icon.class, McpSchema.PaginatedRequest.class,
        McpSchema.PaginatedResult.class,
        ArtifactRequest.class, ArtifactResponse.class, AgentMetadata.class, AgentRuntimeDescriptor.class,
        com.prodigalgal.remotecontrolmcp.protocol.ExecutionContract.class,
        com.prodigalgal.remotecontrolmcp.protocol.ExecutionContract.Budget.class,
        AgentConfigUpdate.class,
        OutputRequest.class, OutputResponse.class, PollRequest.class, PollResponse.class,
        RegisterRequest.class, RegisterResponse.class, TaskCommand.class,
        TaskCommand.DesktopAction.class, com.prodigalgal.remotecontrolmcp.protocol.FileTransferAction.class,
        com.prodigalgal.remotecontrolmcp.protocol.FileTransferResponse.class, TaskUpdateRequest.class,
        com.prodigalgal.remotecontrolmcp.protocol.TaskProgressUpdate.class,
        UpgradeArtifact.class, UpgradePlan.class, UpgradeStatusRequest.class,
        com.prodigalgal.remotecontrolmcp.protocol.UpgradeComponentPlan.class,
        // Admin API projections/requests are also Java records.  They are
        // serialized outside the MCP handler (for example /machines and
        // /tasks), so Native Image must retain their record component
        // accessors as well.
        MachineView.class, TaskView.class, UpgradeCampaignView.class,
        UpgradeTargetView.class, AgentConfigUpdateRequest.class,
        CreateTaskRequest.class, AdminCreateTaskRequest.class, CreateUpgradeCampaignRequest.class,
        AuditEventView.class,
        AdminController.IssueEnrollmentRequest.class, AdminController.IssueMcpTokenRequest.class,
        AdminController.SessionCloseRequest.class,
        McpPrincipalService.IssueRequest.class, McpPrincipalService.IssuedToken.class,
        McpTokenView.class,
        ExecutionSessionService.SessionView.class,
        McpQuotaService.QuotaView.class,
        ArtifactTransferService.TransferCreated.class, ArtifactTransferService.TransferDescriptor.class,
        ArtifactTransferService.AgentDownload.class, ArtifactTransferService.PublicArtifact.class,
        ArtifactTransferService.ArtifactAdminView.class,
        McpConfiguration.ArtifactFileCore.class, McpConfiguration.ArtifactPutCoreArgs.class,
        McpConfiguration.ArtifactGetCoreArgs.class, McpConfiguration.ArtifactReadCoreArgs.class,
        TaskService.ArtifactGcResult.class, TaskService.TaskRetentionGcResult.class,
        McpOAuthService.TokenResponse.class})
public class McpConfiguration {
    /** Stable Apps SDK resource URI; changing it would require reconnecting every client. */
    static final String ARTIFACT_VIEWER_URI = "ui://remote-control-mcp/artifact-viewer-v1.html";
    // MCP inventory responses are intentionally smaller than the Console
    // pages.  The model normally only needs an identifier and a few routing
    // hints; detailed runtime data is an explicit machines(detail) follow-up.
    private static final int MAX_MACHINE_PAGE = 25;
    private static final int MAX_OUTPUT_PAGE = 64 * 1024;
    private static final int DEFAULT_OUTPUT_PAGE = 16 * 1024;
    private static final int MAX_MCP_JSON_CHARS = 192 * 1024;
    // Keep ordinary screenshots/camera images useful in the ChatGPT
    // conversation without allowing a single result to consume the whole MCP
    // context window.  Larger files remain object-store backed handles.
    private static final int MAX_INLINE_IMAGE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_INLINE_CONTENT_BYTES = 5 * 1024 * 1024;
    private static final int DEFAULT_INLINE_ARTIFACT_WAIT_MS = 15000;
    private static final int DEFAULT_EXPLICIT_INLINE_WAIT_MS = 20000;
    private static final int ARTIFACT_URL_WAIT_MS = 30000;

    @Bean
    public HttpServletStreamableServerTransportProvider mcpTransport(McpAuthenticationService authentication) {
        ServerTransportSecurityValidator security = headers -> {
            var authorization = headers.entrySet().stream()
                    .filter(entry -> "authorization".equalsIgnoreCase(entry.getKey()))
                    .flatMap(entry -> entry.getValue().stream())
                    .findFirst()
                    .orElse("");
            var parts = authorization.trim().split("\\s+", 2);
            var bearer = parts.length == 2 && "Bearer".equalsIgnoreCase(parts[0]) ? parts[1].trim() : "";
            if (authentication.resolve(bearer).isEmpty()) {
                throw new ServerTransportSecurityException(401, "invalid MCP bearer token");
            }
        };
        return HttpServletStreamableServerTransportProvider.builder()
                .mcpEndpoint("/mcp")
                .disallowDelete(true)
                .maxRequestSize(256 * 1024)
                // The SDK carries this immutable transport metadata into the
                // async tool exchange, so authorization never depends on a
                // ThreadLocal surviving a scheduler hop.
                .contextExtractor((HttpServletRequest request) -> {
                    var principal = authentication.resolve(request.getHeader("Authorization") == null
                            ? "" : bearerValue(request.getHeader("Authorization")))
                            // The transport invokes the security validator immediately
                            // before the extractor.  A checked
                            // ServerTransportSecurityException cannot cross the
                            // extractor's functional interface, so keep this as a
                            // fail-closed invariant check; invalid HTTP requests
                            // have already been returned as 401 by the validator.
                            .orElseThrow(() -> new IllegalStateException("invalid MCP bearer token"));
                    return McpTransportContext.create(Map.of("rcm.principal", principal));
                })
                .securityValidator(security)
                .build();
    }

    @Bean
    public ServletRegistrationBean<HttpServlet> mcpServlet(HttpServletStreamableServerTransportProvider transport) {
        var registration = new ServletRegistrationBean<HttpServlet>(transport, "/mcp");
        registration.setName("remoteConnectMcp");
        registration.setLoadOnStartup(1);
        return registration;
    }

    @Bean
    public ExecutorService mcpVirtualThreadExecutor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("mcp-vt-", 1).factory());
    }

    @Bean
    public McpAsyncServer mcpServer(HttpServletStreamableServerTransportProvider transport,
                                    AgentRegistry agents,
                                    TaskService tasks,
                                    McpAccessService access,
                                    ArtifactTransferService transfers,
                                    McpConversationService conversations,
                                    ExecutorService mcpVirtualThreadExecutor,
                                    CenterOAuthConfig oauth,
                                    @Value("${rcm.version:dev}") String version) {
        var server = McpServer.async(transport)
                .serverInfo("remote-control-mcp-center", version)
                .instructions("Use machines to choose a stable machine ID. Execution tools create durable tasks: wait_ms=0 returns immediately; a positive value waits briefly without canceling the task at deadline. Use limit to bound initial output. New calls execute new work; use one explicit idempotency_key only for retries of the same invocation. Once a task ID is known, continue with task_read. For status-only observation use include_output=false with change_seq; for new logs pass the last next_cursor; for failure logs use tail_bytes. task_read returns image metadata by default; include_artifact=true fetches the image. Browser include_snapshot=true observes the page after the action; detail=true adds bounded diagnostics. Artifact inline text is paged with cursor/limit; file handles retain the full file. wait_ms takes precedence over delivery_mode's default wait. cwd is a working-directory hint, not a sandbox. Center enforces identity, machine authorization, task ownership and resource limits.")
                .strictToolNameValidation(true)
                .validateToolInputs(true)
                .requestTimeout(Duration.ofSeconds(30))
                .resources(artifactViewerResource(transfers))
                .tools(modelToolSpecs(agents, tasks, access, transfers, conversations,
                        mcpVirtualThreadExecutor, oauth))
                .build();
        return server;
    }

    /**
     * Minimal Apps SDK component for file objects.  It is a resource, not a
     * tool response, so the normal MCP transcript only receives the compact
     * artifact handle while a capable host can render/download the file on
     * demand. Hosts that do not render MCP Apps still receive the compact
     * artifact handle in the normal tool result.
     */
    private static McpServerFeatures.AsyncResourceSpecification artifactViewerResource(ArtifactTransferService transfers) {
        var domains = viewerDomains(transfers == null ? "" : transfers.publicBaseUrl());
        var standardCsp = new LinkedHashMap<String, Object>();
        standardCsp.put("connectDomains", domains);
        standardCsp.put("resourceDomains", domains);
        standardCsp.put("frameDomains", domains);
        var resourceMeta = new LinkedHashMap<String, Object>();
        var resourceUi = new LinkedHashMap<String, Object>();
        resourceUi.put("csp", standardCsp);
        if (!domains.isEmpty()) resourceUi.put("domain", domains.getFirst());
        resourceMeta.put("ui", resourceUi);
        var resource = McpSchema.Resource.builder(ARTIFACT_VIEWER_URI, "Remote Control Artifact Viewer")
                .description("Render an artifact file object returned by artifact operation=read")
                .mimeType("text/html;profile=mcp-app")
                .meta(resourceMeta)
                .build();
        // MCP Apps hosts consume the standard metadata from the resource
        // contents as well as the discovery entry.
        var contentMeta = new LinkedHashMap<String, Object>();
        var contentUi = new LinkedHashMap<String, Object>();
        contentUi.put("csp", standardCsp);
        if (!domains.isEmpty()) contentUi.put("domain", domains.getFirst());
        contentMeta.put("ui", contentUi);
        return new McpServerFeatures.AsyncResourceSpecification(resource, (exchange, request) ->
                Mono.fromSupplier(() -> McpSchema.ReadResourceResult.builder(List.of(
                        McpSchema.TextResourceContents.builder(ARTIFACT_VIEWER_URI, artifactViewerHtml())
                                .mimeType("text/html;profile=mcp-app").meta(contentMeta).build())).build()));
    }

    private static String artifactViewerHtml() {
        try (var stream = McpConfiguration.class.getResourceAsStream("/mcp/artifact-viewer-v1.html")) {
            if (stream == null) throw new IllegalStateException("artifact viewer resource is missing from the Center image");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("artifact viewer resource cannot be read", exception);
        }
    }

    private static List<String> viewerDomains(String rawPublicUrl) {
        if (rawPublicUrl == null || rawPublicUrl.isBlank()) return List.of();
        try {
            var uri = URI.create(rawPublicUrl.trim());
            var host = uri.getHost();
            if (host == null || host.isBlank()) return List.of();
            var port = uri.getPort();
            var origin = uri.getScheme() + "://" + host + (port > 0 ? ":" + port : "");
            return List.of(origin);
        } catch (IllegalArgumentException ignored) {
            return List.of();
        }
    }

    /**
     * Final model-facing surface.  The handlers deliberately share the
     * existing Center services, but their public arguments are strict,
     * semantic envelopes instead of implementation-level scheduling fields.
     */
    private static List<McpServerFeatures.AsyncToolSpecification> modelToolSpecs(AgentRegistry agents, TaskService tasks,
                                                                                    McpAccessService access,
                                                                                    ArtifactTransferService transfers,
                                                                                    McpConversationService conversations,
                                                                                    ExecutorService mcpVirtualThreadExecutor,
                                                                                    CenterOAuthConfig oauth) {
        var scheduler = Schedulers.fromExecutor(mcpVirtualThreadExecutor);
        // MCP Apps is the canonical UI contract.  ChatGPT consumes the same
        // nested metadata as other MCP Apps hosts; do not publish the old
        // flat resource metadata or ChatGPT-only output-template alias.  The
        // file parameter declaration is equally important: it tells
        // ChatGPT to inject its host file object (download_url + file_id)
        // instead of exposing an inaccessible /mnt/data path to the model.
        var artifactMeta = new LinkedHashMap<String, Object>();
        // Files are data results. Do not mount an iframe for pending tasks,
        // failures or every read; return standard MCP content to the host.
        artifactMeta.put("openai/fileParams", List.of("file"));
        return List.of(
                tool("machines", "Discover registered machines or fetch one bounded machine detail. Returns stable IDs and compact capability summaries.",
                        machinesModelSchema(),
                        (exchange, request) -> { requireScope(exchange, "mcp:read"); return machinesModel(agents, access, origin(exchange, conversations, request), request); }, scheduler, oauth),
                tool("command", "Run a shell command as the Agent service account. USERPROFILE/HOME may differ from the desktop user's directory; machines(detail).environment reports known paths. wait_ms=0 returns a task immediately; positive wait_ms returns bounded output if ready. Use task_read to continue.",
                        commandModelSchema(),
                        (exchange, request) -> { requireScope(exchange, "mcp:execute"); return commandModel(agents, tasks, access, origin(exchange, conversations, request), request); }, scheduler, oauth),
                tool("desktop", "Control a desktop-capable user session. wait_ms=0 returns a task immediately; a positive wait_ms returns a bounded result if ready. Use task_read to continue.",
                        desktopModelSchema(),
                        (exchange, request) -> { requireScope(exchange, "mcp:execute"); return desktopModel(agents, tasks, access, origin(exchange, conversations, request), request); }, scheduler, oauth),
                tool("browser", "Run one structured browser action. wait_ms=0 returns a task immediately; a positive wait_ms returns a bounded result if ready. Use task_read to continue.",
                        browserModelSchema(),
                        (exchange, request) -> { requireScope(exchange, "mcp:execute"); return browserModel(agents, tasks, access, origin(exchange, conversations, request), request); }, scheduler, oauth),
                tool("artifact", "put uploads a ChatGPT file to a machine; get retrieves one regular file, not a directory; read inspects an existing artifact_id or transfer_id. List or search directories with command first. wait_ms bounds put/get waiting; cursor and limit select inline text only.",
                        artifactModelSchema(), artifactMeta,
                        (exchange, request) -> { requireScope(exchange,
                                "read".equalsIgnoreCase(asString(modelArguments(request).get("operation"))) ? "mcp:read" : "mcp:execute");
                            return artifactModel(agents, tasks, access, transfers, origin(exchange, conversations, request), request); }, scheduler, oauth),
                tool("task_read", "Observe one durable task. include_output=false reads status only; cursor reads new logs; tail_bytes reads the end. include_artifact=true fetches an image; detail=true includes execution details.",
                        taskReadModelSchema(),
                        (exchange, request) -> { requireScope(exchange, "mcp:read"); return taskReadModel(tasks, access, transfers, origin(exchange, conversations, request), request); }, scheduler, oauth),
                tool("task_cancel", "Cancel one queued or running task owned by the current principal on an authorized machine. Reconnecting does not lose the task.",
                        taskCancelModelSchema(),
                        (exchange, request) -> { requireScope(exchange, "mcp:execute"); return taskCancelModel(tasks, access, transfers, origin(exchange, conversations, request), request); }, scheduler, oauth));
    }

    private static McpServerFeatures.AsyncToolSpecification tool(String name, String description, Map<String, Object> schema,
                                                                   BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler,
                                                                   reactor.core.scheduler.Scheduler scheduler,
                                                                   CenterOAuthConfig oauth) {
        return tool(name, description, schema, Map.of(), handler, scheduler, oauth);
    }

    private static McpServerFeatures.AsyncToolSpecification tool(String name, String description, Map<String, Object> schema,
                                                                   Map<String, Object> meta,
                                                                   BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler,
                                                                   reactor.core.scheduler.Scheduler scheduler,
                                                                   CenterOAuthConfig oauth) {
        var annotations = McpSchema.ToolAnnotations.builder()
                .readOnlyHint(name.equals("machines") || name.equals("task_read"))
                .idempotentHint(name.equals("machines") || name.equals("task_read") || name.equals("task_cancel"))
                .destructiveHint(name.equals("command") || name.equals("desktop") || name.equals("browser")
                        || name.equals("task_cancel") || name.equals("artifact"))
                .openWorldHint(name.equals("command") || name.equals("browser")
                        || name.equals("artifact"))
                .build();
        var toolMeta = new LinkedHashMap<String, Object>();
        if (meta != null) toolMeta.putAll(meta);
        // MCP Apps clients that implement the current OpenAI reference read
        // this compatibility mirror from _meta.  The Java MCP SDK 2.0.1 does
        // not yet expose a top-level securitySchemes builder field, so keeping
        // the declaration here is the wire-compatible option without forking
        // the SDK.  The Center still validates the token and scopes on every
        // request; this metadata is never an authorization decision.
        if (oauth != null && oauth.isConfigured()) {
            toolMeta.put("securitySchemes", List.of(Map.of("type", "oauth2",
                    "scopes", oauthScopesForTool(name))));
        }
        var toolBuilder = McpSchema.Tool.builder(name)
                .description(description)
                .inputSchema(schema)
                .annotations(annotations)
                .meta(toolMeta);
        if (Set.of("machines", "command", "desktop", "browser", "artifact", "task_read", "task_cancel").contains(name)) {
            toolBuilder.outputSchema(modelOutputSchema(name));
        }
        var tool = toolBuilder.build();
        return McpServerFeatures.AsyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> Mono.fromCallable(() -> handler.apply(exchange, request)).subscribeOn(scheduler))
                .build();
    }

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        var result = new LinkedHashMap<String, Object>();
        result.put("type", "object");
        result.put("properties", properties);
        result.put("required", required);
        result.put("additionalProperties", false);
        return result;
    }

    private static Map<String, Object> string(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> integer(String description) {
        return Map.of("type", "integer", "description", description);
    }

    private static Map<String, Object> object(String description) {
        return Map.of("type", "object", "description", description, "additionalProperties", Map.of("type", "string"));
    }

    private static Map<String, Object> objectArray(String description) {
        return Map.of("type", "array", "description", description, "items", Map.of("type", "string"));
    }

    private static Map<String, Object> modelSchema(Map<String, Object> properties, List<String> required) {
        var result = new LinkedHashMap<String, Object>();
        result.put("type", "object");
        result.put("properties", properties);
        result.put("required", required);
        result.put("additionalProperties", false);
        return result;
    }

    private static Map<String, Object> modelString(String description, int min, int max) {
        return Map.of("type", "string", "description", description, "minLength", min, "maxLength", max);
    }

    private static Map<String, Object> modelEnum(String description, List<String> values) {
        return Map.of("type", "string", "description", description, "enum", values);
    }

    private static Map<String, Object> modelInteger(String description, long min, long max) {
        return Map.of("type", "integer", "description", description, "minimum", min, "maximum", max);
    }

    private static Map<String, Object> modelBoolean(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    static Map<String, Object> modelBrowserRequestSchema() {
        var properties = Map.<String, Object>ofEntries(
                Map.entry("action", modelEnum("browser action", List.of("navigate", "observe", "click", "fill", "select", "press", "wait", "extract", "download", "screenshot"))),
                Map.entry("url", modelString("HTTP(S) navigation URL, at most 4096 UTF-8 bytes", 1, 4096)),
                Map.entry("ref", modelString("rcm-ref-v1 snapshot reference", 1, 2048)),
                Map.entry("selector", modelString("bounded CSS/text selector", 1, 2048)),
                Map.entry("text", modelString("fill text, at most 65536 UTF-8 bytes; empty clears the field", 0, 65536)),
                Map.entry("value", modelString("select value, at most 2048 UTF-8 bytes", 1, 2048)),
                Map.entry("keys", Map.of("type", "array", "description", "semantic key names", "items", modelString("key", 1, 64), "minItems", 1, "maxItems", 16)),
                Map.entry("wait_ms", modelInteger("wait action duration; distinct from the tool's response wait", 0, 15000)),
                Map.entry("timeout_ms", modelInteger("action timeout; task timeout remains the total execution limit", 1, 300000)),
                Map.entry("include_snapshot", modelBoolean("observe the page after this action")),
                Map.entry("detail", modelBoolean("include bounded network and console diagnostics")));
        var result = modelSchema(properties, List.of("action"));
        var branches = new java.util.ArrayList<Map<String, Object>>();
        for (var action : List.of("navigate", "observe", "click", "fill", "select", "press", "wait", "extract", "download", "screenshot")) {
            var fields = switch (action) {
                case "navigate" -> List.of("url");
                case "click", "extract", "download" -> List.of("ref", "selector");
                case "fill" -> List.of("ref", "selector", "text");
                case "select" -> List.of("ref", "selector", "value");
                case "press" -> List.of("ref", "selector", "keys");
                case "wait" -> List.of("wait_ms");
                default -> List.<String>of();
            };
            var required = switch (action) {
                case "navigate" -> List.of("url");
                case "fill" -> List.of("text");
                case "select" -> List.of("value");
                case "press" -> List.of("keys");
                case "wait" -> List.of("wait_ms");
                default -> List.<String>of();
            };
            var branch = operationBranch(properties, "action", action, required, fields,
                    List.of("timeout_ms", "include_snapshot", "detail"));
            if (fields.contains("ref")) branch.put("oneOf", exclusiveFields("ref", "selector"));
            branches.add(whenOperation("action", action, branch));
        }
        result.put("allOf", branches);
        return result;
    }

    private static Map<String, Object> modelFileObjectSchema() {
        return modelSchema(Map.ofEntries(
                Map.entry("download_url", modelString("HTTPS URL the host exposes for one-time download", 8, 8192)),
                Map.entry("file_id", modelString("host file identifier", 1, 512)),
                Map.entry("file_name", modelString("original file name", 1, 512)),
                Map.entry("mime_type", modelString("MIME type", 1, 256)),
                Map.entry("bytes", modelInteger("optional byte size", 0L, 4L * 1024 * 1024 * 1024)),
                Map.entry("sha256", Map.of("type", "string", "description", "optional SHA-256", "pattern", "^[A-Fa-f0-9]{64}$"))),
                List.of("download_url", "file_id"));
    }

    static Map<String, Object> machinesModelSchema() {
        var properties = Map.<String, Object>ofEntries(
                Map.entry("operation", modelEnum("inventory operation", List.of("list", "detail"))),
                Map.entry("machine_id", modelString("stable machine identifier", 1, 180)),
                Map.entry("offset", modelInteger("zero-based page offset", 0, 1000000)),
                Map.entry("limit", modelInteger("page size", 1, MAX_MACHINE_PAGE)));
        var result = modelSchema(properties, List.of("operation"));
        result.put("allOf", List.of(
                whenOperation("operation", "list", operationBranch(properties, "operation", "list", List.of(), List.of("offset", "limit"), List.of())),
                whenOperation("operation", "detail", operationBranch(properties, "operation", "detail", List.of("machine_id"), List.of("machine_id"), List.of()))));
        return result;
    }

    static Map<String, Object> commandModelSchema() {
        return modelSchema(Map.ofEntries(
                Map.entry("machine_id", modelString("target machine identifier", 1, 180)),
                Map.entry("command", modelString("shell command", 1, 65536)),
                Map.entry("cwd", modelString("working directory on target machine", 1, 4096)),
                Map.entry("env", Map.of("type", "object", "description", "optional non-secret environment map", "additionalProperties", modelString("environment value", 0, 8192))),
                Map.entry("timeout_seconds", modelInteger("0 means Agent default", 0, 86400)),
                Map.entry("wait_ms", modelInteger("0 returns a task immediately; a positive value waits briefly for bounded output without canceling the task", 0, 15000)),
                Map.entry("limit", modelInteger("initial output byte budget; default 16384", 1, MAX_OUTPUT_PAGE)),
                Map.entry("idempotency_key", Map.of("type", "string", "description", "optional stable retry key", "minLength", 8, "maxLength", 128, "pattern", "^[A-Za-z0-9._:-]+$"))),
                List.of("machine_id", "command"));
    }

    static Map<String, Object> desktopModelSchema() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("operation", modelEnum("semantic desktop operation", List.of("screenshot", "screenshot_region", "screens", "windows", "launch", "click", "double_click", "right_click", "move", "drag", "shortcut", "type", "clipboard_read", "clipboard_write", "focus")));
        properties.put("machine_id", modelString("desktop-capable machine identifier", 1, 180));
        properties.put("cwd", modelString("working directory on target machine", 1, 4096));
        properties.put("executable", modelString("literal application for launch", 1, 4096));
        properties.put("args", Map.of("type", "array", "description", "literal launch arguments", "items", modelString("argument", 0, 4096), "maxItems", 128));
        properties.put("text", modelString("text for type or clipboard", 0, 65536));
        properties.put("keys", Map.of("type", "array", "description", "semantic shortcut keys", "items", modelString("key", 1, 64), "minItems", 1, "maxItems", 16));
        properties.put("x", modelInteger("screen x or region left", -100000, 100000));
        properties.put("y", modelInteger("screen y or region top", -100000, 100000));
        properties.put("x2", modelInteger("screen x endpoint or region right", -100000, 100000));
        properties.put("y2", modelInteger("screen y endpoint or region bottom", -100000, 100000));
        properties.put("duration_ms", modelInteger("drag duration", 0, 10000));
        properties.put("screen", modelInteger("monitor index", 0, 32));
        properties.put("window_title", modelString("partial window title", 1, 512));
        properties.put("key", modelString("single key name; shortcut uses keys[]", 1, 64));
        properties.put("timeout_seconds", modelInteger("task timeout", 0, 86400));
        properties.put("wait_ms", modelInteger("bounded synchronous wait", 0, 15000));
        properties.put("limit", modelInteger("initial output byte budget; default 16384", 1, MAX_OUTPUT_PAGE));
        properties.put("idempotency_key", Map.of("type", "string", "description", "optional stable retry key", "minLength", 8, "maxLength", 128, "pattern", "^[A-Za-z0-9._:-]+$"));
        var result = modelSchema(properties, List.of("operation", "machine_id"));
        var branches = new java.util.ArrayList<Map<String, Object>>();
        for (var operation : List.of("screenshot", "screenshot_region", "screens", "windows", "launch", "click",
                "double_click", "right_click", "move", "drag", "shortcut", "type", "clipboard_read", "clipboard_write", "focus")) {
            var fields = switch (operation) {
                case "screenshot" -> List.of("screen");
                case "launch" -> List.of("executable", "args");
                case "click", "double_click", "right_click", "move" -> List.of("x", "y");
                case "drag" -> List.of("x", "y", "x2", "y2", "duration_ms");
                case "screenshot_region" -> List.of("x", "y", "x2", "y2");
                case "shortcut" -> List.of("keys", "key");
                case "type", "clipboard_write" -> List.of("text");
                case "focus" -> List.of("window_title");
                default -> List.<String>of();
            };
            var required = switch (operation) {
                case "launch" -> List.of("executable");
                case "click", "double_click", "right_click", "move" -> List.of("x", "y");
                case "drag", "screenshot_region" -> List.of("x", "y", "x2", "y2");
                case "type", "clipboard_write" -> List.of("text");
                case "focus" -> List.of("window_title");
                default -> List.<String>of();
            };
            var branch = operationBranch(properties, "operation", operation, required, fields,
                    List.of("machine_id", "cwd", "timeout_seconds", "wait_ms", "limit", "idempotency_key"));
            if ("shortcut".equals(operation)) branch.put("oneOf", exclusiveFields("keys", "key"));
            if ("type".equals(operation)) branch.put("properties", Map.of(
                    "operation", Map.of("const", operation), "text", modelString("text to type", 1, 16384)));
            branches.add(whenOperation("operation", operation, branch));
        }
        result.put("allOf", branches);
        return result;
    }

    static Map<String, Object> browserModelSchema() {
        return modelSchema(Map.ofEntries(
                Map.entry("machine_id", modelString("browser-capable machine identifier", 1, 180)),
                Map.entry("request", modelBrowserRequestSchema()),
                Map.entry("cwd", modelString("working directory on target machine", 1, 4096)),
                Map.entry("timeout_seconds", modelInteger("task timeout", 1, 86400)),
                Map.entry("wait_ms", modelInteger("bounded synchronous wait", 0, 15000)),
                Map.entry("limit", modelInteger("initial output byte budget; default 16384", 1, MAX_OUTPUT_PAGE)),
                Map.entry("idempotency_key", Map.of("type", "string", "description", "optional stable retry key", "minLength", 8, "maxLength", 128, "pattern", "^[A-Za-z0-9._:-]+$"))),
                List.of("machine_id", "request"));
    }

    static Map<String, Object> artifactModelSchema() {
        var file = modelFileObjectSchema();
        file.put("description", "put only: ChatGPT file object injected into the top-level file parameter");
        var result = modelSchema(Map.ofEntries(
                Map.entry("operation", modelEnum("put uploads, get retrieves, read inspects an existing artifact", List.of("put", "get", "read"))),
                Map.entry("delivery_mode", modelEnum("get/read only: auto returns compact content, inline requests native MCP content, async returns a file handle", List.of("auto", "inline", "async"))),
                Map.entry("wait_ms", modelInteger("put/get only: bounded wait; 0 returns the task immediately and never cancels it", 0, 25000)),
                Map.entry("machine_id", modelString("put/get only: target or source machine identifier", 1, 180)),
                Map.entry("file", file),
                Map.entry("artifact_id", modelString("read only: existing artifact identifier", 1, 180)),
                Map.entry("transfer_id", modelString("read only: existing transfer identifier", 1, 180)),
                Map.entry("cursor", modelInteger("read inline text only: output byte offset", 0, Long.MAX_VALUE)),
                Map.entry("limit", modelInteger("inline text byte budget; default 16384", 1, MAX_OUTPUT_PAGE)),
                Map.entry("destination_path", modelString("put only: target path on the machine", 1, 4096)),
                Map.entry("source_path", modelString("get only: source path on the machine", 1, 4096)),
                Map.entry("file_name", modelString("display file name", 1, 512)),
                Map.entry("mime_type", modelString("MIME type", 1, 256)),
                Map.entry("expected_bytes", modelInteger("expected byte size", 0L, 4L * 1024 * 1024 * 1024)),
                Map.entry("expected_sha256", Map.of("type", "string", "description", "expected SHA-256", "pattern", "^[A-Fa-f0-9]{64}$")),
                Map.entry("overwrite", modelBoolean("replace an existing target")),
                Map.entry("cwd", modelString("working directory on target machine", 1, 4096)),
                Map.entry("idempotency_key", Map.of("type", "string", "description", "optional stable retry key", "minLength", 8, "maxLength", 128, "pattern", "^[A-Za-z0-9._:-]+$"))),
                List.of("operation"));
        result.put("oneOf", List.of(
                artifactOperationBranch("put", List.of("operation", "machine_id", "file", "destination_path"),
                        List.of("source_path", "artifact_id", "transfer_id", "delivery_mode", "cursor", "limit")),
                artifactOperationBranch("get", List.of("operation", "machine_id", "source_path"),
                        List.of("file", "destination_path", "artifact_id", "transfer_id",
                                 "expected_bytes", "expected_sha256", "overwrite", "cursor")),
                artifactOperationBranch("read", List.of("operation", "artifact_id"),
                        List.of("transfer_id", "machine_id", "file", "destination_path", "source_path",
                                "file_name", "mime_type", "expected_bytes", "expected_sha256", "overwrite",
                                "cwd", "idempotency_key", "wait_ms")),
                artifactOperationBranch("read", List.of("operation", "transfer_id"),
                        List.of("artifact_id", "machine_id", "file", "destination_path", "source_path",
                                "file_name", "mime_type", "expected_bytes", "expected_sha256", "overwrite",
                                 "cwd", "idempotency_key", "wait_ms"))));
        result.put("allOf", List.of(Map.of(
                "if", Map.of("anyOf", List.of(Map.of("required", List.of("cursor")), Map.of("required", List.of("limit")))),
                "then", Map.of("required", List.of("delivery_mode"),
                        "properties", Map.of("delivery_mode", Map.of("const", "inline"))))));
        return result;
    }

    private static List<Map<String, Object>> exclusiveFields(String first, String second) {
        return List.of(Map.of("required", List.of(first), "not", Map.of("required", List.of(second))),
                Map.of("required", List.of(second), "not", Map.of("required", List.of(first))));
    }

    private static Map<String, Object> whenOperation(String field, String value, Map<String, Object> branch) {
        // Validate only the selected operation. A union makes the SDK expand
        // errors from every other operation, obscuring the actionable cause.
        return Map.of("if", Map.of("properties", Map.of(field, Map.of("const", value)),
                        "required", List.of(field)), "then", branch);
    }

    private static Map<String, Object> operationBranch(Map<String, Object> properties, String discriminator,
                                                       String operation, List<String> required,
                                                       List<String> fields, List<String> common) {
        var branch = new LinkedHashMap<String, Object>();
        branch.put("properties", Map.of(discriminator, Map.of("const", operation)));
        var mandatory = new java.util.ArrayList<>(required);
        mandatory.add(discriminator);
        branch.put("required", mandatory);
        var forbidden = properties.keySet().stream().filter(key -> !discriminator.equals(key)
                && !fields.contains(key) && !common.contains(key)).sorted().toList();
        if (!forbidden.isEmpty()) branch.put("not", Map.of("anyOf",
                forbidden.stream().map(key -> Map.of("required", List.of(key))).toList()));
        return branch;
    }

    private static Map<String, Object> artifactOperationBranch(String operation, List<String> required,
                                                                List<String> forbidden) {
        var branch = new LinkedHashMap<String, Object>();
        branch.put("properties", Map.of("operation", Map.of("const", operation)));
        branch.put("required", required);
        branch.put("not", Map.of("anyOf", forbidden.stream()
                .map(field -> Map.of("required", List.of(field))).toList()));
        return branch;
    }

    static Map<String, Object> taskReadModelSchema() {
        var result = modelSchema(Map.ofEntries(
                Map.entry("task_id", modelString("task identifier", 1, 180)),
                Map.entry("cursor", modelInteger("output byte offset; may jump directly near the end", 0, Integer.MAX_VALUE)),
                Map.entry("tail_bytes", modelInteger("latest stored output bytes; use instead of cursor and limit; capped output may omit the process's true end", 1, MAX_OUTPUT_PAGE)),
                Map.entry("wait_ms", modelInteger("bounded wait", 0, 20000)),
                Map.entry("change_seq", modelInteger("return when task change sequence advances", 0, Long.MAX_VALUE)),
                Map.entry("detail", modelBoolean("include execution timing, command context, progress counts and retention details")),
                Map.entry("include_output", modelBoolean("read output; defaults to false for change_seq-only status observation")),
                Map.entry("include_artifact", modelBoolean("fetch image content; defaults to metadata only")),
                Map.entry("limit", modelInteger("output page size", 1, MAX_OUTPUT_PAGE))), List.of("task_id"));
        result.put("not", Map.of("anyOf", List.of(
                Map.of("required", List.of("tail_bytes", "cursor")),
                Map.of("required", List.of("tail_bytes", "limit")),
                Map.of("properties", Map.of("include_output", Map.of("const", false)),
                        "required", List.of("include_output"),
                        "anyOf", List.of(Map.of("required", List.of("cursor")), Map.of("required", List.of("tail_bytes")),
                                Map.of("required", List.of("limit")))))));
        return result;
    }

    private static Map<String, Object> taskCancelModelSchema() {
        return modelSchema(Map.of("task_id", modelString("task identifier", 1, 180)), List.of("task_id"));
    }

    static Map<String, Object> modelOutputSchema(String toolName) {
        var task = modelSchema(Map.of(
                "id", Map.of("type", "string"),
                "machine_id", Map.of("type", "string"),
                "kind", Map.of("type", "string"),
                "status", modelEnum("cancel_requested is not terminal", List.of(TaskStatus.QUEUED,
                        TaskStatus.DISPATCHING, TaskStatus.RUNNING, TaskStatus.CANCEL_REQUESTED,
                        TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.CANCELED)),
                "change_seq", Map.of("type", "integer"),
                "exit_code", Map.of("type", "integer"),
                "error", Map.of("type", "string")),
                List.of("id", "machine_id", "kind", "status", "change_seq"));
        task.put("additionalProperties", true);
        var output = modelSchema(Map.of(
                "text", Map.of("type", "string"),
                "data", Map.of("type", "object", "description", "bounded structured browser result",
                        "additionalProperties", true),
                "cursor", Map.of("type", "integer"),
                "next_cursor", Map.of("type", "integer"),
                "more", Map.of("type", "boolean")), List.of("cursor", "next_cursor", "more"));
        output.put("oneOf", exclusiveFields("text", "data"));
        var transfer = modelSchema(Map.of(
                "transfer_id", Map.of("type", "string"),
                "artifact_id", Map.of("type", "string"),
                "status", Map.of("type", "string")), List.of("transfer_id", "artifact_id", "status"));
        transfer.put("additionalProperties", true);
        var file = modelSchema(Map.of(
                "artifact_id", Map.of("type", "string"),
                "download_url", Map.of("type", List.of("string", "null"))), List.of("artifact_id"));
        file.put("additionalProperties", true);
        var properties = new LinkedHashMap<String, Object>();
        if ("machines".equals(toolName)) {
            properties.put("machines", Map.of("type", "array", "items", Map.of("type", "object"), "maxItems", MAX_MACHINE_PAGE));
            properties.put("machine", Map.of("type", "object"));
            for (var key : List.of("offset", "limit", "total")) properties.put(key, Map.of("type", "integer"));
            properties.put("has_more", Map.of("type", "boolean"));
        } else {
            properties.put("task", task);
            if (!"task_cancel".equals(toolName)) {
                properties.put("output", output);
                properties.put("artifact", Map.of("type", "object", "additionalProperties", true));
            }
            if ("artifact".equals(toolName) || "task_read".equals(toolName)) {
                properties.put("transfer", transfer);
                properties.put("file", file);
            }
            if ("artifact".equals(toolName)) {
                properties.put("delivery_mode", Map.of("const", "inline"));
            }
        }
        properties.put("kind", Map.of("const", "error"));
        properties.put("error", Map.of("type", "object", "required", List.of("code", "message", "retryable")));
        var result = modelSchema(properties, List.of());
        var variants = new java.util.ArrayList<Map<String, Object>>();
        variants.add(Map.of("required", List.of("error")));
        if ("machines".equals(toolName)) {
            variants.add(Map.of("required", List.of("machines")));
            variants.add(Map.of("required", List.of("machine")));
        } else {
            variants.add(Map.of("required", List.of("artifact".equals(toolName) ? "transfer" : "task")));
        }
        result.put("anyOf", variants);
        result.put("additionalProperties", true);
        return result;
    }

    static McpSchema.CallToolResult machinesModel(AgentRegistry agents, McpAccessService access,
                                                          TaskOrigin origin, McpSchema.CallToolRequest request) {
        try {
            var arguments = modelArguments(request);
            var operation = requiredModelString(arguments, "operation");
            if ("detail".equals(operation)) {
                var normalized = Map.<String, Object>of("machine_id", requiredModelString(arguments, "machine_id"));
                return machineInfoCore(agents, access, origin, modelRequest("machines", normalized));
            }
            var normalized = new LinkedHashMap<String, Object>();
            copyIfPresent(arguments, normalized, "offset");
            copyIfPresent(arguments, normalized, "limit");
            return machinesListCore(agents, access, origin, modelRequest("machines", normalized));
        } catch (Exception exception) {
            return error(exception);
        }
    }

    static McpSchema.CallToolResult commandModel(AgentRegistry agents, TaskService tasks,
                                                         McpAccessService access,
                                                         TaskOrigin origin, McpSchema.CallToolRequest request) {
        try {
            var arguments = modelArguments(request);
            var normalized = new LinkedHashMap<String, Object>();
            normalized.put("machine_id", requiredModelString(arguments, "machine_id"));
            normalized.put("command", requiredModelString(arguments, "command"));
            copyIfPresent(arguments, normalized, "env");
            copyIfPresent(arguments, normalized, "timeout_seconds");
            copyIfPresent(arguments, normalized, "wait_ms");
            copyIfPresent(arguments, normalized, "limit");
            normalized.put("idempotency_key", modelRetryKey(arguments));
            copyIfPresent(arguments, normalized, "cwd");
            return commandCore(agents, tasks, access, origin, modelRequest("command", normalized));
        } catch (Exception exception) {
            return error(exception);
        }
    }

    private static McpSchema.CallToolResult desktopModel(AgentRegistry agents, TaskService tasks,
                                                         McpAccessService access,
                                                         TaskOrigin origin, McpSchema.CallToolRequest request) {
        try {
            var arguments = modelArguments(request);
            var normalized = new LinkedHashMap<String, Object>();
            normalized.put("operation", "shortcut".equals(
                    asString(arguments.get("operation")).toLowerCase(java.util.Locale.ROOT))
                    ? "key" : requiredModelString(arguments, "operation"));
            normalized.put("machine_id", requiredModelString(arguments, "machine_id"));
            for (var key : List.of("executable", "args", "text", "x", "y", "x2", "y2",
                    "duration_ms", "screen", "window_title", "timeout_seconds", "wait_ms", "limit")) {
                copyIfPresent(arguments, normalized, key);
            }
            if (arguments.containsKey("keys")) {
                var keys = stringList(arguments.get("keys"), "keys", 16, 64);
                normalized.put("key", String.join("+", keys));
            } else {
                copyIfPresent(arguments, normalized, "key");
            }
            if ("type".equals(normalized.get("operation"))) {
                var value = asString(arguments.get("text"));
                if (value == null || value.isEmpty() || value.length() > 16384)
                    throw new IllegalArgumentException("type requires text up to 16384 characters");
            }
            if ("screenshot_region".equals(normalized.get("operation"))) {
                var x = optionalModelInt(arguments, "x", -100000, 100000, 0);
                var y = optionalModelInt(arguments, "y", -100000, 100000, 0);
                var x2 = optionalModelInt(arguments, "x2", -100000, 100000, 0);
                var y2 = optionalModelInt(arguments, "y2", -100000, 100000, 0);
                if (x2 <= x || y2 <= y || (long) x2 - x > 16000 || (long) y2 - y > 16000)
                    throw new IllegalArgumentException("screenshot_region requires an ordered region within 16000x16000");
            }
            normalized.put("idempotency_key", modelRetryKey(arguments));
            copyIfPresent(arguments, normalized, "cwd");
            return desktopCore(agents, tasks, access, origin, modelRequest("desktop", normalized));
        } catch (Exception exception) {
            return error(exception);
        }
    }

    private static McpSchema.CallToolResult browserModel(AgentRegistry agents, TaskService tasks,
                                                         McpAccessService access,
                                                         TaskOrigin origin, McpSchema.CallToolRequest request) {
        try {
            var arguments = modelArguments(request);
            var normalized = new LinkedHashMap<String, Object>();
            normalized.put("machine_id", requiredModelString(arguments, "machine_id"));
            var browserRequest = requiredModelMap(arguments, "request");
            var action = requiredModelString(browserRequest, "action");
            if (!List.of("navigate", "observe", "click", "fill", "select", "press", "wait", "extract", "download", "screenshot")
                    .contains(action)) {
                throw new IllegalArgumentException("unsupported browser request action");
            }
            normalized.put("command", McpJsonDefaults.getMapper().writeValueAsString(browserRequest));
            if (((String) normalized.get("command")).getBytes(StandardCharsets.UTF_8).length > MAX_OUTPUT_PAGE)
                throw new IllegalArgumentException("request exceeds 65536 UTF-8 bytes");
            for (var field : List.of("url", "selector", "text", "value")) {
                var maximum = switch (field) {
                    case "url" -> 4096;
                    case "selector", "value" -> 2048;
                    default -> MAX_OUTPUT_PAGE;
                };
                var value = browserRequest.get(field);
                if (value instanceof String text && (text.indexOf('\0') >= 0
                        || text.getBytes(StandardCharsets.UTF_8).length > maximum))
                    throw new IllegalArgumentException("request." + field + " exceeds its UTF-8 byte limit or contains NUL");
            }
            copyIfPresent(arguments, normalized, "timeout_seconds");
            copyIfPresent(arguments, normalized, "wait_ms");
            copyIfPresent(arguments, normalized, "limit");
            normalized.put("idempotency_key", modelRetryKey(arguments));
            copyIfPresent(browserRequest, normalized, "detail");
            copyIfPresent(arguments, normalized, "cwd");
            return browserCore(agents, tasks, access, origin, modelRequest("browser", normalized));
        } catch (Exception exception) {
            return error(exception);
        }
    }

    static McpSchema.CallToolResult artifactModel(AgentRegistry agents, TaskService tasks,
                                                          McpAccessService access, ArtifactTransferService transfers,
                                                          TaskOrigin origin, McpSchema.CallToolRequest request) {
        try {
            var arguments = modelArguments(request);
            var operation = requiredModelString(arguments, "operation");
            if ("read".equals(operation)) {
                rejectModelFields(arguments, operation, "machine_id", "file", "destination_path", "source_path",
                        "file_name", "mime_type", "expected_bytes", "expected_sha256", "overwrite",
                        "cwd", "idempotency_key", "wait_ms");
                var artifactId = asString(arguments.get("artifact_id"));
                var transferId = asString(arguments.get("transfer_id"));
                var hasArtifactId = artifactId != null && !artifactId.isBlank();
                var hasTransferId = transferId != null && !transferId.isBlank();
                if (hasArtifactId == hasTransferId) {
                    throw new IllegalArgumentException("read requires exactly one artifact_id or transfer_id");
                }
                var normalized = new LinkedHashMap<String, Object>();
                copyIfPresent(arguments, normalized, "artifact_id");
                copyIfPresent(arguments, normalized, "transfer_id");
                copyIfPresent(arguments, normalized, "delivery_mode");
                copyIfPresent(arguments, normalized, "cursor");
                copyIfPresent(arguments, normalized, "limit");
                return artifactReadCore(access, transfers, origin, modelRequest("artifact", normalized));
            }
            if (!"put".equals(operation) && !"get".equals(operation)) {
                throw new IllegalArgumentException("operation must be put, get, or read");
            }
            var normalized = new LinkedHashMap<String, Object>();
            normalized.put("machine_id", requiredModelString(arguments, "machine_id"));
            var retryKey = modelRetryKey(arguments);
            normalized.put("idempotency_key", retryKey.isBlank() ? java.util.UUID.randomUUID().toString() : retryKey);
            if ("put".equals(operation)) {
                rejectModelFields(arguments, operation, "source_path", "artifact_id", "transfer_id", "delivery_mode");
                normalized.put("file", requiredModelMap(arguments, "file"));
                normalized.put("destination_path", requiredModelString(arguments, "destination_path"));
                for (var key : List.of("file_name", "mime_type", "expected_bytes", "expected_sha256", "overwrite", "wait_ms")) {
                    copyIfPresent(arguments, normalized, key);
                }
                copyIfPresent(arguments, normalized, "cwd");
                return artifactPutCore(agents, tasks, access, transfers, origin, modelRequest("artifact", normalized));
            }
            rejectModelFields(arguments, operation, "file", "destination_path", "artifact_id", "transfer_id",
                    "expected_bytes", "expected_sha256", "overwrite");
            normalized.put("source_path", requiredModelString(arguments, "source_path"));
            for (var key : List.of("file_name", "mime_type", "delivery_mode", "wait_ms", "limit")) copyIfPresent(arguments, normalized, key);
            copyIfPresent(arguments, normalized, "cwd");
            return artifactGetCore(agents, tasks, access, transfers, origin, modelRequest("artifact", normalized));
        } catch (Exception exception) {
            return error(exception);
        }
    }

    static McpSchema.CallToolResult taskReadModel(TaskService tasks, McpAccessService access,
                                                  TaskOrigin origin, McpSchema.CallToolRequest request) {
        return taskReadModel(tasks, access, null, origin, request);
    }

    static McpSchema.CallToolResult taskReadModel(TaskService tasks, McpAccessService access,
                                                  ArtifactTransferService transfers, TaskOrigin origin,
                                                  McpSchema.CallToolRequest request) {
        try {
            var arguments = modelArguments(request);
            var taskId = requiredModelString(arguments, "task_id");
            var tailBytes = optionalModelInt(arguments, "tail_bytes", 1, MAX_OUTPUT_PAGE, 0);
            if (tailBytes > 0 && (arguments.containsKey("cursor") || arguments.containsKey("limit"))) {
                throw new IllegalArgumentException("tail_bytes cannot be combined with cursor or limit");
            }
            var cursor = optionalModelInt(arguments, "cursor", 0, Integer.MAX_VALUE, 0);
            var waitMs = optionalModelInt(arguments, "wait_ms", 0, 20000, 0);
            var changeSequence = optionalModelLong(arguments, "change_seq", 0L, Long.MAX_VALUE, -1L);
            var limit = optionalModelInt(arguments, "limit", 1, MAX_OUTPUT_PAGE, DEFAULT_OUTPUT_PAGE);
            var detail = Boolean.TRUE.equals(arguments.get("detail"));
            var outputSelected = arguments.containsKey("cursor") || arguments.containsKey("tail_bytes")
                    || arguments.containsKey("limit");
            var includeOutput = arguments.containsKey("include_output")
                    ? Boolean.TRUE.equals(arguments.get("include_output"))
                    : changeSequence < 0 || outputSelected;
            if (!includeOutput && outputSelected)
                throw new IllegalArgumentException("include_output=false cannot be combined with output selectors");
            var includeArtifact = Boolean.TRUE.equals(arguments.get("include_artifact"));
            var current = tasks.findFor(origin, taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
            access.authorizeTool(origin, current.machineId(), "task_read");
            // State/progress observation must not replay or be woken by old logs.
            var waitCursor = !includeOutput ? Long.MAX_VALUE : tailBytes > 0 ? current.outputBytes() : cursor;
            var view = waitMs == 0 ? new TaskView(current)
                    : tasks.waitForChange(origin, taskId, waitCursor, changeSequence, Duration.ofMillis(waitMs));
            var outputCursor = tailBytes > 0 ? Math.max(0L, view.outputBytes() - tailBytes) : cursor;
            var result = taskResult(tasks, origin, view, outputCursor, tailBytes > 0 ? tailBytes : limit,
                    detail, includeOutput, includeArtifact);
            if (transfers == null || !TaskKind.FILE_TRANSFER.wireValue().equals(view.kind())) return result;
            var transfer = transfers.findByTask(taskId, origin).orElse(null);
            if (transfer == null) return result;
            @SuppressWarnings("unchecked")
            var payload = new LinkedHashMap<>((Map<String, Object>) result.structuredContent());
            payload.put("transfer", detail ? transferDetailMap(transfer, false) : transferMap(transfer));
            if ("agent-to-web".equals(transfer.direction()) && fileReady(transfer)) {
                payload.put("file", artifactFileMap(transfer, transfers, origin));
            }
            return artifactHandleResult(payload);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return error(exception);
        } catch (Exception exception) {
            return error(exception);
        }
    }

    private static McpSchema.CallToolResult taskCancelModel(TaskService tasks, McpAccessService access,
                                                            ArtifactTransferService transfers,
                                                            TaskOrigin origin, McpSchema.CallToolRequest request) {
        var arguments = modelArguments(request);
        var normalized = Map.<String, Object>of("task_id", requiredModelString(arguments, "task_id"));
        return taskCancelCore(tasks, access, transfers, origin, modelRequest("task_cancel", normalized));
    }

    private static Map<String, Object> modelArguments(McpSchema.CallToolRequest request) {
        return request == null || request.arguments() == null ? Map.of() : request.arguments();
    }

    private static String requiredModelString(Map<String, Object> values, String key) {
        var value = asString(values.get(key));
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value.trim();
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requiredModelMap(Map<String, Object> values, String key) {
        var value = values.get(key);
        if (!(value instanceof Map<?, ?> raw)) throw new IllegalArgumentException(key + " must be an object");
        var result = new LinkedHashMap<String, Object>();
        raw.forEach((entryKey, entryValue) -> result.put(String.valueOf(entryKey), entryValue));
        return result;
    }

    private static void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key) && source.get(key) != null) target.put(key, source.get(key));
    }

    private static void rejectModelFields(Map<String, Object> arguments, String operation, String... fields) {
        for (var field : fields) {
            if (arguments.get(field) != null) {
                throw new IllegalArgumentException(field + " is not valid for artifact " + operation);
            }
        }
    }

    private static int optionalModelInt(Map<String, Object> values, String key, int min, int max, int fallback) {
        var value = values.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number number)) throw new IllegalArgumentException(key + " must be an integer");
        var result = number.intValue();
        if (result < min || result > max) throw new IllegalArgumentException(key + " is outside the allowed range");
        return result;
    }

    private static long optionalModelLong(Map<String, Object> values, String key, long min, long max, long fallback) {
        var value = values.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number number)) throw new IllegalArgumentException(key + " must be an integer");
        var result = number.longValue();
        if (result < min || result > max) throw new IllegalArgumentException(key + " is outside the allowed range");
        return result;
    }

    private static List<String> stringList(Object value, String key, int maxItems, int maxItemLength) {
        if (!(value instanceof List<?> raw)) throw new IllegalArgumentException(key + " must be an array");
        if (raw.size() < 1 || raw.size() > maxItems) throw new IllegalArgumentException(key + " has too many items");
        return raw.stream().map(item -> {
            var text = asString(item);
            if (text == null || text.isBlank() || text.length() > maxItemLength) throw new IllegalArgumentException(key + " contains an invalid item");
            return text.trim();
        }).toList();
    }

    static String modelRetryKey(Map<String, Object> values) {
        var explicit = asString(values.get("idempotency_key"));
        // Matching arguments can describe distinct intentional operations.
        return explicit == null ? "" : explicit.trim();
    }

    private static McpSchema.CallToolRequest modelRequest(String name, Map<String, Object> arguments) {
        return new McpSchema.CallToolRequest(name, arguments, Map.of());
    }

    private static McpSchema.CallToolResult artifactPutCore(AgentRegistry agents, TaskService tasks,
                                                        McpAccessService access, ArtifactTransferService transfers,
                                                        TaskOrigin origin, McpSchema.CallToolRequest request) {
        try {
            var args = args(request, ArtifactPutCoreArgs.class);
            if (args.file() == null) throw new IllegalArgumentException("file is required");
            var waitMs = executionWaitMs(args.waitMs(), 25000);
            access.authorizeTool(origin, args.machineId(), "artifact");
            var cwd = resolveCwd(agents, args.machineId(), args.cwd());
            var command = new TaskCommand("", TaskKind.FILE_TRANSFER, "file_transfer", null, cwd, Map.of(), 0, null, Instant.now(), null, 0, null);
            var create = new CreateTaskRequest(args.machineId(), command, args.idempotencyKey(),
                    null, "", "low", false, origin);
            var file = args.file();
            var name = firstNonBlank(args.fileName(), file.fileName());
            var mime = firstNonBlank(args.mimeType(), file.mimeType());
            var result = transfers.createWebToAgent(origin, create, file.fileId(), args.destinationPath(), name, mime,
                    URI.create(file.downloadUrl()), args.expectedBytes() == null ? file.bytes() : args.expectedBytes(),
                    firstNonBlank(args.expectedSha256(), file.sha256()), Boolean.TRUE.equals(args.overwrite()));
            var finalTask = waitMs == 0 ? result.task()
                    : tasks.waitForTerminal(origin, result.task().id(), Duration.ofMillis(waitMs));
            var finalTransfer = waitMs == 0 ? result.transfer()
                    : transfers.findByTransfer(result.transfer().transferId(), origin).orElse(result.transfer());
            var payload = new LinkedHashMap<String, Object>();
            payload.put("task", taskMap(finalTask));
            payload.put("transfer", transferMap(finalTransfer));
            return structuredJson(payload);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return error(exception);
        } catch (Exception exception) {
            return error(exception);
        }
    }

    private static McpSchema.CallToolResult artifactGetCore(AgentRegistry agents, TaskService tasks,
                                                         McpAccessService access, ArtifactTransferService transfers,
                                                        TaskOrigin origin, McpSchema.CallToolRequest request) {
        try {
            var args = args(request, ArtifactGetCoreArgs.class);
            var deliveryMode = normalizeDeliveryMode(args.deliveryMode());
            var waitMs = artifactWaitMs(deliveryMode, args.waitMs());
            access.authorizeTool(origin, args.machineId(), "artifact");
            var cwd = resolveCwd(agents, args.machineId(), args.cwd());
            var command = new TaskCommand("", TaskKind.FILE_TRANSFER, "file_transfer", null, cwd, Map.of(), 0, null, Instant.now(), null, 0, null);
            var create = new CreateTaskRequest(args.machineId(), command, args.idempotencyKey(),
                    null, "", "low", false, origin);
            var result = transfers.createAgentToWeb(origin, create, args.sourcePath(), args.fileName(), args.mimeType());
            var finalTask = waitMs == 0
                    ? result.task()
                    : tasks.waitForTerminal(origin, result.task().id(), Duration.ofMillis(waitMs));
            var finalTransfer = transfers.findByTransfer(result.transfer().transferId(), origin).orElse(result.transfer());
            var inline = inlineArtifactResult(finalTask, finalTransfer, deliveryMode, 0,
                    initialOutputLimit(args.limit()), transfers, origin);
            if (inline != null) return inline;
            var payload = new LinkedHashMap<String, Object>();
            payload.put("task", taskMap(finalTask));
            payload.put("transfer", transferMap(finalTransfer));
            if (fileReady(finalTransfer)) {
                payload.put("file", artifactFileMap(finalTransfer, transfers, origin));
            }
            return artifactHandleResult(payload);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return error(exception);
        } catch (Exception exception) {
            return error(exception);
        }
    }

    private static McpSchema.CallToolResult artifactReadCore(McpAccessService access, ArtifactTransferService transfers,
                                                         TaskOrigin origin,
                                                         McpSchema.CallToolRequest request) {
        try {
            var args = args(request, ArtifactReadCoreArgs.class);
            var deliveryMode = normalizeDeliveryMode(args.deliveryMode());
            var descriptor = args.artifactId() == null || args.artifactId().isBlank()
                    ? transfers.findByTransfer(args.transferId(), origin).orElseThrow(() -> new IllegalArgumentException("artifact or transfer id is required"))
                    : transfers.findByArtifact(args.artifactId(), origin).orElseThrow(() -> new IllegalArgumentException("artifact not found"));
            access.authorizeTool(origin, descriptor.machineId(), "artifact");
            var payload = new LinkedHashMap<String, Object>();
            payload.put("transfer", transferDetailMap(descriptor, fileReady(descriptor)));
            if (fileReady(descriptor)) {
                payload.put("file", artifactFileMap(descriptor, transfers, origin));
            }
            var inline = inlineArtifactResult(null, descriptor, deliveryMode,
                    args.cursor() == null ? 0L : args.cursor(), initialOutputLimit(args.limit()), transfers, origin);
            if (inline != null) return inline;
            return artifactHandleResult(payload);
        } catch (Exception exception) {
            return error(exception);
        }
    }

    private static McpSchema.CallToolResult inlineArtifactResult(TaskView task,
                                                                  ArtifactTransferService.TransferDescriptor descriptor,
                                                                  String deliveryMode, long cursor, int limit,
                                                                  ArtifactTransferService transfers, TaskOrigin origin) {
        if (!fileReady(descriptor) || "async".equals(deliveryMode)
                || (task != null && !TaskStatus.COMPLETED.equals(task.status()))) return null;
        if ("inline".equals(deliveryMode) && isInlineText(descriptor.mimeType(), descriptor.fileName())) {
            var page = transfers.readTextPage(descriptor.artifactId(), origin, cursor, limit);
            if (page.isEmpty()) return null;
            var payload = new LinkedHashMap<String, Object>();
            if (task != null) payload.put("task", taskMap(task));
            payload.put("transfer", task == null ? transferDetailMap(descriptor, true) : transferMap(descriptor));
            payload.put("file", artifactFileMap(descriptor, transfers, origin));
            payload.put("delivery_mode", "inline");
            var value = page.get();
            payload.put("output", Map.of("text", new String(value.data(), StandardCharsets.UTF_8),
                    "cursor", value.cursor(), "next_cursor", value.nextCursor(), "more", value.more()));
            return artifactHandleResult(payload);
        }
        if (cursor != 0) throw new IllegalArgumentException("cursor is supported only for inline text");
        // Inspect metadata before opening bytes. Auto non-image delivery is a
        // handle, so it must not read and discard the entire object.
        if (!"inline".equals(deliveryMode) && !isInlineImage(descriptor.mimeType(), descriptor.bytes())) return null;
        var inline = transfers.readInlineContent(descriptor.artifactId(), origin, MAX_INLINE_CONTENT_BYTES);
        return inline.isEmpty() ? null : artifactInlineContentResult(task, descriptor, inline.get(), transfers, origin);
    }

    private static McpSchema.CallToolResult artifactInlineContentResult(TaskView task,
                                                                         ArtifactTransferService.TransferDescriptor descriptor,
                                                                         ArtifactTransferService.InlineArtifact inline,
                                                                         ArtifactTransferService transfers,
                                                                         TaskOrigin origin) {
        var payload = new LinkedHashMap<String, Object>();
        if (task != null) payload.put("task", taskMap(task));
        payload.put("transfer", task == null ? transferDetailMap(descriptor, true) : transferMap(descriptor));
        var file = artifactFileMap(descriptor, transfers, origin);
        file.put("mime_type", inline.mimeType());
        file.put("bytes", inline.data().length);
        file.put("sha256", inline.sha256());
        if (inline.fileName() != null && !inline.fileName().isBlank()) file.put("file_name", inline.fileName());
        payload.put("file", file);
        payload.put("delivery_mode", "inline");
        var builder = McpSchema.CallToolResult.builder()
                .structuredContent(payload)
                .addTextContent(jsonText(payload));
        var mime = inline.mimeType() == null || inline.mimeType().isBlank()
                ? "application/octet-stream" : inline.mimeType();
        if (mime.toLowerCase(java.util.Locale.ROOT).startsWith("image/")) {
            builder.addContent(McpSchema.ImageContent.builder(
                    java.util.Base64.getEncoder().encodeToString(inline.data()), mime).build());
        } else if (mime.toLowerCase(java.util.Locale.ROOT).startsWith("audio/")) {
            builder.addContent(McpSchema.AudioContent.builder(
                    java.util.Base64.getEncoder().encodeToString(inline.data()), mime).build());
        } else {
            var resource = McpSchema.BlobResourceContents.builder(
                            "artifact:" + inline.artifactId(),
                            java.util.Base64.getEncoder().encodeToString(inline.data()))
                    .mimeType(mime)
                    .build();
            builder.addContent(McpSchema.EmbeddedResource.builder(resource).build());
        }
        return builder.build();
    }

    private static boolean isInlineText(String mimeType, String fileName) {
        var mime = mimeType == null ? "" : mimeType.toLowerCase(java.util.Locale.ROOT);
        var name = fileName == null ? "" : fileName.toLowerCase(java.util.Locale.ROOT);
        return mime.startsWith("text/") || mime.contains("json") || mime.contains("xml")
                || mime.contains("yaml") || mime.contains("javascript") || mime.contains("markdown")
                || name.matches(".*\\.(txt|log|md|markdown|csv|json|ya?ml|toml|ini|xml|html?|css|js|ts|java|go|py|sh|ps1|sql)$");
    }

    private static String normalizeDeliveryMode(String value) {
        var mode = value == null || value.isBlank() ? "auto" : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!Set.of("auto", "inline", "async").contains(mode)) {
            throw new IllegalArgumentException("delivery_mode must be auto, inline, or async");
        }
        return mode;
    }

    static int artifactWaitMs(String deliveryMode, Integer requested) {
        var bounded = requested == null ? ("async".equals(deliveryMode) ? 0 : "inline".equals(deliveryMode)
                ? DEFAULT_EXPLICIT_INLINE_WAIT_MS : DEFAULT_INLINE_ARTIFACT_WAIT_MS) : requested;
        if (bounded < 0 || bounded > 25000) throw new IllegalArgumentException("wait_ms must be between 0 and 25000");
        // MCP request timeout is 30 seconds; keep the synchronous branch below
        // that transport ceiling so a caller receives a structured fallback.
        return bounded;
    }

    private static int executionWaitMs(Integer requested) {
        return executionWaitMs(requested, 15000);
    }

    private static int executionWaitMs(Integer requested, int maxWaitMs) {
        var waitMs = requested == null ? 0 : requested;
        if (waitMs < 0 || waitMs > maxWaitMs) {
            throw new IllegalArgumentException("wait_ms must be between 0 and " + maxWaitMs);
        }
        return waitMs;
    }

    private static Map<String, Object> artifactFileMap(ArtifactTransferService.TransferDescriptor descriptor,
                                                        ArtifactTransferService transfers, TaskOrigin origin) {
        var waitForDelivery = !Set.of("ready", "delivered", "failed", "canceled")
                .contains(descriptor.status());
        var downloadUrl = descriptor.downloadUrl();
        if ((downloadUrl == null || downloadUrl.isBlank()) && waitForDelivery) {
            downloadUrl = transfers.publicUrl(descriptor.artifactId(), origin,
                    transfers.sessionForTask(descriptor.taskId()), "download");
        }
        var previewUrl = transfers.publicUrl(descriptor.artifactId(), origin,
                transfers.sessionForTask(descriptor.taskId()), "preview");
        if (waitForDelivery) {
            downloadUrl = withWaitQuery(downloadUrl);
            previewUrl = withWaitQuery(previewUrl);
        }
        var file = new LinkedHashMap<String, Object>();
        // This is an RCM artifact id, not a host-registered ChatGPT file id.
        file.put("artifact_id", descriptor.artifactId());
        file.put("download_url", downloadUrl);
        file.put("preview_url", previewUrl);
        file.put("file_name", descriptor.fileName());
        file.put("mime_type", descriptor.mimeType());
        file.put("bytes", descriptor.bytes());
        file.put("sha256", descriptor.sha256());
        return file;
    }

    private static String withWaitQuery(String url) {
        if (url == null || url.isBlank()) return url;
        return url + (url.indexOf('?') >= 0 ? '&' : '?') + "wait_ms=" + ARTIFACT_URL_WAIT_MS;
    }

    static Map<String, Object> transferMap(ArtifactTransferService.TransferDescriptor value) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("transfer_id", value.transferId());
        payload.put("artifact_id", value.artifactId());
        payload.put("status", value.status());
        if (value.bytes() > 0 && value.bytesTransferred() < value.bytes())
            payload.put("bytes", value.bytes());
        if (value.bytesTransferred() > 0 && (value.bytes() == 0 || value.bytesTransferred() < value.bytes()))
            payload.put("bytes_transferred", value.bytesTransferred());
        if (value.error() != null && !value.error().isBlank()) payload.put("error", compact(value.error(), 1024));
        return payload;
    }

    static Map<String, Object> transferDetailMap(ArtifactTransferService.TransferDescriptor value, boolean hasFile) {
        var payload = new LinkedHashMap<>(transferMap(value));
        payload.put("direction", value.direction());
        if (value.taskId() != null && !value.taskId().isBlank()) payload.put("task_id", value.taskId());
        if (value.machineId() != null && !value.machineId().isBlank()) payload.put("machine_id", value.machineId());
        if (!hasFile) {
            if (value.fileName() != null && !value.fileName().isBlank()) payload.put("file_name", value.fileName());
            if (value.mimeType() != null && !value.mimeType().isBlank()) payload.put("mime_type", value.mimeType());
            payload.put("bytes", value.bytes());
            if (value.sha256() != null && !value.sha256().isBlank()) payload.put("sha256", value.sha256());
        }
        return payload;
    }

    private static McpSchema.CallToolResult machinesListCore(AgentRegistry agents, McpAccessService access,
                                                         TaskOrigin origin, McpSchema.CallToolRequest request) {
        try {
            var args = args(request, MachinesCoreArgs.class);
            var offset = Math.max(0, args.offset() == null ? 0 : args.offset());
            var limit = args.limit() == null ? 25 : args.limit();
            if (limit < 1 || limit > MAX_MACHINE_PAGE) {
                throw new IllegalArgumentException("limit must be between 1 and " + MAX_MACHINE_PAGE);
            }
            var all = agents.listAllMachines(Instant.now());
            var visible = all.stream().filter(machine -> access.canReadMachine(origin, machine.id())).toList();
            var machines = offset >= visible.size() ? List.<MachineView>of()
                    : visible.subList(offset, Math.min(visible.size(), offset + limit));
            var values = machines.stream().map(McpConfiguration::machineSummary).toList();
            var total = visible.size();
            var payload = new LinkedHashMap<String, Object>();
            payload.put("machines", values);
            payload.put("offset", offset);
            payload.put("limit", limit);
            payload.put("total", total);
            var hasMore = offset + values.size() < total;
            payload.put("has_more", hasMore);
            return json(payload);
        } catch (Exception exception) {
            return error(exception);
        }
    }

    private static McpSchema.CallToolResult machineInfoCore(AgentRegistry agents, McpAccessService access,
                                                        TaskOrigin origin, McpSchema.CallToolRequest request) {
        try {
            var args = args(request, MachineInfoCoreArgs.class);
            if (!access.canReadMachine(origin, args.machineId())) {
                throw new SecurityException("MCP credential cannot access this machine");
            }
            var machine = agents.findMachine(args.machineId(), Instant.now()).orElseThrow(() -> new IllegalArgumentException("machine not found"));
            return json(Map.of("machine", machineMap(machine)));
        } catch (Exception exception) {
            return error(exception);
        }
    }

    private static McpSchema.CallToolResult commandCore(AgentRegistry agents, TaskService tasks,
                                                         McpAccessService access,
                                                         TaskOrigin origin,
                                                         McpSchema.CallToolRequest request) {
        try {
            var args = args(request, CommandCoreArgs.class);
            var waitMs = executionWaitMs(args.waitMs());
            var limit = initialOutputLimit(args.limit());
            access.authorizeTool(origin, args.machineId(), "command");
            var timeout = args.timeoutSeconds() == null ? 0 : args.timeoutSeconds();
            var cwd = resolveCwd(agents, args.machineId(), args.cwd());
            var command = new com.prodigalgal.remotecontrolmcp.protocol.TaskCommand("", com.prodigalgal.remotecontrolmcp.protocol.TaskKind.COMMAND,
                    null, args.command(), cwd, args.env(), timeout, null, Instant.now(), null, 0, null);
            var task = tasks.create(new CreateTaskRequest(args.machineId(), command, args.idempotencyKey(),
                    null, "", "low", false, origin), "mcp", origin);
            if (waitMs > 0) return immediateTaskResult(tasks, origin,
                    tasks.waitForTerminal(origin, task.id(), Duration.ofMillis(waitMs)), limit);
            return json(Map.of("task", taskMap(task)));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return error(exception);
        } catch (Exception exception) {
            return error(exception);
        }
    }

    private static McpSchema.CallToolResult browserCore(AgentRegistry agents, TaskService tasks,
                                                    McpAccessService access,
                                                    TaskOrigin origin,
                                                    McpSchema.CallToolRequest request) {
        try {
            var args = args(request, BrowserCoreArgs.class);
            var waitMs = executionWaitMs(args.waitMs());
            var limit = initialOutputLimit(args.limit());
            access.authorizeTool(origin, args.machineId(), "browser");
            var machine = agents.findMachine(args.machineId(), Instant.now()).orElseThrow(() -> new IllegalArgumentException("machine not found"));
            if (!machine.capabilities().contains("browser")) throw new IllegalArgumentException("machine does not advertise browser capability");
            var timeout = args.timeoutSeconds() == null ? 300 : args.timeoutSeconds();
            var cwd = resolveCwd(agents, args.machineId(), args.cwd());
            var command = new com.prodigalgal.remotecontrolmcp.protocol.TaskCommand("", com.prodigalgal.remotecontrolmcp.protocol.TaskKind.BROWSER,
                    "browser", args.command(), cwd, Map.of(), timeout, null, Instant.now(), null, 0, null);
            var created = tasks.create(new CreateTaskRequest(args.machineId(), command, args.idempotencyKey(),
                    null, "", "low", false, origin), "mcp", origin);
            if (waitMs > 0) return immediateTaskResult(tasks, origin,
                    tasks.waitForTerminal(origin, created.id(), Duration.ofMillis(waitMs)), limit, Boolean.TRUE.equals(args.detail()));
            return json(Map.of("task", taskMap(created)));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return error(exception);
        } catch (Exception exception) {
            return error(exception);
        }
    }

    private static McpSchema.CallToolResult desktopCore(AgentRegistry agents, TaskService tasks,
                                                    McpAccessService access,
                                                    TaskOrigin origin,
                                                    McpSchema.CallToolRequest request) {
        try {
            var args = args(request, DesktopCoreArgs.class);
            var operation = args.operation() == null ? "" : args.operation().trim().toLowerCase();
            var waitMs = executionWaitMs(args.waitMs());
            var limit = initialOutputLimit(args.limit());
            if (!"screenshot".equals(operation) && !"screenshot_region".equals(operation) && !"screens".equals(operation) && !"windows".equals(operation) && !"launch".equals(operation)
                    && !"click".equals(operation) && !"double_click".equals(operation) && !"right_click".equals(operation)
                    && !"move".equals(operation) && !"drag".equals(operation) && !"key".equals(operation)
                    && !"type".equals(operation) && !"clipboard_read".equals(operation)
                    && !"clipboard_write".equals(operation) && !"focus".equals(operation)) {
                throw new IllegalArgumentException("unsupported desktop operation");
            }
            access.authorizeTool(origin, args.machineId(), "desktop");
            var machine = agents.findMachine(args.machineId(), Instant.now()).orElseThrow(() -> new IllegalArgumentException("machine not found"));
            if (!machine.capabilities().contains("desktop")) throw new IllegalArgumentException("machine does not advertise desktop capability");
            var timeout = args.timeoutSeconds() == null ? 30 : args.timeoutSeconds();
            var cwd = resolveCwd(agents, args.machineId(), args.cwd());
            var action = new com.prodigalgal.remotecontrolmcp.protocol.TaskCommand.DesktopAction(operation,
                    args.executable(), args.args(), cwd, args.text(), args.x(), args.y(), args.key(),
                    args.x2(), args.y2(), args.durationMs(), args.screen(), args.windowTitle());
            var command = new com.prodigalgal.remotecontrolmcp.protocol.TaskCommand("", com.prodigalgal.remotecontrolmcp.protocol.TaskKind.DESKTOP,
                    "desktop", null, cwd, Map.of(), timeout, action, Instant.now(), null, 0, null);
            var created = tasks.create(new CreateTaskRequest(args.machineId(), command, args.idempotencyKey(),
                    null, "", "low", false, origin), "mcp", origin);
            if (waitMs > 0) return immediateTaskResult(tasks, origin,
                    tasks.waitForTerminal(origin, created.id(), Duration.ofMillis(waitMs)), limit);
            return json(Map.of("task", taskMap(created)));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return error(exception);
        } catch (Exception exception) {
            return error(exception);
        }
    }

    private static String jsonText(Object value) {
        try {
            return boundedJsonText(value);
        } catch (IOException exception) {
            return "{\"message\":\"response omitted; use task_read cursor or the Console detail endpoint\"}";
        }
    }

    private static McpSchema.CallToolResult taskCancelCore(TaskService tasks, McpAccessService access,
                                                       ArtifactTransferService transfers,
                                                       TaskOrigin origin, McpSchema.CallToolRequest request) {
        try {
            var args = args(request, TaskCancelCoreArgs.class);
            var task = tasks.findFor(origin, args.taskId()).orElseThrow(() -> new IllegalArgumentException("task not found"));
            access.authorizeTool(origin, task.machineId(), "task_cancel");
            var canceled = tasks.cancel(origin, args.taskId());
            if (transfers != null && TaskStatus.CANCELED.equals(canceled.status())) {
                transfers.cancelForTask(canceled.id());
            }
            return json(Map.of("task", taskMap(canceled)));
        } catch (Exception exception) {
            return error(exception);
        }
    }

    static McpSchema.CallToolResult immediateTaskResult(TaskService tasks, TaskOrigin origin,
                                                          TaskView view) {
        return immediateTaskResult(tasks, origin, view, DEFAULT_OUTPUT_PAGE);
    }

    private static int initialOutputLimit(Integer requested) {
        if (requested != null && (requested < 1 || requested > MAX_OUTPUT_PAGE))
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_OUTPUT_PAGE);
        return requested == null ? DEFAULT_OUTPUT_PAGE : requested;
    }

    static McpSchema.CallToolResult immediateTaskResult(TaskService tasks, TaskOrigin origin,
                                                        TaskView view, int limit) {
        return immediateTaskResult(tasks, origin, view, limit, false);
    }

    static McpSchema.CallToolResult immediateTaskResult(TaskService tasks, TaskOrigin origin,
                                                        TaskView view, int limit, boolean detail) {
        // Failed short-wait calls should show the useful end of a long log in
        // the same response. task_read(cursor=0) still explicitly reads its start.
        var cursor = TaskStatus.FAILED.equals(view.status())
                ? Math.max(0L, view.outputBytes() - limit) : 0L;
        if ("browser".equals(view.kind())) cursor = 0;
        return taskResult(tasks, origin, view, cursor, limit, detail, true, true);
    }

    static McpSchema.CallToolResult taskResult(TaskService tasks, TaskOrigin origin,
                                                 TaskView view, long cursor, int limit) {
        return taskResult(tasks, origin, view, cursor, limit, false);
    }

    static McpSchema.CallToolResult taskResult(TaskService tasks, TaskOrigin origin,
                                                TaskView view, long cursor, int limit, boolean detail) {
        return taskResult(tasks, origin, view, cursor, limit, detail, true, false);
    }

    static McpSchema.CallToolResult taskResult(TaskService tasks, TaskOrigin origin,
                                              TaskView view, long cursor, int limit, boolean detail,
                                              boolean includeOutput, boolean includeArtifact) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("task", detail ? taskDetailMap(view) : taskMap(view));
        if (includeOutput) {
            var structuredBrowser = "browser".equals(view.kind()) && cursor == 0;
            var page = tasks.readOutput(origin, view.id(), cursor, structuredBrowser ? MAX_OUTPUT_PAGE : Math.max(4, limit));
            Map<String, Object> browserData = null;
            if (structuredBrowser && page.data().length > 0 && !page.more() && !view.outputTruncated()) {
                try {
                    var parsed = McpJsonDefaults.getMapper().readValue(new String(page.data(), StandardCharsets.UTF_8),
                            new io.modelcontextprotocol.json.TypeRef<Map<String, Object>>() {});
                    if (parsed != null) browserData = compactBrowserOutput(parsed, limit, detail);
                } catch (IOException ignored) {
                    // An older/custom adapter can return plain text.
                }
            }
            if (browserData == null) page = Utf8OutputPage.align(page, limit);
            if (page.data().length > 0 || page.more()) {
                var output = new LinkedHashMap<String, Object>();
                if (browserData == null) output.put("text", new String(page.data(), StandardCharsets.UTF_8));
                else output.put("data", browserData);
                output.put("cursor", page.cursor());
                output.put("next_cursor", page.nextCursor());
                output.put("more", page.more());
                payload.put("output", output);
            }
        }
        // Browser and desktop tasks can finish with a screenshot or another
        // bounded artifact.  Keep task_read useful for those capabilities as
        // well as command tasks: return metadata for every artifact, and
        // inline only small image bytes (the Center MCP threshold is 5 MiB;
        // the authenticated artifact endpoint has its separate bounded
        // storage contract). Non-image downloads remain
        // available through the authenticated console artifact endpoint
        // without inflating MCP context with binary/base64 data.
        if (view.artifactBytes() <= 0) {
            return json(payload);
        }
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("sha256", view.artifactSha256());
        metadata.put("bytes", view.artifactBytes());
        metadata.put("mime_type", view.artifactMime());
        var inlineImage = includeArtifact && isInlineImage(view.artifactMime(), view.artifactBytes());
        if (inlineImage) metadata.put("inline", true);
        payload.put("artifact", metadata);
        if (!inlineImage) return json(payload);
        var artifact = tasks.readArtifact(origin, view.id());
        if (artifact.isEmpty()) return json(payload);
        var value = artifact.get();
        metadata.put("sha256", value.sha256());
        metadata.put("bytes", value.data().length);
        metadata.put("mime_type", value.mimeType());
        if (!isInlineImage(value.mimeType(), value.data().length)) {
            metadata.remove("inline");
            return json(payload);
        }
        var text = jsonText(payload);
        return McpSchema.CallToolResult.builder()
                .structuredContent(payload)
                .addTextContent(text)
                .addContent(McpSchema.ImageContent.builder(
                        java.util.Base64.getEncoder().encodeToString(value.data()), value.mimeType()).build())
                .build();
    }

    static Map<String, Object> compactBrowserOutput(Map<String, Object> full, int limit, boolean detail)
            throws IOException {
        var value = new LinkedHashMap<>(full);
        if (!detail && value.remove("diagnostics") != null) value.put("detail_available", true);
        if (browserOutputBytes(value) <= limit) return value;
        value.put("truncated", true);
        value.put("detail_available", true);
        if (value.remove("diagnostics") != null && browserOutputBytes(value) <= limit) return value;
        for (var key : List.of("snapshot", "text")) {
            while (browserOutputBytes(value) > limit && value.get(key) instanceof String text && !text.isEmpty()) {
                var end = text.length() / 2;
                if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
                if (end == 0) value.remove(key);
                else value.put(key, text.substring(0, end));
            }
        }
        for (var key : List.of("elements", "warnings")) {
            if (value.get(key) instanceof List<?> entries) {
                var bounded = new java.util.ArrayList<>(entries);
                value.put(key, bounded);
                while (browserOutputBytes(value) > limit && !bounded.isEmpty()) bounded.removeLast();
            }
        }
        if (browserOutputBytes(value) > limit) {
            // Very small caller budgets still return an intact omission marker.
            return Map.of("truncated", true, "detail_available", true);
        }
        return value;
    }

    private static int browserOutputBytes(Map<String, Object> value) throws IOException {
        return McpJsonDefaults.getMapper().writeValueAsString(value).getBytes(StandardCharsets.UTF_8).length;
    }

    private static List<String> oauthScopesForTool(String name) {
        return switch (name) {
            case "machines", "task_read" -> List.of("mcp:read");
            case "artifact" -> List.of("mcp:read", "mcp:execute");
            default -> List.of("mcp:execute");
        };
    }

    private static boolean isInlineImage(String mimeType, long bytes) {
        return mimeType != null && mimeType.toLowerCase(java.util.Locale.ROOT).startsWith("image/")
                && bytes > 0 && bytes <= MAX_INLINE_IMAGE_BYTES;
    }

    /**
     * Keep the default machine discovery response useful for routing without
     * copying runtime budgets, paths, timestamps, or host metadata into the
     * model transcript.  Those fields remain available through machines(detail).
     */
    private static Map<String, Object> machineSummary(MachineView machine) {
        var value = new LinkedHashMap<String, Object>();
        value.put("id", machine.id());
        value.put("name", machine.name());
        value.put("os", machine.os());
        value.put("arch", machine.arch());
        value.put("version", textOrEmpty(machine.version()));
        var capabilities = machine.capabilities() == null ? List.<String>of() : machine.capabilities();
        var visibleCapabilities = capabilities.stream().limit(16).toList();
        value.put("capabilities", visibleCapabilities);
        if (capabilities.size() > visibleCapabilities.size()) value.put("capabilities_truncated", true);
        value.put("online", machine.online());
        return value;
    }

    private static String textOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static Map<String, Object> machineMap(MachineView machine) {
        var value = new LinkedHashMap<String, Object>();
        value.put("id", machine.id());
        value.put("name", machine.name());
        value.put("host_id", machine.hostId());
        value.put("hostname", machine.hostname());
        value.put("os", machine.os());
        value.put("arch", machine.arch());
        value.put("version", machine.version());
        value.put("default_cwd", machine.defaultCwd());
        value.put("capabilities", machine.capabilities());
        // Runtime self-description is a fixed-size, non-secret projection.
        // Keep it on the machine record rather than exposing raw process or
        // environment details to the MCP client.
        var runtime = machine.runtime();
        if (runtime.userContext() != null) {
            var context = runtime.userContext();
            var environment = new LinkedHashMap<String, Object>();
            if (!context.commandUser().isBlank()) environment.put("command_user", context.commandUser());
            if (!context.commandHome().isBlank()) environment.put("command_home", context.commandHome());
            if (!context.interactiveUser().isBlank()) environment.put("interactive_user", context.interactiveUser());
            if (!context.desktopPath().isBlank()) environment.put("desktop_path", context.desktopPath());
            if (!environment.isEmpty()) value.put("environment", environment);
        }
        value.put("runtime", Map.ofEntries(
                Map.entry("schema_version", runtime.schemaVersion()),
                Map.entry("config_generation", runtime.configGeneration()),
                Map.entry("max_concurrency", runtime.maxConcurrency()),
                Map.entry("max_browser_workers", runtime.maxBrowserWorkers()),
                Map.entry("max_output_bytes", runtime.maxOutputBytes()),
                Map.entry("max_aggregate_output_bytes", runtime.maxAggregateOutputBytes()),
                Map.entry("max_child_processes", runtime.maxChildProcesses()),
                Map.entry("max_total_child_processes", runtime.maxTotalChildProcesses()),
                Map.entry("max_task_duration_seconds", runtime.maxTaskDurationSeconds()),
                Map.entry("max_rss_bytes", runtime.maxRssBytes()),
                Map.entry("max_cpu_seconds", runtime.maxCpuSeconds()),
                Map.entry("desktop_enabled", runtime.desktopEnabled()),
                Map.entry("browser_adapter_configured", runtime.browserAdapterConfigured()),
                Map.entry("resource_enforcement", runtime.resourceEnforcement()),
                Map.entry("desktop_session_available", runtime.desktopSessionAvailable()),
                Map.entry("browser_session_available", runtime.browserSessionAvailable())));
        value.put("created_at", machine.createdAt());
        value.put("last_seen", machine.lastSeen());
        value.put("online", machine.online());
        return value;
    }

    static Map<String, Object> taskMap(TaskView task) {
        var value = new LinkedHashMap<String, Object>();
        value.put("id", task.id());
        value.put("machine_id", task.machineId());
        value.put("kind", task.kind());
        value.put("status", task.status());
        value.put("change_seq", task.changeSequence());
        if (task.exitCode() != null) value.put("exit_code", task.exitCode());
        if (task.error() != null && !task.error().isBlank()) value.put("error", compact(task.error(), 1024));
        if (task.outputBytes() > 0) value.put("output_bytes", task.outputBytes());
        if (task.outputTruncated()) value.put("output_truncated", true);
        if (task.attempt() > 1) value.put("attempt", task.attempt());
        if (!TaskStatus.terminal(task.status())) addProgress(value, task);
        return value;
    }

    private static void addProgress(Map<String, Object> value, TaskView task) {
        if (task.progressPhase() != null && !task.progressPhase().isBlank())
            value.put("progress_phase", task.progressPhase());
        if (task.progressPercent() != null) value.put("progress_percent", task.progressPercent());
        if (task.progressMessage() != null && !task.progressMessage().isBlank())
            value.put("progress_message", compact(task.progressMessage(), 256));
        if (task.progressPercent() == null) {
            if (task.progressCurrent() != null) value.put("progress_current", task.progressCurrent());
            if (task.progressTotal() != null) value.put("progress_total", task.progressTotal());
            if (task.progressUnit() != null && !task.progressUnit().isBlank())
                value.put("progress_unit", task.progressUnit());
        }
    }

    static Map<String, Object> taskDetailMap(TaskView task) {
        var value = new LinkedHashMap<>(taskMap(task));
        // Retain the last reported progress for diagnostics, without
        // presenting it as live progress in terminal task summaries.
        addProgress(value, task);
        if (task.requiredCapability() != null && !task.requiredCapability().isBlank())
            value.put("required_capability", task.requiredCapability());
        // Browser requests can contain credentials and selectors; the returned
        // browser output is the detail surface for those requests.
        if ("command".equals(task.kind()) && task.command() != null && !task.command().isBlank())
            value.put("command", compact(task.command(), 2048));
        if (task.cwd() != null && !task.cwd().isBlank()) value.put("cwd", compact(task.cwd(), 1024));
        value.put("timeout_seconds", task.timeoutSeconds());
        value.put("attempt", task.attempt());
        value.put("output_bytes", task.outputBytes());
        value.put("output_truncated", task.outputTruncated());
        if (task.error() != null && !task.error().isBlank()) value.put("error", compact(task.error(), 2048));
        value.put("created_at", task.createdAt());
        if (task.dispatchedAt() != null) value.put("dispatched_at", task.dispatchedAt());
        if (task.startedAt() != null) value.put("started_at", task.startedAt());
        if (task.finishedAt() != null) value.put("finished_at", task.finishedAt());
        if (task.progressMessage() != null && !task.progressMessage().isBlank())
            value.put("progress_message", compact(task.progressMessage(), 1024));
        if (task.progressCurrent() != null) value.put("progress_current", task.progressCurrent());
        if (task.progressTotal() != null) value.put("progress_total", task.progressTotal());
        if (task.progressUnit() != null && !task.progressUnit().isBlank())
            value.put("progress_unit", task.progressUnit());
        if (task.progressUpdatedAt() != null) value.put("progress_updated_at", task.progressUpdatedAt());
        if (task.metadataExpiresAt() != null) value.put("metadata_expires_at", task.metadataExpiresAt());
        if (task.outputExpiresAt() != null) value.put("output_expires_at", task.outputExpiresAt());
        value.put("pinned", task.pinned());
        if (task.archivedAt() != null) value.put("archived_at", task.archivedAt());
        return value;
    }

    private static String compact(String value, int max) {
        if (value == null || value.length() <= max) {
            return SensitiveValueRedactor.redact(value);
        }
        return SensitiveValueRedactor.redact(value.substring(0, max));
    }

    private static String resolveCwd(AgentRegistry agents, String machineId, String requestedCwd) {
        var machine = agents.findMachine(machineId, Instant.now())
                .orElseThrow(() -> new IllegalArgumentException("machine not found"));
        var defaultCwd = machine.defaultCwd() == null ? "" : machine.defaultCwd().trim();
        var cwd = requestedCwd == null || requestedCwd.isBlank()
                ? (defaultCwd.isBlank() ? null : defaultCwd)
                : requestedCwd.trim();
        return cwd;
    }

    private static TaskOrigin origin(McpAsyncServerExchange exchange, McpConversationService conversations,
                                     McpSchema.CallToolRequest request) {
        if (exchange == null || exchange.transportContext() == null) {
            throw new SecurityException("MCP transport principal is missing");
        }
        var value = exchange.transportContext().get("rcm.principal");
        if (!(value instanceof McpPrincipal principal)) {
            throw new SecurityException("MCP transport principal is missing");
        }
        var origin = conversationOrigin(principal, exchange.sessionId(), request);
        if (conversations != null) conversations.touch(origin, exchange.sessionId(), "streamable-http");
        return origin;
    }

    static TaskOrigin conversationOrigin(McpPrincipal principal, String connectionId,
                                         McpSchema.CallToolRequest request) {
        // A host-provided session is a correlation hint, never authentication.
        // Namespace it by authenticated identity so unrelated credentials cannot
        // pin each other's contracts even if they send the same hint.
        var hint = request == null || request.meta() == null ? null : request.meta().get("openai/session");
        if (!(hint instanceof String session) || session.isBlank() || session.length() > 256
                || session.chars().anyMatch(Character::isISOControl)) return principal.taskOrigin(connectionId);
        try {
            var raw = principal.principalId() + '\u0000' + principal.tokenId() + '\u0000' + session;
            var digest = java.security.MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return principal.taskOrigin("host-" + java.util.HexFormat.of().formatHex(digest));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static boolean fileReady(ArtifactTransferService.TransferDescriptor descriptor) {
        return Set.of("ready", "delivered").contains(descriptor.status());
    }

    static McpSchema.CallToolResult artifactHandleResult(Map<String, Object> payload) {
        try {
            var builder = McpSchema.CallToolResult.builder().structuredContent(payload)
                    .addTextContent(boundedJsonText(payload));
            if (payload.get("file") instanceof Map<?, ?> file
                    && file.get("download_url") instanceof String url && !url.isBlank()) {
                var name = asString(file.get("file_name"));
                if (name == null || name.isBlank()) name = "artifact";
                var link = McpSchema.ResourceLink.builder().name(name).title(name).uri(url);
                var mime = asString(file.get("mime_type"));
                if (mime != null && !mime.isBlank()) link.mimeType(mime);
                if (file.get("bytes") instanceof Number bytes && bytes.longValue() >= 0) link.size(bytes.longValue());
                builder.addContent(link.build());
            }
            return builder.build();
        } catch (IOException exception) {
            return error(exception);
        }
    }

    private static void requireScope(McpAsyncServerExchange exchange, String scope) {
        if (exchange == null || exchange.transportContext() == null) {
            throw new SecurityException("MCP transport principal is missing");
        }
        var value = exchange.transportContext().get("rcm.principal");
        if (!(value instanceof McpPrincipal principal) || !principal.allows(scope)) {
            throw new SecurityException("MCP token is missing required scope: " + scope);
        }
    }

    private static String bearerValue(String authorization) {
        if (authorization == null) return "";
        var parts = authorization.trim().split("\\s+", 2);
        return parts.length == 2 && "Bearer".equalsIgnoreCase(parts[0]) ? parts[1].trim() : "";
    }

    private static McpSchema.CallToolResult json(Object value) {
        return structuredJson(value);
    }

    private static McpSchema.CallToolResult structuredJson(Object value) {
        try {
            var text = boundedJsonText(value);
            return McpSchema.CallToolResult.builder()
                    .structuredContent(value)
                    .addTextContent(text)
                    .build();
        } catch (IOException exception) {
            return error(exception);
        }
    }

    /**
     * Last-resort guard for accidental future projections. Normal list/task
     * paths are much smaller; this prevents an unbounded field from silently
     * becoming a large model-context turn. Callers should use cursors or the
     * authenticated Console detail endpoint instead.
     */
    private static String boundedJsonText(Object value) throws IOException {
        var text = McpJsonDefaults.getMapper().writeValueAsString(value);
        if (text.length() > MAX_MCP_JSON_CHARS) {
            throw new IOException("MCP response exceeds the bounded context budget; use a cursor or Console detail endpoint");
        }
        return text;
    }

    private static McpSchema.CallToolResult error(Exception exception) {
        var message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        message = SensitiveValueRedactor.redact(message);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("kind", "error");
        payload.put("error", Map.of("code", errorCode(exception), "message", compact(message, 2048),
                "retryable", isRetryable(exception)));
        return McpSchema.CallToolResult.builder().isError(true)
                .structuredContent(payload).addTextContent(jsonText(payload)).build();
    }

    private static String errorCode(Exception exception) {
        if (exception instanceof SecurityException) return "FORBIDDEN";
        if (exception instanceof IllegalArgumentException) return "INVALID_ARGUMENT";
        if (exception instanceof InterruptedException) return "INTERRUPTED";
        return "CENTER_ERROR";
    }

    private static boolean isRetryable(Exception exception) {
        return exception instanceof InterruptedException
                || exception instanceof java.util.concurrent.TimeoutException
                || exception.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT).contains("transient");
    }

    private static <T> T args(McpSchema.CallToolRequest request, Class<T> type) {
        return McpJsonDefaults.getMapper().convertValue(request.arguments() == null ? Map.of() : request.arguments(), type);
    }

    record MachinesCoreArgs(Integer offset, Integer limit) {
    }

    record MachineInfoCoreArgs(@JsonProperty("machine_id") String machineId) {
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record CommandCoreArgs(@JsonProperty("machine_id") String machineId,
                           String command,
                           String cwd,
                           Map<String, String> env,
                           @JsonProperty("timeout_seconds") Integer timeoutSeconds,
                           @JsonProperty("wait_ms") Integer waitMs,
                           Integer limit,
                           @JsonProperty("idempotency_key") String idempotencyKey) {
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record DesktopCoreArgs(String operation,
                           @JsonProperty("machine_id") String machineId,
                           String executable,
                           List<String> args,
                           String cwd,
                           String text,
                           Integer x,
                           Integer y,
                           String key,
                           Integer x2,
                           Integer y2,
                           @JsonProperty("duration_ms") Integer durationMs,
                           Integer screen,
                           @JsonProperty("window_title") String windowTitle,
                           @JsonProperty("timeout_seconds") Integer timeoutSeconds,
                            @JsonProperty("wait_ms") Integer waitMs,
                            Integer limit,
                            @JsonProperty("idempotency_key") String idempotencyKey) {
        DesktopCoreArgs {
            args = args == null ? List.of() : List.copyOf(args);
        }
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record BrowserCoreArgs(@JsonProperty("machine_id") String machineId,
                           String command,
                           String cwd,
                           @JsonProperty("timeout_seconds") Integer timeoutSeconds,
                           @JsonProperty("wait_ms") Integer waitMs,
                           Integer limit,
                           Boolean detail,
                           @JsonProperty("idempotency_key") String idempotencyKey) {
    }

    record TaskCancelCoreArgs(@JsonProperty("task_id") String taskId) {
    }

    record ArtifactFileCore(@JsonProperty("download_url") String downloadUrl,
                        @JsonProperty("file_id") String fileId,
                        @JsonProperty("file_name") String fileName,
                        @JsonProperty("mime_type") String mimeType,
                        Long bytes,
                        String sha256) {
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record ArtifactPutCoreArgs(@JsonProperty("machine_id") String machineId,
                            ArtifactFileCore file,
                           @JsonProperty("destination_path") String destinationPath,
                           @JsonProperty("file_name") String fileName,
                           @JsonProperty("mime_type") String mimeType,
                           @JsonProperty("expected_bytes") Long expectedBytes,
                            @JsonProperty("expected_sha256") String expectedSha256,
                            Boolean overwrite,
                            @JsonProperty("wait_ms") Integer waitMs,
                            String cwd,
                           @JsonProperty("idempotency_key") String idempotencyKey) {
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record ArtifactGetCoreArgs(@JsonProperty("machine_id") String machineId,
                           @JsonProperty("source_path") String sourcePath,
                           @JsonProperty("file_name") String fileName,
                           @JsonProperty("mime_type") String mimeType,
                           @JsonProperty("delivery_mode") String deliveryMode,
                            @JsonProperty("wait_ms") Integer waitMs,
                            Integer limit,
                            String cwd,
                           @JsonProperty("idempotency_key") String idempotencyKey) {
    }

    record ArtifactReadCoreArgs(@JsonProperty("artifact_id") String artifactId,
                             @JsonProperty("transfer_id") String transferId,
                              @JsonProperty("delivery_mode") String deliveryMode,
                              Long cursor, Integer limit) {
    }

    private static String firstNonBlank(String primary, String fallback) {
        return primary != null && !primary.isBlank() ? primary.trim()
                : fallback != null && !fallback.isBlank() ? fallback.trim() : null;
    }
}
