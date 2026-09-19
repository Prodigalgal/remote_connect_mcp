package com.prodigalgal.remoteconnectmcp.center;

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
import com.prodigalgal.remoteconnectmcp.protocol.ArtifactRequest;
import com.prodigalgal.remoteconnectmcp.protocol.ArtifactResponse;
import com.prodigalgal.remoteconnectmcp.protocol.AgentConfigUpdate;
import com.prodigalgal.remoteconnectmcp.protocol.AgentMetadata;
import com.prodigalgal.remoteconnectmcp.protocol.AgentRuntimeDescriptor;
import com.prodigalgal.remoteconnectmcp.protocol.OutputRequest;
import com.prodigalgal.remoteconnectmcp.protocol.OutputResponse;
import com.prodigalgal.remoteconnectmcp.protocol.PollRequest;
import com.prodigalgal.remoteconnectmcp.protocol.PollResponse;
import com.prodigalgal.remoteconnectmcp.protocol.RegisterRequest;
import com.prodigalgal.remoteconnectmcp.protocol.RegisterResponse;
import com.prodigalgal.remoteconnectmcp.protocol.ScopeMode;
import com.prodigalgal.remoteconnectmcp.protocol.SensitiveValueRedactor;
import com.prodigalgal.remoteconnectmcp.protocol.TaskCommand;
import com.prodigalgal.remoteconnectmcp.protocol.TaskKind;
import com.prodigalgal.remoteconnectmcp.protocol.TaskUpdateRequest;
import com.prodigalgal.remoteconnectmcp.protocol.LaneMode;
import com.prodigalgal.remoteconnectmcp.protocol.WorkspacePolicyMode;
import com.prodigalgal.remoteconnectmcp.protocol.UpgradeArtifact;
import com.prodigalgal.remoteconnectmcp.protocol.UpgradePlan;
import com.prodigalgal.remoteconnectmcp.protocol.UpgradeStatusRequest;
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
        McpConfiguration.ProjectCoreArgs.class,
        McpConfiguration.CommandCoreArgs.class, McpConfiguration.DesktopCoreArgs.class,
        McpConfiguration.BrowserCoreArgs.class, McpConfiguration.TaskWaitCoreArgs.class,
        McpConfiguration.TaskOutputCoreArgs.class, McpConfiguration.TaskCancelCoreArgs.class,
        // The MCP SDK models are records.  The JVM mapper can discover record
        // components reflectively, while Native Image needs the component
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
        com.prodigalgal.remoteconnectmcp.protocol.ExecutionContract.class,
        com.prodigalgal.remoteconnectmcp.protocol.ExecutionContract.Budget.class,
        LaneMode.class, WorkspacePolicyMode.class,
        AgentConfigUpdate.class,
        OutputRequest.class, OutputResponse.class, PollRequest.class, PollResponse.class,
        RegisterRequest.class, RegisterResponse.class, TaskCommand.class,
        TaskCommand.DesktopAction.class, com.prodigalgal.remoteconnectmcp.protocol.FileTransferAction.class,
        com.prodigalgal.remoteconnectmcp.protocol.FileTransferResponse.class, TaskUpdateRequest.class,
        UpgradeArtifact.class, UpgradePlan.class, UpgradeStatusRequest.class,
        com.prodigalgal.remoteconnectmcp.protocol.UpgradeComponentPlan.class,
        // Admin API projections/requests are also Java records.  They are
        // serialized outside the MCP handler (for example /machines and
        // /tasks), so Native Image must retain their record component
        // accessors as well.
        MachineView.class, TaskView.class, UpgradeCampaignView.class,
        UpgradeTargetView.class, AgentConfigUpdateRequest.class,
        CreateTaskRequest.class, AdminCreateTaskRequest.class, CreateUpgradeCampaignRequest.class,
        ProjectRegistrationRequest.class, ProjectWorktreeRequest.class,
        ProjectGitOperationRequest.class, ProjectView.class, WorktreeView.class,
        AuditEventView.class,
        AdminController.IssueEnrollmentRequest.class, AdminController.IssueMcpTokenRequest.class,
        AdminController.MachineGrantRequest.class, AdminController.ProjectMemberRequest.class,
        AdminController.SessionCloseRequest.class,
        McpPrincipalService.IssueRequest.class, McpPrincipalService.IssuedToken.class,
        McpTokenView.class, McpAccessService.MachineGrantView.class,
        McpAccessService.ProjectMemberView.class, ExecutionSessionService.SessionView.class,
        McpQuotaService.QuotaView.class,
        ArtifactTransferService.TransferCreated.class, ArtifactTransferService.TransferDescriptor.class,
        ArtifactTransferService.AgentDownload.class, ArtifactTransferService.PublicArtifact.class,
        ArtifactTransferService.ArtifactAdminView.class,
        McpConfiguration.ArtifactFileCore.class, McpConfiguration.ArtifactPutCoreArgs.class,
        McpConfiguration.ArtifactGetCoreArgs.class, McpConfiguration.ArtifactReadCoreArgs.class,
        TaskService.ArtifactGcResult.class})
public class McpConfiguration {
    /** Stable Apps SDK resource URI; changing it would require reconnecting every client. */
    static final String ARTIFACT_VIEWER_URI = "ui://remote-connect-mcp/artifact-viewer-v1.html";
    // MCP inventory responses are intentionally smaller than the Console
    // pages.  The model normally only needs an identifier and a few routing
    // hints; detailed runtime data is an explicit machines(detail) follow-up.
    private static final int MAX_MACHINE_PAGE = 25;
    private static final int MAX_PROJECT_PAGE = 25;
    private static final int MAX_WORKTREE_PAGE = 10;
    private static final int MAX_OUTPUT_PAGE = 64 * 1024;
    private static final int MAX_INLINE_WORKTREE_SUMMARIES = 10;
    private static final int MAX_MCP_JSON_CHARS = 192 * 1024;
    // Keep screenshots useful in the ChatGPT conversation without allowing a
    // single desktop/browser result to consume the whole MCP context window.
    // Larger artifacts remain available through the authenticated Console
    // artifact endpoint using the returned SHA-256 metadata.
    private static final int MAX_INLINE_IMAGE_BYTES = 512 * 1024;

    @Bean
    public HttpServletStreamableServerTransportProvider mcpTransport(CenterTokenConfig tokens,
                                                                      McpPrincipalService principals) {
        ServerTransportSecurityValidator security = headers -> {
            var authorization = headers.entrySet().stream()
                    .filter(entry -> "authorization".equalsIgnoreCase(entry.getKey()))
                    .flatMap(entry -> entry.getValue().stream())
                    .findFirst()
                    .orElse("");
            var parts = authorization.trim().split("\\s+", 2);
            var bearer = parts.length == 2 && "Bearer".equalsIgnoreCase(parts[0]) ? parts[1].trim() : "";
            if (principals.resolve(bearer).isEmpty()) {
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
                    var principal = principals.resolve(request.getHeader("Authorization") == null
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

    @Bean(destroyMethod = "close")
    public ExecutorService mcpVirtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    public McpAsyncServer mcpServer(HttpServletStreamableServerTransportProvider transport,
                                    AgentRegistry agents,
                                    TaskService tasks,
                                    ProjectService projects,
                                    McpAccessService access,
                                    ArtifactTransferService transfers,
                                    ExecutorService mcpVirtualThreadExecutor,
                                    @Value("${rcm.version:dev}") String version) {
        var server = McpServer.async(transport)
                .serverInfo("remote-connect-mcp-center", version)
                .instructions("Use machines first to choose a machine by stable id, then command, desktop, browser, project or artifact as needed. Tools are asynchronous: return task handles promptly and use task_read for bounded progress. Keep MCP results compact; detailed logs, screenshots and documents are artifact references fetched on demand. Use an explicit scope object for project/worktree/path/workspace; unrestricted access must be explicit. The Center enforces principal, session, scope, lane and quota contracts regardless of model hints.")
                .strictToolNameValidation(true)
                .validateToolInputs(true)
                .requestTimeout(Duration.ofSeconds(30))
                .resources(artifactViewerResource(transfers))
                .tools(modelToolSpecs(agents, tasks, projects, access, transfers, mcpVirtualThreadExecutor))
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
        resourceMeta.put("ui.csp", standardCsp);
        if (!domains.isEmpty()) resourceMeta.put("ui.domain", domains.getFirst());
        var resourceUi = new LinkedHashMap<String, Object>();
        resourceUi.put("csp", standardCsp);
        if (!domains.isEmpty()) resourceUi.put("domain", domains.getFirst());
        resourceMeta.put("ui", resourceUi);
        var resource = McpSchema.Resource.builder(ARTIFACT_VIEWER_URI, "Remote Connect Artifact Viewer")
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

    /**
     * Keep the Viewer as a separately editable frontend resource. Every
     * release must package the classpath artifact; a missing resource is a
     * startup/configuration error.
     */
    private static String artifactViewerHtml() {
        try (var stream = McpConfiguration.class.getResourceAsStream("/mcp/artifact-viewer-v1.html")) {
            if (stream == null) throw new IllegalStateException("artifact viewer resource is missing from the Center image");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("artifact viewer resource cannot be read", exception);
        }
    }

    private static List<String> viewerDomains(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return List.of();
        try {
            var uri = URI.create(baseUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) return List.of();
            return List.of(uri.getScheme().toLowerCase(java.util.Locale.ROOT) + "://" + uri.getAuthority());
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
                                                                                    ProjectService projects,
                                                                                    McpAccessService access,
                                                                                    ArtifactTransferService transfers,
                                                                                    ExecutorService mcpVirtualThreadExecutor) {
        var scheduler = Schedulers.fromExecutor(mcpVirtualThreadExecutor);
        var artifactMeta = Map.<String, Object>of("openai/outputTemplate", ARTIFACT_VIEWER_URI,
                "ui/resourceUri", ARTIFACT_VIEWER_URI);
        return List.of(
                tool("machines", "Discover registered machines or fetch one bounded machine detail. Returns stable IDs and compact capability summaries.",
                        machinesModelSchema(),
                        (exchange, request) -> { requireScope(exchange, "mcp:read"); return machinesModel(agents, access, origin(exchange), request); }, scheduler),
                tool("command", "Queue one shell command on a selected machine and return a durable task handle immediately.",
                        commandModelSchema(),
                        (exchange, request) -> { requireScope(exchange, "mcp:execute"); return commandModel(agents, tasks, projects, access, origin(exchange), request); }, scheduler),
                tool("desktop", "Control an explicitly desktop-capable user session with semantic screenshot, window, input and launch operations.",
                        desktopModelSchema(),
                        (exchange, request) -> { requireScope(exchange, "mcp:execute"); return desktopModel(agents, tasks, projects, access, origin(exchange), request); }, scheduler),
                tool("browser", "Run one structured browser navigation, observation or interaction request on a browser-capable machine.",
                        browserModelSchema(),
                        (exchange, request) -> { requireScope(exchange, "mcp:execute"); return browserModel(agents, tasks, projects, access, origin(exchange), request); }, scheduler),
                tool("project", "Inspect or mutate a registered project/worktree through one explicit operation. Paths are opt-in and responses are paged.",
                        projectModelSchema(),
                        (exchange, request) -> { requireScope(exchange, "mcp:project"); return projectModel(projects, access, origin(exchange), request); }, scheduler),
                tool("artifact", "Transfer a ChatGPT file to a machine, retrieve a machine file, or read a compact artifact handle.",
                        artifactModelSchema(), artifactMeta,
                        (exchange, request) -> { requireScope(exchange,
                                "read".equalsIgnoreCase(asString(modelArguments(request).get("operation"))) ? "mcp:read" : "mcp:execute");
                            return artifactModel(agents, projects, access, transfers, origin(exchange), request); }, scheduler),
                tool("task_read", "Read one durable task state and one bounded output page; optionally wait briefly for a change.",
                        taskReadModelSchema(),
                        (exchange, request) -> { requireScope(exchange, "mcp:read"); return taskReadModel(tasks, origin(exchange), request); }, scheduler),
                tool("task_cancel", "Cancel one queued or running task owned by the current principal and session.",
                        taskCancelModelSchema(),
                        (exchange, request) -> { requireScope(exchange, "mcp:execute"); return taskCancelModel(tasks, transfers, origin(exchange), request); }, scheduler));
    }

    private static McpServerFeatures.AsyncToolSpecification tool(String name, String description, Map<String, Object> schema,
                                                                   BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler,
                                                                   reactor.core.scheduler.Scheduler scheduler) {
        return tool(name, description, schema, Map.of(), handler, scheduler);
    }

    private static McpServerFeatures.AsyncToolSpecification tool(String name, String description, Map<String, Object> schema,
                                                                   Map<String, Object> meta,
                                                                   BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler,
                                                                   reactor.core.scheduler.Scheduler scheduler) {
        var annotations = McpSchema.ToolAnnotations.builder()
                .readOnlyHint(name.equals("machines") || name.equals("task_read"))
                .idempotentHint(name.equals("machines") || name.equals("task_read") || name.equals("task_cancel"))
                .destructiveHint(name.equals("command") || name.equals("desktop") || name.equals("browser")
                        || name.equals("task_cancel") || name.equals("project") || name.equals("artifact"))
                .openWorldHint(name.equals("command") || name.equals("browser") || name.equals("project")
                        || name.equals("artifact"))
                .build();
        var toolBuilder = McpSchema.Tool.builder(name)
                .description(description)
                .inputSchema(schema)
                .annotations(annotations)
                .meta(meta);
        if (Set.of("machines", "command", "desktop", "browser", "project", "artifact", "task_read", "task_cancel").contains(name)) {
            toolBuilder.outputSchema(modelOutputSchema());
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

    private static Map<String, Object> modelNullableString(String description, int min, int max) {
        return Map.of("description", description, "anyOf", List.of(
                modelString(description, min, max), Map.of("type", "null")));
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

    private static Map<String, Object> modelScopeSchema() {
        return modelSchema(Map.ofEntries(
                Map.entry("mode", modelEnum("bounded execution scope; unrestricted must be explicit",
                        List.of("auto", "project", "worktree", "path", "workspace", "unrestricted"))),
                Map.entry("project_id", modelString("registered project identifier", 1, 180)),
                Map.entry("worktree_id", modelString("registered worktree identifier", 1, 180)),
                Map.entry("root", modelString("path scope root; only required for path mode", 1, 4096)),
                Map.entry("cwd", modelString("working directory inside the selected scope", 1, 4096))), List.of());
    }

    private static Map<String, Object> modelBrowserRequestSchema() {
        return modelSchema(Map.ofEntries(
                Map.entry("action", modelEnum("browser action", List.of("navigate", "observe", "click", "fill", "select", "press", "wait", "extract", "download", "screenshot"))),
                Map.entry("url", modelString("navigation URL", 1, 8192)),
                Map.entry("ref", modelString("rcm-ref-v1 snapshot reference", 1, 256)),
                Map.entry("selector", modelString("bounded CSS/text selector", 1, 2048)),
                Map.entry("text", modelString("text or value to enter", 0, 65536)),
                Map.entry("value", modelString("select value", 0, 4096)),
                Map.entry("keys", Map.of("type", "array", "description", "semantic key names", "items", modelString("key", 1, 64), "minItems", 1, "maxItems", 16)),
                Map.entry("wait_ms", modelInteger("bounded wait", 0, 15000)),
                Map.entry("timeout_ms", modelInteger("action timeout", 1, 300000)),
                Map.entry("include_snapshot", modelBoolean("return a bounded accessibility snapshot"))), List.of("action"));
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

    private static Map<String, Object> machinesModelSchema() {
        return modelSchema(Map.ofEntries(
                Map.entry("operation", modelEnum("inventory operation", List.of("list", "detail"))),
                Map.entry("machine_id", modelString("stable machine identifier", 1, 180)),
                Map.entry("offset", modelInteger("zero-based page offset", 0, 1000000)),
                Map.entry("limit", modelInteger("page size", 1, MAX_MACHINE_PAGE))), List.of("operation"));
    }

    private static Map<String, Object> commandModelSchema() {
        return modelSchema(Map.ofEntries(
                Map.entry("machine_id", modelString("target machine identifier", 1, 180)),
                Map.entry("command", modelString("shell command", 1, 65536)),
                Map.entry("scope", modelScopeSchema()),
                Map.entry("env", Map.of("type", "object", "description", "optional non-secret environment map", "additionalProperties", modelString("environment value", 0, 8192))),
                Map.entry("timeout_seconds", modelInteger("0 means Agent default", 0, 86400)),
                Map.entry("idempotency_key", Map.of("type", "string", "description", "optional stable retry key", "minLength", 8, "maxLength", 128, "pattern", "^[A-Za-z0-9._:-]+$"))),
                List.of("machine_id", "command"));
    }

    private static Map<String, Object> desktopModelSchema() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("operation", modelEnum("semantic desktop operation", List.of("screenshot", "screenshot_region", "screens", "windows", "launch", "click", "double_click", "right_click", "move", "drag", "shortcut", "type", "clipboard_read", "clipboard_write", "focus", "result")));
        properties.put("machine_id", modelString("desktop-capable machine identifier", 1, 180));
        properties.put("task_id", modelString("existing desktop task for result", 1, 180));
        properties.put("scope", modelScopeSchema());
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
        properties.put("idempotency_key", Map.of("type", "string", "description", "optional stable retry key", "minLength", 8, "maxLength", 128, "pattern", "^[A-Za-z0-9._:-]+$"));
        return modelSchema(properties, List.of("operation", "machine_id"));
    }

    private static Map<String, Object> browserModelSchema() {
        return modelSchema(Map.ofEntries(
                Map.entry("machine_id", modelString("browser-capable machine identifier", 1, 180)),
                Map.entry("request", modelBrowserRequestSchema()),
                Map.entry("scope", modelScopeSchema()),
                Map.entry("timeout_seconds", modelInteger("task timeout", 1, 86400)),
                Map.entry("wait_ms", modelInteger("bounded synchronous wait", 0, 15000)),
                Map.entry("idempotency_key", Map.of("type", "string", "description", "optional stable retry key", "minLength", 8, "maxLength", 128, "pattern", "^[A-Za-z0-9._:-]+$"))),
                List.of("machine_id", "request"));
    }

    private static Map<String, Object> projectModelSchema() {
        return modelSchema(Map.ofEntries(
                Map.entry("operation", modelEnum("project operation", List.of("list", "detail", "register", "remove", "worktree_create", "worktree_remove", "git_status", "git_diff", "git_log", "git_commit", "git_merge", "git_merge_abort"))),
                Map.entry("machine_id", modelString("target machine identifier", 1, 180)),
                Map.entry("project_id", modelString("registered project identifier", 1, 180)),
                Map.entry("worktree_id", modelString("registered worktree identifier", 1, 180)),
                Map.entry("name", modelString("project display name", 1, 256)),
                Map.entry("root_path", modelString("absolute project root; registration only", 1, 4096)),
                Map.entry("repository_path", modelString("optional repository path", 1, 4096)),
                Map.entry("default_ref", modelString("default Git ref", 1, 512)),
                Map.entry("ref", modelString("Git ref", 1, 512)),
                Map.entry("message", modelString("single-line commit message", 1, 4096)),
                Map.entry("mode", modelEnum("git diff mode", List.of("stat", "patch"))),
                Map.entry("offset", modelInteger("page offset", 0, 1000000)),
                Map.entry("limit", modelInteger("page size", 1, MAX_PROJECT_PAGE)),
                Map.entry("include_paths", modelBoolean("explicitly include local paths")),
                Map.entry("idempotency_key", Map.of("type", "string", "description", "optional stable retry key", "minLength", 8, "maxLength", 128, "pattern", "^[A-Za-z0-9._:-]+$"))),
                List.of("operation"));
    }

    private static Map<String, Object> artifactModelSchema() {
        return modelSchema(Map.ofEntries(
                Map.entry("operation", modelEnum("artifact operation", List.of("put", "get", "read"))),
                Map.entry("machine_id", modelString("target/source machine identifier", 1, 180)),
                Map.entry("file", modelFileObjectSchema()),
                Map.entry("artifact_id", modelString("artifact identifier", 1, 180)),
                Map.entry("transfer_id", modelString("transfer identifier", 1, 180)),
                Map.entry("destination_path", modelString("complete target path or relative path inside scope", 1, 4096)),
                Map.entry("source_path", modelString("complete source path inside scope", 1, 4096)),
                Map.entry("file_name", modelString("display file name", 1, 512)),
                Map.entry("mime_type", modelString("MIME type", 1, 256)),
                Map.entry("expected_bytes", modelInteger("expected byte size", 0L, 4L * 1024 * 1024 * 1024)),
                Map.entry("expected_sha256", Map.of("type", "string", "description", "expected SHA-256", "pattern", "^[A-Fa-f0-9]{64}$")),
                Map.entry("overwrite", modelBoolean("replace an existing target")),
                Map.entry("scope", modelScopeSchema()),
                Map.entry("idempotency_key", Map.of("type", "string", "description", "optional stable retry key", "minLength", 8, "maxLength", 128, "pattern", "^[A-Za-z0-9._:-]+$"))),
                List.of("operation"));
    }

    private static Map<String, Object> taskReadModelSchema() {
        return modelSchema(Map.ofEntries(
                Map.entry("task_id", modelString("task identifier", 1, 180)),
                Map.entry("cursor", modelInteger("output byte cursor", 0, Integer.MAX_VALUE)),
                Map.entry("wait_ms", modelInteger("bounded wait", 0, 20000)),
                Map.entry("limit", modelInteger("output page size", 1, MAX_OUTPUT_PAGE))), List.of("task_id"));
    }

    private static Map<String, Object> taskCancelModelSchema() {
        return modelSchema(Map.of("task_id", modelString("task identifier", 1, 180)), List.of("task_id"));
    }

    private static Map<String, Object> modelOutputSchema() {
        var taskProperties = new LinkedHashMap<String, Object>();
        taskProperties.put("id", modelString("task identifier", 1, 180));
        taskProperties.put("machine_id", modelString("machine identifier", 1, 180));
        taskProperties.put("kind", modelString("task kind", 1, 64));
        taskProperties.put("status", modelString("task status", 1, 64));
        taskProperties.put("attempt", modelInteger("dispatch attempt", 0, Integer.MAX_VALUE));
        taskProperties.put("output_bytes", modelInteger("output bytes", 0, Integer.MAX_VALUE));
        taskProperties.put("output_truncated", modelBoolean("output was truncated"));
        taskProperties.put("artifact_bytes", modelInteger("artifact bytes", 0L, 4L * 1024 * 1024 * 1024));
        taskProperties.put("artifact_mime", modelNullableString("artifact MIME", 0, 256));
        taskProperties.put("artifact_sha256", modelNullableString("artifact SHA-256", 0, 128));
        taskProperties.put("next_action", modelString("next action", 0, 512));
        var task = modelSchema(taskProperties, List.of("id", "machine_id", "kind", "status"));
        // Task projections intentionally grow as capabilities are added (for
        // example execution_scope, result_channel and contract timestamps).
        // Keep the core fields discoverable without making a new harmless
        // projection field invalidate the whole MCP result.
        task.put("additionalProperties", true);
        var output = modelSchema(Map.ofEntries(
                Map.entry("text", modelString("bounded output page", 0, MAX_OUTPUT_PAGE)),
                Map.entry("cursor", modelInteger("current cursor", 0, Integer.MAX_VALUE)),
                Map.entry("next_cursor", modelInteger("next cursor", 0, Integer.MAX_VALUE)),
                Map.entry("more", modelBoolean("more output exists"))), List.of());
        var error = modelSchema(Map.ofEntries(
                Map.entry("code", modelString("stable error code", 1, 128)),
                Map.entry("message", modelString("safe error message", 1, 2048)),
                Map.entry("retryable", modelBoolean("whether retry is safe"))), List.of("code", "message", "retryable"));
        // Different compact tools use different top-level projections
        // (machines, projects, task, artifact and transfer).  The declared
        // fields above document the common envelope; unknown projection
        // fields remain valid so clients do not reject a correct response
        // merely because a capability added a bounded metadata field.
        var result = modelSchema(Map.ofEntries(
                Map.entry("kind", modelEnum("result kind", List.of("task", "machines", "machine", "project", "artifact", "output", "error"))),
                Map.entry("task", task),
                Map.entry("output", output),
                Map.entry("machines", Map.of("type", "array", "items", modelSchema(Map.ofEntries(
                        Map.entry("id", modelString("machine identifier", 1, 180)), Map.entry("name", modelString("machine name", 0, 256)),
                        Map.entry("os", modelString("operating system", 0, 64)), Map.entry("arch", modelString("architecture", 0, 64)),
                        Map.entry("version", modelString("agent version", 0, 128)), Map.entry("scope_mode", modelString("scope mode", 0, 64)),
                        Map.entry("capabilities", Map.of("type", "array", "items", modelString("capability", 1, 64))),
                        Map.entry("capabilities_truncated", modelBoolean("capability list truncated")), Map.entry("online", modelBoolean("online state"))),
                        List.of("id", "online")), "maxItems", MAX_MACHINE_PAGE)),
                Map.entry("artifact", Map.of("type", "object", "additionalProperties", true)),
                Map.entry("file", Map.of("type", "object", "additionalProperties", true)),
                Map.entry("offset", modelInteger("zero-based page offset", 0, Integer.MAX_VALUE)),
                Map.entry("limit", modelInteger("page size", 1, MAX_OUTPUT_PAGE)),
                Map.entry("total", modelInteger("total visible records", 0, Integer.MAX_VALUE)),
                Map.entry("total_worktrees", modelInteger("total worktrees", 0, Integer.MAX_VALUE)),
                Map.entry("has_more", modelBoolean("more records are available")),
                Map.entry("next_action", modelString("next action", 0, 512)),
                Map.entry("error", error)), List.of());
        result.put("additionalProperties", true);
        return result;
    }

    private static McpSchema.CallToolResult machinesModel(AgentRegistry agents, McpAccessService access,
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

    private static McpSchema.CallToolResult commandModel(AgentRegistry agents, TaskService tasks,
                                                         ProjectService projects, McpAccessService access,
                                                         TaskOrigin origin, McpSchema.CallToolRequest request) {
        try {
            var arguments = modelArguments(request);
            var normalized = new LinkedHashMap<String, Object>();
            normalized.put("machine_id", requiredModelString(arguments, "machine_id"));
            normalized.put("command", requiredModelString(arguments, "command"));
            copyIfPresent(arguments, normalized, "env");
            copyIfPresent(arguments, normalized, "timeout_seconds");
            normalized.put("idempotency_key", ensureModelIdempotency(arguments, origin, "command"));
            addScopeFields(arguments, normalized);
            return commandCore(agents, tasks, projects, access, origin, modelRequest("command", normalized));
        } catch (Exception exception) {
            return error(exception);
        }
    }

    private static McpSchema.CallToolResult desktopModel(AgentRegistry agents, TaskService tasks,
                                                         ProjectService projects, McpAccessService access,
                                                         TaskOrigin origin, McpSchema.CallToolRequest request) {
        try {
            var arguments = modelArguments(request);
            var normalized = new LinkedHashMap<String, Object>();
            normalized.put("operation", "shortcut".equals(
                    asString(arguments.get("operation")).toLowerCase(java.util.Locale.ROOT))
                    ? "key" : requiredModelString(arguments, "operation"));
            normalized.put("machine_id", requiredModelString(arguments, "machine_id"));
            for (var key : List.of("task_id", "executable", "args", "text", "x", "y", "x2", "y2",
                    "duration_ms", "screen", "window_title", "timeout_seconds", "wait_ms")) {
                copyIfPresent(arguments, normalized, key);
            }
            if (arguments.containsKey("keys")) {
                var keys = stringList(arguments.get("keys"), "keys", 16, 64);
                normalized.put("key", String.join("+", keys));
            } else {
                copyIfPresent(arguments, normalized, "key");
            }
            normalized.put("idempotency_key", ensureModelIdempotency(arguments, origin, "desktop"));
            addScopeFields(arguments, normalized);
            return desktopCore(agents, tasks, projects, access, origin, modelRequest("desktop", normalized));
        } catch (Exception exception) {
            return error(exception);
        }
    }

    private static McpSchema.CallToolResult browserModel(AgentRegistry agents, TaskService tasks,
                                                         ProjectService projects, McpAccessService access,
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
            copyIfPresent(arguments, normalized, "timeout_seconds");
            copyIfPresent(arguments, normalized, "wait_ms");
            normalized.put("idempotency_key", ensureModelIdempotency(arguments, origin, "browser"));
            addScopeFields(arguments, normalized);
            return browserCore(agents, tasks, projects, access, origin, modelRequest("browser", normalized));
        } catch (Exception exception) {
            return error(exception);
        }
    }

    private static McpSchema.CallToolResult projectModel(ProjectService projects, McpAccessService access,
                                                         TaskOrigin origin, McpSchema.CallToolRequest request) {
        try {
            var arguments = modelArguments(request);
            var normalized = new LinkedHashMap<>(arguments);
            var operation = requiredModelString(arguments, "operation");
            if (Set.of("worktree_create", "worktree_remove", "git_commit", "git_merge", "git_merge_abort").contains(operation)) {
                normalized.put("idempotency_key", ensureModelIdempotency(arguments, origin, "project"));
            }
            return projectCore(projects, access, origin, modelRequest("project", normalized));
        } catch (Exception exception) {
            return error(exception);
        }
    }

    private static McpSchema.CallToolResult artifactModel(AgentRegistry agents, ProjectService projects,
                                                          McpAccessService access, ArtifactTransferService transfers,
                                                          TaskOrigin origin, McpSchema.CallToolRequest request) {
        try {
            var arguments = modelArguments(request);
            var operation = requiredModelString(arguments, "operation");
            if ("read".equals(operation)) {
                var normalized = new LinkedHashMap<String, Object>();
                copyIfPresent(arguments, normalized, "artifact_id");
                copyIfPresent(arguments, normalized, "transfer_id");
                return artifactReadCore(transfers, origin, modelRequest("artifact", normalized));
            }
            var normalized = new LinkedHashMap<String, Object>();
            normalized.put("machine_id", requiredModelString(arguments, "machine_id"));
            normalized.put("idempotency_key", ensureModelIdempotency(arguments, origin, "artifact:" + operation));
            if ("put".equals(operation)) {
                normalized.put("file", requiredModelMap(arguments, "file"));
                normalized.put("destination_path", requiredModelString(arguments, "destination_path"));
                for (var key : List.of("file_name", "mime_type", "expected_bytes", "expected_sha256", "overwrite")) {
                    copyIfPresent(arguments, normalized, key);
                }
                addScopeFields(arguments, normalized);
                return artifactPutCore(agents, projects, access, transfers, origin, modelRequest("artifact", normalized));
            }
            if (!"get".equals(operation)) throw new IllegalArgumentException("operation must be put, get, or read");
            normalized.put("source_path", requiredModelString(arguments, "source_path"));
            for (var key : List.of("file_name", "mime_type")) copyIfPresent(arguments, normalized, key);
            addScopeFields(arguments, normalized);
            return artifactGetCore(agents, projects, access, transfers, origin, modelRequest("artifact", normalized));
        } catch (Exception exception) {
            return error(exception);
        }
    }

    private static McpSchema.CallToolResult taskReadModel(TaskService tasks, TaskOrigin origin,
                                                          McpSchema.CallToolRequest request) {
        try {
            var arguments = modelArguments(request);
            var taskId = requiredModelString(arguments, "task_id");
            var cursor = optionalModelInt(arguments, "cursor", 0, Integer.MAX_VALUE, 0);
            var waitMs = optionalModelInt(arguments, "wait_ms", 0, 20000, 0);
            var limit = optionalModelInt(arguments, "limit", 1, MAX_OUTPUT_PAGE, 16 * 1024);
            var current = tasks.findFor(origin, taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
            var view = waitMs == 0 ? new TaskView(current)
                    : tasks.waitForChange(origin, taskId, cursor, Duration.ofMillis(waitMs));
            return taskResult(tasks, origin, view, cursor, limit);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return error(exception);
        } catch (Exception exception) {
            return error(exception);
        }
    }

    private static McpSchema.CallToolResult taskCancelModel(TaskService tasks, ArtifactTransferService transfers,
                                                            TaskOrigin origin, McpSchema.CallToolRequest request) {
        var arguments = modelArguments(request);
        var normalized = Map.<String, Object>of("task_id", requiredModelString(arguments, "task_id"));
        return taskCancelCore(tasks, transfers, origin, modelRequest("task_cancel", normalized));
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

    private static int optionalModelInt(Map<String, Object> values, String key, int min, int max, int fallback) {
        var value = values.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number number)) throw new IllegalArgumentException(key + " must be an integer");
        var result = number.intValue();
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

    private static void addScopeFields(Map<String, Object> source, Map<String, Object> target) {
        var scope = source.get("scope") instanceof Map<?, ?> ? requiredModelMap(source, "scope") : Map.<String, Object>of();
        copyIfPresent(scope, target, "project_id");
        copyIfPresent(scope, target, "worktree_id");
        copyIfPresent(scope, target, "root");
        copyIfPresent(scope, target, "cwd");
        var mode = asString(scope.get("mode"));
        if (mode != null && !mode.isBlank() && !"auto".equalsIgnoreCase(mode)) target.put("scope_mode", mode);
        else target.remove("scope_mode");
    }

    private static String ensureModelIdempotency(Map<String, Object> values, TaskOrigin origin, String tool) {
        var explicit = asString(values.get("idempotency_key"));
        if (explicit != null && !explicit.isBlank()) return explicit.trim();
        var copy = new LinkedHashMap<>(values);
        copy.remove("idempotency_key");
        var window = Instant.now().getEpochSecond() / 60;
        try {
            var canonical = McpJsonDefaults.getMapper().writeValueAsString(copy);
            var input = tool + "\u0000" + origin.principalId() + "\u0000" + origin.connectionId()
                    + "\u0000" + window + "\u0000" + canonical;
            var digest = java.security.MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return "auto_" + java.util.HexFormat.of().formatHex(digest, 0, 24);
        } catch (Exception exception) {
            throw new IllegalStateException("could not derive idempotency key", exception);
        }
    }

    private static McpSchema.CallToolRequest modelRequest(String name, Map<String, Object> arguments) {
        return new McpSchema.CallToolRequest(name, arguments, Map.of());
    }

    private static McpSchema.CallToolResult artifactPutCore(AgentRegistry agents, ProjectService projects,
                                                        McpAccessService access, ArtifactTransferService transfers,
                                                        TaskOrigin origin, McpSchema.CallToolRequest request) {
        try {
            var args = args(request, ArtifactPutCoreArgs.class);
            if (args.file() == null) throw new IllegalArgumentException("file is required");
            access.authorizeExecution(origin, args.machineId(), args.projectId());
            var scope = resolveScope(agents, projects, args.machineId(), args.projectId(), args.worktreeId(),
                    args.scopeMode(), args.scopeRoot(), args.cwd());
            var command = new TaskCommand("", TaskKind.FILE_TRANSFER, "file_transfer", null, scope.cwd(), Map.of(), 0, null, Instant.now(), null, 0, null);
            var create = new CreateTaskRequest(args.machineId(), command, args.idempotencyKey(), args.projectId(), args.worktreeId(),
                    scope.mode(), scope.root(), args.workspacePolicy(), args.laneMode(), args.sessionId(), args.risk(),
                    Boolean.TRUE.equals(args.elevationRequired()), origin);
            var file = args.file();
            var name = firstNonBlank(args.fileName(), file.fileName());
            var mime = firstNonBlank(args.mimeType(), file.mimeType());
            var result = transfers.createWebToAgent(origin, create, file.fileId(), args.destinationPath(), name, mime,
                    URI.create(file.downloadUrl()), args.expectedBytes() == null ? file.bytes() : args.expectedBytes(),
                    firstNonBlank(args.expectedSha256(), file.sha256()), Boolean.TRUE.equals(args.overwrite()));
            return structuredJson(Map.of("task", taskMap(result.task()), "transfer", transferMap(result.transfer()),
                    "next_action", "call task_read, then artifact with operation=read and the returned artifact_id"));
        } catch (Exception exception) {
            return error(exception);
        }
    }

    private static McpSchema.CallToolResult artifactGetCore(AgentRegistry agents, ProjectService projects,
                                                        McpAccessService access, ArtifactTransferService transfers,
                                                        TaskOrigin origin, McpSchema.CallToolRequest request) {
        try {
            var args = args(request, ArtifactGetCoreArgs.class);
            access.authorizeExecution(origin, args.machineId(), args.projectId());
            var scope = resolveScope(agents, projects, args.machineId(), args.projectId(), args.worktreeId(),
                    args.scopeMode(), args.scopeRoot(), args.cwd());
            var command = new TaskCommand("", TaskKind.FILE_TRANSFER, "file_transfer", null, scope.cwd(), Map.of(), 0, null, Instant.now(), null, 0, null);
            var create = new CreateTaskRequest(args.machineId(), command, args.idempotencyKey(), args.projectId(), args.worktreeId(),
                    scope.mode(), scope.root(), args.workspacePolicy(), args.laneMode(), args.sessionId(), args.risk(),
                    Boolean.TRUE.equals(args.elevationRequired()), origin);
            var result = transfers.createAgentToWeb(origin, create, args.sourcePath(), args.fileName(), args.mimeType());
            return structuredJson(Map.of("task", taskMap(result.task()), "transfer", transferMap(result.transfer()),
                    "next_action", "call task_read until completed, then artifact with operation=read and the returned artifact_id"));
        } catch (Exception exception) {
            return error(exception);
        }
    }

    private static McpSchema.CallToolResult artifactReadCore(ArtifactTransferService transfers, TaskOrigin origin,
                                                         McpSchema.CallToolRequest request) {
        try {
            var args = args(request, ArtifactReadCoreArgs.class);
            var descriptor = args.artifactId() == null || args.artifactId().isBlank()
                    ? transfers.findByTransfer(args.transferId(), origin).orElseThrow(() -> new IllegalArgumentException("artifact or transfer id is required"))
                    : transfers.findByArtifact(args.artifactId(), origin).orElseThrow(() -> new IllegalArgumentException("artifact not found"));
            var payload = new LinkedHashMap<String, Object>();
            payload.put("artifact_id", descriptor.artifactId());
            payload.put("transfer_id", descriptor.transferId());
            payload.put("status", descriptor.status());
            payload.put("bytes", descriptor.bytes());
            payload.put("sha256", descriptor.sha256());
            payload.put("mime_type", descriptor.mimeType());
            payload.put("file_name", descriptor.fileName());
            if (descriptor.downloadUrl() != null && !descriptor.downloadUrl().isBlank()) {
                var file = new LinkedHashMap<String, Object>();
                file.put("file_id", descriptor.artifactId());
                file.put("download_url", descriptor.downloadUrl());
                file.put("preview_url", transfers.publicUrl(descriptor.artifactId(), origin,
                        transfers.sessionForTask(descriptor.taskId()), "preview"));
                file.put("mime_type", descriptor.mimeType() == null || descriptor.mimeType().isBlank()
                        ? "application/octet-stream" : descriptor.mimeType());
                file.put("file_name", descriptor.fileName());
                file.put("bytes", descriptor.bytes());
                file.put("sha256", descriptor.sha256());
                payload.put("file", file);
            }
            return structuredJson(payload);
        } catch (Exception exception) {
            return error(exception);
        }
    }

    private static Map<String, Object> transferMap(ArtifactTransferService.TransferDescriptor value) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("transfer_id", value.transferId());
        payload.put("artifact_id", value.artifactId());
        payload.put("direction", value.direction());
        payload.put("task_id", value.taskId());
        payload.put("status", value.status());
        payload.put("bytes", value.bytes());
        payload.put("bytes_transferred", value.bytesTransferred());
        payload.put("sha256", value.sha256());
        payload.put("file_name", value.fileName());
        payload.put("mime_type", value.mimeType());
        if (value.downloadUrl() != null && !value.downloadUrl().isBlank()) payload.put("download_url", value.downloadUrl());
        if (value.error() != null && !value.error().isBlank()) payload.put("error", value.error());
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
            return json(Map.of("machines", values, "offset", offset, "limit", limit,
                    "total", total, "has_more", offset + values.size() < total,
                    "next_action", offset + values.size() < total
                            ? "call machines with operation=list and offset + limit" : "no more machines"));
        } catch (Exception exception) {
            return error(exception);
        }
    }

    private static McpSchema.CallToolResult machineInfoCore(AgentRegistry agents, McpAccessService access,
                                                        TaskOrigin origin, McpSchema.CallToolRequest request) {
        try {
            var args = args(request, MachineInfoCoreArgs.class);
            access.authorizeMachine(origin, args.machineId(), "read");
            var machine = agents.findMachine(args.machineId(), Instant.now()).orElseThrow(() -> new IllegalArgumentException("machine not found"));
            return json(machineMap(machine));
        } catch (Exception exception) {
            return error(exception);
        }
    }

    private static McpSchema.CallToolResult projectCore(ProjectService projects, McpAccessService access, TaskOrigin origin,
                                                    McpSchema.CallToolRequest request) {
        try {
            var args = args(request, ProjectCoreArgs.class);
            var operation = args.operation() == null ? "" : args.operation().trim().toLowerCase(java.util.Locale.ROOT);
            return switch (operation) {
                case "list" -> {
                    var offset = args.offset() == null ? 0 : args.offset();
                    var limit = args.limit() == null ? 25 : args.limit();
                    if (offset < 0 || limit < 1 || limit > MAX_PROJECT_PAGE) {
                        throw new IllegalArgumentException("project list offset must be non-negative and limit must be between 1 and " + MAX_PROJECT_PAGE);
                    }
                    var values = projects.listAll(args.machineId()).stream()
                            .filter(value -> access.canReadMachine(origin, value.machineId()))
                            .filter(value -> access.canReadProject(origin, value.id()))
                            .toList();
                    var total = values.size();
                    values = offset >= total ? List.of() : values.subList(offset, Math.min(total, offset + limit));
                    var summaries = values.stream().map(McpConfiguration::projectSummary).toList();
                    yield json(Map.of("projects", summaries, "offset", offset, "limit", limit,
                            "total", total, "has_more", offset + values.size() < total,
                            "next_action", offset + values.size() < total
                                    ? "call project list with offset + limit" : "no more projects"));
                }
                case "detail" -> {
                    var project = projects.find(args.projectId());
                    access.authorizeMachine(origin, project.machineId(), "read");
                    access.authorizeProject(origin, project.id(), "read");
                    var offset = args.offset() == null ? 0 : args.offset();
                    var limit = args.limit() == null ? MAX_WORKTREE_PAGE : args.limit();
                    if (offset < 0 || limit < 1 || limit > MAX_WORKTREE_PAGE) {
                        throw new IllegalArgumentException("project detail offset must be non-negative and limit must be between 1 and " + MAX_WORKTREE_PAGE);
                    }
                    yield json(projectDetail(project, offset, limit, Boolean.TRUE.equals(args.includePaths())));
                }
                case "register" -> {
                    access.authorizeMachine(origin, args.machineId(), "admin");
                    var created = projects.register(new ProjectRegistrationRequest(
                            args.machineId(), args.name(), args.rootPath(), args.repositoryPath(), args.defaultRef()));
                    if (!origin.isConfigured()) {
                        // The principal that explicitly registered a project
                        // becomes its first admin member.  Shared system
                        // identity does not
                        // create durable ACL rows.
                        access.grantProject(origin.principalId(), created.id(), Set.of("admin"), null);
                    }
                    yield json(Map.of("project", projectSummary(created),
                            "next_action", "use project list or an explicit project operation for details"));
                }
                case "remove" -> {
                    var project = projects.find(args.projectId());
                    access.authorizeMachine(origin, project.machineId(), "admin");
                    access.authorizeProject(origin, project.id(), "admin");
                    yield json(Map.of("project", projectSummary(projects.remove(args.projectId()))));
                }
                case "worktree_create" -> {
                    var project = projects.find(args.projectId());
                    access.authorizeMachine(origin, project.machineId(), "execute");
                    access.authorizeProject(origin, project.id(), "write");
                    var value = projects.createWorktree(args.projectId(), new ProjectWorktreeRequest(args.ref(), args.idempotencyKey()), origin);
                    yield json(Map.of("worktree", worktreeSummary(value), "next_action", "use task_read with the returned task_id, then submit project-scoped tasks"));
                }
                case "worktree_remove" -> {
                    var project = projects.find(args.projectId());
                    access.authorizeMachine(origin, project.machineId(), "execute");
                    access.authorizeProject(origin, project.id(), "write");
                    var value = projects.removeWorktree(args.projectId(), args.worktreeId(), args.idempotencyKey(), origin);
                    yield json(Map.of("worktree", worktreeSummary(value), "next_action", "use task_read with the returned task_id"));
                }
                case "git_status", "git_diff", "git_log", "git_commit", "git_merge", "git_merge_abort" -> {
                    var project = projects.find(args.projectId());
                    access.authorizeMachine(origin, project.machineId(), "execute");
                    var gitAction = switch (operation) {
                        case "git_status", "git_diff", "git_log" -> "read";
                        default -> "write";
                    };
                    access.authorizeProject(origin, project.id(), gitAction);
                    var gitOperation = operation.substring("git_".length());
                    var value = projects.gitOperation(args.projectId(), gitOperation,
                            new ProjectGitOperationRequest(args.worktreeId(), args.ref(), args.message(), args.mode(), args.idempotencyKey()), origin);
                    yield json(Map.of("task", taskMap(value),
                            "next_action", "use task_read with the returned task_id"));
                }
                default -> throw new IllegalArgumentException("operation must be list, detail, register, remove, worktree_create, worktree_remove, or git_* operation");
            };
        } catch (Exception exception) {
            return error(exception);
        }
    }

    private static McpSchema.CallToolResult commandCore(AgentRegistry agents, TaskService tasks,
                                                         ProjectService projects, McpAccessService access,
                                                         TaskOrigin origin,
                                                         McpSchema.CallToolRequest request) {
        try {
            var args = args(request, CommandCoreArgs.class);
            access.authorizeExecution(origin, args.machineId(), args.projectId());
            var timeout = args.timeoutSeconds() == null ? 0 : args.timeoutSeconds();
            var scope = resolveScope(agents, projects, args.machineId(), args.projectId(), args.worktreeId(),
                    args.scopeMode(), args.scopeRoot(), args.cwd());
            var command = new com.prodigalgal.remoteconnectmcp.protocol.TaskCommand("", com.prodigalgal.remoteconnectmcp.protocol.TaskKind.COMMAND,
                    null, args.command(), scope.cwd(), args.env(), timeout, null, Instant.now(), null, 0, null);
            var task = tasks.create(new CreateTaskRequest(args.machineId(), command, args.idempotencyKey(),
                    args.projectId(), args.worktreeId(), scope.mode(), scope.root(), args.workspacePolicy(), args.laneMode(),
                    args.sessionId(), args.risk(), Boolean.TRUE.equals(args.elevationRequired()), origin), "mcp", origin);
            return json(Map.of("task", taskMap(task), "next_action", "use task_read with this task_id"));
        } catch (Exception exception) {
            return error(exception);
        }
    }

    private static McpSchema.CallToolResult browserCore(AgentRegistry agents, TaskService tasks,
                                                    ProjectService projects, McpAccessService access,
                                                    TaskOrigin origin,
                                                    McpSchema.CallToolRequest request) {
        try {
            var args = args(request, BrowserCoreArgs.class);
            access.authorizeExecution(origin, args.machineId(), args.projectId());
            var machine = agents.findMachine(args.machineId(), Instant.now()).orElseThrow(() -> new IllegalArgumentException("machine not found"));
            if (!machine.capabilities().contains("browser")) throw new IllegalArgumentException("machine does not advertise browser capability");
            var timeout = args.timeoutSeconds() == null ? 300 : args.timeoutSeconds();
            var scope = resolveScope(agents, projects, args.machineId(), args.projectId(), args.worktreeId(),
                    args.scopeMode(), args.scopeRoot(), args.cwd());
            var command = new com.prodigalgal.remoteconnectmcp.protocol.TaskCommand("", com.prodigalgal.remoteconnectmcp.protocol.TaskKind.BROWSER,
                    "browser", args.command(), scope.cwd(), Map.of(), timeout, null, Instant.now(), null, 0, null);
            var created = tasks.create(new CreateTaskRequest(args.machineId(), command, args.idempotencyKey(),
                    args.projectId(), args.worktreeId(), scope.mode(), scope.root(), args.workspacePolicy(), args.laneMode(),
                    args.sessionId(), args.risk(), Boolean.TRUE.equals(args.elevationRequired()), origin), "mcp", origin);
            var waitMs = args.waitMs() == null ? 0 : args.waitMs();
            if (waitMs < 0 || waitMs > 15000) throw new IllegalArgumentException("wait_ms must be between 0 and 15000");
            if (waitMs > 0) return taskResult(tasks, origin,
                    tasks.waitForTerminal(origin, created.id(), Duration.ofMillis(waitMs)), 0, 16 * 1024);
            return json(Map.of("task", taskMap(created), "next_action", "use task_read with this task_id"));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return error(exception);
        } catch (Exception exception) {
            return error(exception);
        }
    }

    private static McpSchema.CallToolResult desktopCore(AgentRegistry agents, TaskService tasks,
                                                    ProjectService projects, McpAccessService access,
                                                    TaskOrigin origin,
                                                    McpSchema.CallToolRequest request) {
        try {
            var args = args(request, DesktopCoreArgs.class);
            var operation = args.operation() == null ? "" : args.operation().trim().toLowerCase();
            // All desktop operations are queued by default.  A caller may opt
            // into a short bounded wait explicitly, but a screenshot must not
            // pin the MCP request simply because it is the default action.
            var waitMs = args.waitMs() == null ? 0 : args.waitMs();
            if (waitMs < 0 || waitMs > 15000) throw new IllegalArgumentException("wait_ms must be between 0 and 15000");
            if ("result".equals(operation)) {
                var taskState = tasks.findFor(origin, args.taskId()).orElseThrow(() -> new IllegalArgumentException("task not found"));
                if (!"desktop".equals(taskState.command().kind().wireValue())) throw new IllegalArgumentException("task is not a desktop task");
                var task = new TaskView(taskState);
                if (waitMs > 0 && !TaskStatus.terminal(task.status())) {
                    task = tasks.waitForTerminal(origin, task.id(), Duration.ofMillis(waitMs));
                }
                return desktopResult(tasks, origin, task);
            }
            if (!"screenshot".equals(operation) && !"screenshot_region".equals(operation) && !"screens".equals(operation) && !"windows".equals(operation) && !"launch".equals(operation)
                    && !"click".equals(operation) && !"double_click".equals(operation) && !"right_click".equals(operation)
                    && !"move".equals(operation) && !"drag".equals(operation) && !"key".equals(operation)
                    && !"type".equals(operation) && !"clipboard_read".equals(operation)
                    && !"clipboard_write".equals(operation) && !"focus".equals(operation)) {
                throw new IllegalArgumentException("operation must be screenshot, screenshot_region, screens, windows, launch, click, double_click, right_click, move, drag, key, type, clipboard_read, clipboard_write, focus, or result");
            }
            access.authorizeExecution(origin, args.machineId(), args.projectId());
            var machine = agents.findMachine(args.machineId(), Instant.now()).orElseThrow(() -> new IllegalArgumentException("machine not found"));
            if (!machine.capabilities().contains("desktop")) throw new IllegalArgumentException("machine does not advertise desktop capability");
            var timeout = args.timeoutSeconds() == null ? 30 : args.timeoutSeconds();
            var scope = resolveScope(agents, projects, args.machineId(), args.projectId(), args.worktreeId(),
                    args.scopeMode(), args.scopeRoot(), args.cwd());
            var action = new com.prodigalgal.remoteconnectmcp.protocol.TaskCommand.DesktopAction(operation,
                    args.executable(), args.args(), scope.cwd(), args.text(), args.x(), args.y(), args.key(),
                    args.x2(), args.y2(), args.durationMs(), args.screen(), args.windowTitle());
            var command = new com.prodigalgal.remoteconnectmcp.protocol.TaskCommand("", com.prodigalgal.remoteconnectmcp.protocol.TaskKind.DESKTOP,
                    "desktop", null, scope.cwd(), Map.of(), timeout, action, Instant.now(), null, 0, null);
            var created = tasks.create(new CreateTaskRequest(args.machineId(), command, args.idempotencyKey(),
                    args.projectId(), args.worktreeId(), scope.mode(), scope.root(), args.workspacePolicy(), args.laneMode(),
                    args.sessionId(), args.risk(), Boolean.TRUE.equals(args.elevationRequired()), origin), "mcp", origin);
            if (waitMs > 0) {
                var completed = tasks.waitForTerminal(origin, created.id(), Duration.ofMillis(waitMs));
                return desktopResult(tasks, origin, completed);
            }
            return json(Map.of("task", taskMap(created), "next_action", "call desktop with operation=result and this task_id"));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return error(exception);
        } catch (Exception exception) {
            return error(exception);
        }
    }

    private static McpSchema.CallToolResult desktopResult(TaskService tasks, TaskOrigin origin, TaskView task) {
        if (!TaskStatus.terminal(task.status())) {
            return json(Map.of("task", taskMap(task), "next_action", "retry operation=result later"));
        }
        var page = tasks.readOutput(origin, task.id(), 0, 16 * 1024);
        var output = new LinkedHashMap<String, Object>();
        output.put("text", new String(page.data(), StandardCharsets.UTF_8));
        output.put("cursor", page.cursor());
        output.put("next_cursor", page.nextCursor());
        output.put("more", page.more());
        if (!TaskStatus.COMPLETED.equals(task.status())) {
            return json(Map.of("task", taskMap(task), "output", output,
                    "next_action", "inspect task.error; do not retry automatically"));
        }
        if (task.artifactBytes() <= 0) return json(Map.of("task", taskMap(task), "output", output));
        var artifactMetadata = new LinkedHashMap<String, Object>();
        artifactMetadata.put("sha256", task.artifactSha256());
        artifactMetadata.put("bytes", task.artifactBytes());
        artifactMetadata.put("mime_type", task.artifactMime());
        var inlineImage = isInlineImage(task.artifactMime(), task.artifactBytes());
        artifactMetadata.put("inline", inlineImage);
        var resultPayload = new LinkedHashMap<String, Object>();
        resultPayload.put("task", taskMap(task));
        resultPayload.put("output", output);
        resultPayload.put("artifact", artifactMetadata);
        if (!inlineImage) return json(resultPayload);
        // Only load bytes when the bounded MCP response can actually inline
        // them. Large downloads and non-image artifacts remain metadata-only;
        // the authenticated Console endpoint performs the full read on demand.
        var artifact = tasks.readArtifact(origin, task.id());
        if (artifact.isEmpty()) return json(resultPayload);
        var value = artifact.get();
        artifactMetadata.put("sha256", value.sha256());
        artifactMetadata.put("bytes", value.data().length);
        artifactMetadata.put("mime_type", value.mimeType());
        if (!isInlineImage(value.mimeType(), value.data().length)) {
            artifactMetadata.put("inline", false);
            return json(resultPayload);
        }
        var result = McpSchema.CallToolResult.builder()
                .addTextContent(jsonText(resultPayload))
                .build();
        if (inlineImage) {
            result = McpSchema.CallToolResult.builder()
                    .addTextContent(jsonText(resultPayload))
                    .addContent(McpSchema.ImageContent.builder(java.util.Base64.getEncoder().encodeToString(value.data()), value.mimeType()).build())
                    .build();
        }
        return result;
    }

    private static String jsonText(Object value) {
        try {
            return boundedJsonText(value);
        } catch (IOException exception) {
            return "{\"message\":\"response omitted; use task_read cursor or the Console detail endpoint\"}";
        }
    }

    private static McpSchema.CallToolResult taskWaitCore(TaskService tasks, TaskOrigin origin,
                                                     McpSchema.CallToolRequest request) {
        try {
            var args = args(request, TaskWaitCoreArgs.class);
            var cursor = args.cursor() == null ? 0 : args.cursor();
            var waitMs = args.waitMs() == null ? 0 : args.waitMs();
            if (waitMs < 0 || waitMs > 20000) {
                throw new IllegalArgumentException("wait_ms must be between 0 and 20000");
            }
            var task = tasks.findFor(origin, args.taskId()).orElseThrow(() -> new IllegalArgumentException("task not found"));
            var view = waitMs == 0 ? new TaskView(task) : tasks.waitForChange(origin, args.taskId(), cursor, Duration.ofMillis(waitMs));
            return taskResult(tasks, origin, view, cursor, 16 * 1024);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return error(exception);
        } catch (Exception exception) {
            return error(exception);
        }
    }

    private static McpSchema.CallToolResult taskOutputCore(TaskService tasks, TaskOrigin origin,
                                                       McpSchema.CallToolRequest request) {
        try {
            var args = args(request, TaskOutputCoreArgs.class);
            var cursor = args.cursor() == null ? 0 : args.cursor();
            var limit = args.limit() == null ? 16 * 1024 : args.limit();
            if (limit < 1 || limit > MAX_OUTPUT_PAGE) {
                throw new IllegalArgumentException("limit must be between 1 and " + MAX_OUTPUT_PAGE);
            }
            var task = tasks.findFor(origin, args.taskId()).orElseThrow(() -> new IllegalArgumentException("task not found"));
            return taskResult(tasks, origin, new TaskView(task), cursor, limit);
        } catch (Exception exception) {
            return error(exception);
        }
    }

    private static McpSchema.CallToolResult taskCancelCore(TaskService tasks, ArtifactTransferService transfers,
                                                       TaskOrigin origin, McpSchema.CallToolRequest request) {
        try {
            var args = args(request, TaskCancelCoreArgs.class);
            var canceled = tasks.cancel(origin, args.taskId());
            if (transfers != null && TaskStatus.CANCELED.equals(canceled.status())) {
                transfers.cancelForTask(canceled.id());
            }
            return json(Map.of("task", taskMap(canceled)));
        } catch (Exception exception) {
            return error(exception);
        }
    }

    private static McpSchema.CallToolResult taskResult(TaskService tasks, TaskOrigin origin,
                                                       TaskView view, long cursor, int limit) {
        var page = tasks.readOutput(origin, view.id(), cursor, limit);
        var output = new LinkedHashMap<String, Object>();
        output.put("text", new String(page.data(), StandardCharsets.UTF_8));
        output.put("cursor", page.cursor());
        output.put("next_cursor", page.nextCursor());
        output.put("more", page.more());
        // Browser and desktop tasks can finish with a screenshot or another
        // bounded artifact.  Keep task_read useful for those capabilities as
        // well as command tasks: return metadata for every artifact, and
        // inline only small image bytes (the Center MCP threshold is 512 KiB;
        // the authenticated artifact endpoint has its separate bounded
        // storage contract). Non-image downloads remain
        // available through the authenticated console artifact endpoint
        // without inflating MCP context with binary/base64 data.
        if (view.artifactBytes() <= 0) {
            return json(Map.of("task", taskMap(view), "output", output));
        }
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("sha256", view.artifactSha256());
        metadata.put("bytes", view.artifactBytes());
        metadata.put("mime_type", view.artifactMime());
        var inlineImage = isInlineImage(view.artifactMime(), view.artifactBytes());
        metadata.put("inline", inlineImage);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("task", taskMap(view));
        payload.put("output", output);
        payload.put("artifact", metadata);
        if (!inlineImage) return json(payload);
        var artifact = tasks.readArtifact(origin, view.id());
        if (artifact.isEmpty()) return json(payload);
        var value = artifact.get();
        metadata.put("sha256", value.sha256());
        metadata.put("bytes", value.data().length);
        metadata.put("mime_type", value.mimeType());
        if (!isInlineImage(value.mimeType(), value.data().length)) {
            metadata.put("inline", false);
            return json(payload);
        }
        var text = jsonText(payload);
        return McpSchema.CallToolResult.builder()
                .addTextContent(text)
                .addContent(McpSchema.ImageContent.builder(
                        java.util.Base64.getEncoder().encodeToString(value.data()), value.mimeType()).build())
                .build();
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
        value.put("scope_mode", textOrEmpty(machine.scopeMode()));
        var capabilities = machine.capabilities() == null ? List.<String>of() : machine.capabilities();
        var visibleCapabilities = capabilities.stream().limit(16).toList();
        value.put("capabilities", visibleCapabilities);
        value.put("capabilities_truncated", capabilities.size() > visibleCapabilities.size());
        value.put("online", machine.online());
        return value;
    }

    /**
     * Project discovery deliberately excludes root/repository/worktree paths.
     * Paths can be long and are often private; an explicit project operation
     * or the Console is the detail channel.  Worktree summaries are capped so
     * a project with many historical worktrees cannot flood MCP context.
     */
    private static Map<String, Object> projectSummary(ProjectView project) {
        var value = new LinkedHashMap<String, Object>();
        value.put("id", project.id());
        value.put("machine_id", project.machineId());
        value.put("name", project.name());
        value.put("default_ref", textOrEmpty(project.defaultRef()));
        var worktrees = project.worktrees() == null ? List.<WorktreeView>of() : project.worktrees();
        var summaries = worktrees.stream()
                .limit(MAX_INLINE_WORKTREE_SUMMARIES)
                .map(McpConfiguration::worktreeSummary)
                .toList();
        value.put("worktree_count", worktrees.size());
        value.put("worktrees", summaries);
        value.put("worktrees_truncated", worktrees.size() > summaries.size());
        return value;
    }

    /**
     * Explicit project details are still paged.  A caller must opt in to
     * local paths because they are rarely needed for routing and can be noisy
     * or private.  Worktree metadata is returned in a small page rather than
     * copying the complete project history into one MCP response.
     */
    private static Map<String, Object> projectDetail(ProjectView project, int offset, int limit,
                                                     boolean includePaths) {
        var value = new LinkedHashMap<String, Object>();
        value.put("id", project.id());
        value.put("machine_id", project.machineId());
        value.put("name", project.name());
        value.put("default_ref", textOrEmpty(project.defaultRef()));
        value.put("created_at", project.createdAt());
        value.put("updated_at", project.updatedAt());
        value.put("paths_included", includePaths);
        if (includePaths) {
            value.put("root_path", textOrEmpty(project.rootPath()));
            value.put("repository_path", textOrEmpty(project.repositoryPath()));
        }
        var worktrees = project.worktrees() == null ? List.<WorktreeView>of() : project.worktrees();
        var page = offset >= worktrees.size() ? List.<WorktreeView>of()
                : worktrees.subList(offset, Math.min(worktrees.size(), offset + limit));
        value.put("worktrees", page.stream().map(worktree -> worktreeDetail(worktree, includePaths)).toList());
        value.put("offset", offset);
        value.put("limit", limit);
        value.put("total_worktrees", worktrees.size());
        value.put("has_more", offset + page.size() < worktrees.size());
        value.put("next_action", offset + page.size() < worktrees.size()
                ? "call project detail with offset + limit"
                : "no more worktrees");
        return value;
    }

    private static Map<String, Object> worktreeDetail(WorktreeView worktree, boolean includePath) {
        var value = new LinkedHashMap<String, Object>();
        value.put("id", worktree.id());
        value.put("project_id", worktree.projectId());
        value.put("ref", textOrEmpty(worktree.ref()));
        value.put("operation", textOrEmpty(worktree.operation()));
        value.put("status", textOrEmpty(worktree.status()));
        value.put("task_id", textOrEmpty(worktree.taskId()));
        value.put("created_at", worktree.createdAt());
        value.put("updated_at", worktree.updatedAt());
        if (includePath) value.put("path", textOrEmpty(worktree.path()));
        return value;
    }

    private static Map<String, Object> worktreeSummary(WorktreeView worktree) {
        var value = new LinkedHashMap<String, Object>();
        value.put("id", worktree.id());
        value.put("ref", textOrEmpty(worktree.ref()));
        value.put("status", textOrEmpty(worktree.status()));
        value.put("task_id", textOrEmpty(worktree.taskId()));
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
        value.put("scope_mode", machine.scopeMode());
        value.put("workspace_root", machine.workspaceRoot());
        value.put("capabilities", machine.capabilities());
        // Runtime self-description is a fixed-size, non-secret projection.
        // Keep it on the machine record rather than exposing raw process or
        // environment details to the MCP client.
        var runtime = machine.runtime();
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
                Map.entry("scope_mode", runtime.scopeMode().wireValue()),
                Map.entry("desktop_session_available", runtime.desktopSessionAvailable()),
                Map.entry("browser_session_available", runtime.browserSessionAvailable())));
        value.put("created_at", machine.createdAt());
        value.put("last_seen", machine.lastSeen());
        value.put("online", machine.online());
        return value;
    }

    private static Map<String, Object> taskMap(TaskView task) {
        var value = new LinkedHashMap<String, Object>();
        value.put("id", task.id());
        value.put("machine_id", task.machineId());
        value.put("kind", task.kind());
        value.put("required_capability", task.requiredCapability());
        // Browser adapter requests can contain URLs, selectors, or adapter
        // payloads with credentials.  The Agent already returned the bounded
        // result/manifest; do not echo the original browser command into the
        // model context or the MCP transcript.
        value.put("command", "browser".equals(task.kind())
                ? "[browser adapter request kept on Agent]" : compact(task.command(), 1024));
        value.put("cwd", compact(task.cwd(), 1024));
        value.put("timeout_seconds", task.timeoutSeconds());
        value.put("status", task.status());
        value.put("attempt", task.attempt());
        value.put("exit_code", task.exitCode());
        value.put("error", compact(task.error(), 2048));
        value.put("output_bytes", task.outputBytes());
        value.put("output_truncated", task.outputTruncated());
        value.put("created_at", task.createdAt());
        value.put("dispatched_at", task.dispatchedAt());
        value.put("started_at", task.startedAt());
        value.put("finished_at", task.finishedAt());
        value.put("artifact_bytes", task.artifactBytes());
        value.put("artifact_mime", task.artifactMime());
        value.put("artifact_sha256", task.artifactSha256());
        // Correlation identifiers are fixed-size and opaque.  Returning them
        // lets a caller correlate a task across a reconnect without exposing
        // the original Bearer token or broadcasting output to a whole session.
        value.put("execution_session_id", task.executionSessionId());
        value.put("result_channel", task.resultChannel());
        if (task.scopeMode() != null) {
            var scope = new LinkedHashMap<String, Object>();
            scope.put("mode", task.scopeMode());
            scope.put("project_id", task.projectId());
            scope.put("worktree_id", task.worktreeId());
            scope.put("root", compact(task.scopeRoot(), 1024));
            scope.put("workspace_policy", task.workspacePolicy() == null ? "shared_serial" : task.workspacePolicy().wireValue());
            scope.put("lane_mode", task.laneMode() == null ? "write" : task.laneMode().wireValue());
            scope.put("risk", task.risk());
            scope.put("expires_at", task.contractExpiresAt());
            value.put("execution_scope", scope);
        }
        return value;
    }

    private static String compact(String value, int max) {
        if (value == null || value.length() <= max) {
            return SensitiveValueRedactor.redact(value);
        }
        return SensitiveValueRedactor.redact(value.substring(0, max));
    }

    private static ResolvedScope resolveScope(AgentRegistry agents, ProjectService projects, String machineId,
                                              String projectId, String worktreeId, String rawMode,
                                              String requestedRoot, String requestedCwd) {
        var machine = agents.findMachine(machineId, Instant.now())
                .orElseThrow(() -> new IllegalArgumentException("machine not found"));
        var normalizedProject = projectId == null ? "" : projectId.trim();
        var normalizedWorktree = worktreeId == null ? "" : worktreeId.trim();
        var mode = rawMode == null || rawMode.isBlank() ? null : ScopeMode.fromWireValue(rawMode);
        if (mode == null) {
            mode = !normalizedWorktree.isBlank() ? ScopeMode.WORKTREE
                    : !normalizedProject.isBlank() ? ScopeMode.PROJECT
                    : ScopeMode.fromWireValue(machine.scopeMode());
            if (mode == ScopeMode.UNRESTRICTED) {
                throw new IllegalArgumentException("scope_mode=unrestricted must be explicit");
            }
        }
        if (mode == ScopeMode.PROJECT || mode == ScopeMode.WORKTREE) {
            if (projects == null) throw new IllegalStateException("project service is unavailable");
            if (normalizedProject.isBlank()) throw new IllegalArgumentException("project_id is required for project/worktree scope");
            if (mode == ScopeMode.PROJECT && !normalizedWorktree.isBlank()) {
                throw new IllegalArgumentException("worktree_id requires worktree scope");
            }
            if (mode == ScopeMode.WORKTREE && normalizedWorktree.isBlank()) {
                throw new IllegalArgumentException("worktree_id is required for worktree scope");
            }
            var cwd = projects.resolveCwd(machineId, normalizedProject,
                    normalizedWorktree, requestedCwd);
            var root = projects.resolveScopeRoot(machineId, normalizedProject, normalizedWorktree);
            return new ResolvedScope(cwd, root, mode);
        }
        if (!normalizedProject.isBlank() || !normalizedWorktree.isBlank()) {
            throw new IllegalArgumentException("project_id/worktree_id require project or worktree scope");
        }
        if (mode == ScopeMode.PATH && (requestedRoot == null || requestedRoot.isBlank())) {
            throw new IllegalArgumentException("scope_root is required for path scope");
        }
        var root = requestedRoot == null || requestedRoot.isBlank() ? machine.workspaceRoot() : requestedRoot.trim();
        if (mode == ScopeMode.UNRESTRICTED) root = null;
        return new ResolvedScope(requestedCwd == null || requestedCwd.isBlank() ? null : requestedCwd.trim(), root, mode);
    }

    private static TaskOrigin origin(McpAsyncServerExchange exchange) {
        if (exchange == null || exchange.transportContext() == null) {
            throw new SecurityException("MCP transport principal is missing");
        }
        var value = exchange.transportContext().get("rcm.principal");
        if (!(value instanceof McpPrincipal principal)) {
            throw new SecurityException("MCP transport principal is missing");
        }
        return principal.taskOrigin(exchange.sessionId());
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
        payload.put("next_action", isRetryable(exception) ? "retry with the same task or idempotency key" : "fix the request and retry");
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

    record ProjectCoreArgs(String operation,
                       @JsonProperty("machine_id") String machineId,
                       @JsonProperty("project_id") String projectId,
                       @JsonProperty("worktree_id") String worktreeId,
                       String name,
                       @JsonProperty("root_path") String rootPath,
                       @JsonProperty("repository_path") String repositoryPath,
                       @JsonProperty("default_ref") String defaultRef,
                       String ref,
                       String message,
                       String mode,
                       Integer offset,
                       Integer limit,
                       @JsonProperty("include_paths") Boolean includePaths,
                       @JsonProperty("idempotency_key") String idempotencyKey) {
    }

    record CommandCoreArgs(@JsonProperty("machine_id") String machineId,
                               String command,
                               String cwd,
                               Map<String, String> env,
                               @JsonProperty("timeout_seconds") Integer timeoutSeconds,
                               @JsonProperty("idempotency_key") String idempotencyKey,
                               @JsonProperty("project_id") String projectId,
                               @JsonProperty("worktree_id") String worktreeId,
                               @JsonProperty("scope_mode") String scopeMode,
                               @JsonProperty("scope_root") String scopeRoot,
                               @JsonProperty("workspace_policy") WorkspacePolicyMode workspacePolicy,
                               @JsonProperty("lane_mode") LaneMode laneMode,
                               @JsonProperty("session_id") String sessionId,
                               String risk,
                               @JsonProperty("elevation_required") Boolean elevationRequired) {
    }

    record DesktopCoreArgs(String operation,
                               @JsonProperty("machine_id") String machineId,
                               @JsonProperty("task_id") String taskId,
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
                               @JsonProperty("idempotency_key") String idempotencyKey,
                               @JsonProperty("project_id") String projectId,
                               @JsonProperty("worktree_id") String worktreeId,
                               @JsonProperty("scope_mode") String scopeMode,
                               @JsonProperty("scope_root") String scopeRoot,
                               @JsonProperty("workspace_policy") WorkspacePolicyMode workspacePolicy,
                               @JsonProperty("lane_mode") LaneMode laneMode,
                               @JsonProperty("session_id") String sessionId,
                               String risk,
                               @JsonProperty("elevation_required") Boolean elevationRequired) {
        DesktopCoreArgs {
            args = args == null ? List.of() : List.copyOf(args);
        }
    }

    record BrowserCoreArgs(@JsonProperty("machine_id") String machineId,
                               String command,
                               String cwd,
                               @JsonProperty("timeout_seconds") Integer timeoutSeconds,
                               @JsonProperty("wait_ms") Integer waitMs,
                               @JsonProperty("idempotency_key") String idempotencyKey,
                               @JsonProperty("project_id") String projectId,
                               @JsonProperty("worktree_id") String worktreeId,
                               @JsonProperty("scope_mode") String scopeMode,
                               @JsonProperty("scope_root") String scopeRoot,
                               @JsonProperty("workspace_policy") WorkspacePolicyMode workspacePolicy,
                               @JsonProperty("lane_mode") LaneMode laneMode,
                               @JsonProperty("session_id") String sessionId,
                               String risk,
                               @JsonProperty("elevation_required") Boolean elevationRequired) {
    }

    record TaskWaitCoreArgs(@JsonProperty("task_id") String taskId,
                                Long cursor,
                                @JsonProperty("wait_ms") Integer waitMs) {
    }

    record TaskOutputCoreArgs(@JsonProperty("task_id") String taskId, Long cursor, Integer limit) {
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

    record ArtifactPutCoreArgs(@JsonProperty("machine_id") String machineId,
                           ArtifactFileCore file,
                           @JsonProperty("destination_path") String destinationPath,
                           @JsonProperty("file_name") String fileName,
                           @JsonProperty("mime_type") String mimeType,
                           @JsonProperty("expected_bytes") Long expectedBytes,
                           @JsonProperty("expected_sha256") String expectedSha256,
                           Boolean overwrite,
                           String cwd,
                           @JsonProperty("idempotency_key") String idempotencyKey,
                           @JsonProperty("project_id") String projectId,
                           @JsonProperty("worktree_id") String worktreeId,
                           @JsonProperty("scope_mode") String scopeMode,
                           @JsonProperty("scope_root") String scopeRoot,
                           @JsonProperty("workspace_policy") WorkspacePolicyMode workspacePolicy,
                           @JsonProperty("lane_mode") LaneMode laneMode,
                           @JsonProperty("session_id") String sessionId,
                           String risk,
                           @JsonProperty("elevation_required") Boolean elevationRequired) {
    }

    record ArtifactGetCoreArgs(@JsonProperty("machine_id") String machineId,
                           @JsonProperty("source_path") String sourcePath,
                           @JsonProperty("file_name") String fileName,
                           @JsonProperty("mime_type") String mimeType,
                           String cwd,
                           @JsonProperty("idempotency_key") String idempotencyKey,
                           @JsonProperty("project_id") String projectId,
                           @JsonProperty("worktree_id") String worktreeId,
                           @JsonProperty("scope_mode") String scopeMode,
                           @JsonProperty("scope_root") String scopeRoot,
                           @JsonProperty("workspace_policy") WorkspacePolicyMode workspacePolicy,
                           @JsonProperty("lane_mode") LaneMode laneMode,
                           @JsonProperty("session_id") String sessionId,
                           String risk,
                           @JsonProperty("elevation_required") Boolean elevationRequired) {
    }

    record ArtifactReadCoreArgs(@JsonProperty("artifact_id") String artifactId,
                            @JsonProperty("transfer_id") String transferId) {
    }

    private record ResolvedScope(String cwd, String root, ScopeMode mode) {
    }

    private static String firstNonBlank(String primary, String fallback) {
        return primary != null && !primary.isBlank() ? primary.trim()
                : fallback != null && !fallback.isBlank() ? fallback.trim() : null;
    }
}
