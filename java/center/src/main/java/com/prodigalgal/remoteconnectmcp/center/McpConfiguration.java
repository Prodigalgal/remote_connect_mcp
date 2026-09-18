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
@RegisterReflectionForBinding({McpConfiguration.MachinesArgs.class, McpConfiguration.MachineInfoArgs.class,
        McpConfiguration.ProjectArgs.class,
        McpConfiguration.CommandArgs.class, McpConfiguration.DesktopArgs.class,
        McpConfiguration.BrowserArgs.class, McpConfiguration.TaskWaitArgs.class,
        McpConfiguration.TaskOutputArgs.class, McpConfiguration.TaskCancelArgs.class,
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
        McpConfiguration.ArtifactFile.class, McpConfiguration.ArtifactPutArgs.class,
        McpConfiguration.ArtifactGetArgs.class, McpConfiguration.ArtifactReadArgs.class,
        TaskService.ArtifactGcResult.class})
public class McpConfiguration {
    /** Stable Apps SDK resource URI; changing it would require reconnecting every client. */
    static final String ARTIFACT_VIEWER_URI = "ui://remote-connect-mcp/artifact-viewer-v1.html";
    // MCP inventory responses are intentionally smaller than the Console
    // pages.  The model normally only needs an identifier and a few routing
    // hints; detailed runtime data is an explicit machine_info follow-up.
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
                .instructions("Use machines_list first and always pass an explicit machine_id and scope. Inventory lists are compact summaries: use machine_info or an explicit project operation for details instead of asking for everything at once. Prefer project/worktree or workspace/path; unrestricted must be explicit. Tasks are asynchronous and bounded; report task_id for long work and read output with cursors. Use artifact_put for a ChatGPT file to Agent and artifact_get for an Agent file to ChatGPT; file tools return compact handles and never put binary data in MCP text. Call artifact_read only when the file handle or short-lived download URL is needed.")
                .strictToolNameValidation(true)
                .validateToolInputs(true)
                .requestTimeout(Duration.ofSeconds(30))
                .resources(artifactViewerResource())
                .tools(toolSpecs(agents, tasks, projects, access, transfers, mcpVirtualThreadExecutor))
                .build();
        return server;
    }

    /**
     * Minimal Apps SDK component for file objects.  It is a resource, not a
     * tool response, so the normal MCP transcript only receives the compact
     * artifact handle while a capable host can render/download the file on
     * demand.  The component also degrades to a plain link in older hosts.
     */
    private static McpServerFeatures.AsyncResourceSpecification artifactViewerResource() {
        var resource = McpSchema.Resource.builder(ARTIFACT_VIEWER_URI, "Remote Connect Artifact Viewer")
                .description("Render an artifact file object returned by artifact_read")
                .mimeType("text/html;profile=mcp-app")
                .build();
        return new McpServerFeatures.AsyncResourceSpecification(resource, (exchange, request) ->
                Mono.fromSupplier(() -> McpSchema.ReadResourceResult.builder(List.of(
                        McpSchema.TextResourceContents.builder(ARTIFACT_VIEWER_URI, ARTIFACT_VIEWER_HTML)
                                .mimeType("text/html;profile=mcp-app").build())).build()));
    }

    private static final String ARTIFACT_VIEWER_HTML = """
            <!doctype html>
            <html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Remote Connect Artifact</title>
            <style>body{font:14px system-ui,sans-serif;margin:16px;color:#172033;background:#fff}main{display:grid;gap:10px}header{font-weight:600;word-break:break-word}small{color:#65718a}img,video,iframe{max-width:100%;max-height:70vh;border:1px solid #d9dfeb;border-radius:6px}pre{white-space:pre-wrap;max-height:60vh;overflow:auto;background:#f5f7fb;padding:10px;border-radius:6px}a{color:#1769e0}button{padding:6px 10px;border:1px solid #b7c2d6;border-radius:5px;background:#f5f7fb;cursor:pointer}</style></head>
            <body><main><header id="name">Artifact</header><small id="meta"></small><section id="preview"></section><a id="download" rel="noreferrer" download>Download</a><button id="refresh" hidden>Refresh</button></main>
            <script>
            (function(){
              const output=()=>window.openai&&window.openai.toolOutput?window.openai.toolOutput:null;
              const pick=()=>{const o=output()||{}; return o.file||o.artifact||o;};
              const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
              const render=()=>{const f=pick(), url=f.preview_url||f.download_url||f.url||'', downloadUrl=f.download_url||f.url||url, name=f.file_name||f.name||'artifact', mime=(f.mime_type||f.mime||'application/octet-stream').toLowerCase();
                document.querySelector('#name').textContent=name; document.querySelector('#meta').textContent=[mime,f.bytes?Number(f.bytes).toLocaleString()+' bytes':'',f.sha256?'sha256 '+f.sha256:''].filter(Boolean).join(' · ');
                const p=document.querySelector('#preview'); p.replaceChildren(); const safe=esc(url);
                if(!url){p.innerHTML='<small>Artifact is not ready yet. Call artifact_read again after task_wait reports delivered.</small>';return;}
                if(mime.startsWith('image/')) p.innerHTML='<img alt="'+esc(name)+'" src="'+safe+'">';
                else if(mime==='application/pdf') p.innerHTML='<iframe title="'+esc(name)+'" src="'+safe+'" style="width:100%;height:70vh"></iframe>';
                else if(mime.startsWith('video/')) p.innerHTML='<video controls src="'+safe+'"></video>';
                else if(mime.startsWith('audio/')) p.innerHTML='<audio controls src="'+safe+'"></audio>';
                else if(mime.startsWith('text/')||mime.includes('json')||mime.includes('xml')){fetch(url,{credentials:'omit'}).then(r=>r.text()).then(t=>{p.innerHTML='<pre>'+esc(t.slice(0,262144))+'</pre>'}).catch(()=>{p.innerHTML='<small>Preview unavailable; use download.</small>'})}
                else p.innerHTML='<small>This file type is download-only.</small>';
                 const a=document.querySelector('#download');a.href=downloadUrl;a.download=name;a.textContent='Download '+name;
              }; render(); if(window.openai&&window.openai.onToolOutput)window.openai.onToolOutput(render);
            })();</script></body></html>
            """;

    private static List<McpServerFeatures.AsyncToolSpecification> toolSpecs(AgentRegistry agents, TaskService tasks,
                                                                             ProjectService projects,
                                                                             McpAccessService access,
                                                                             ArtifactTransferService transfers,
                                                                             ExecutorService mcpVirtualThreadExecutor) {
        var scheduler = Schedulers.fromExecutor(mcpVirtualThreadExecutor);
        return List.of(
                tool("machines_list", "List a compact, bounded page of registered machine summaries; call machine_info for runtime details.", schema(
                        Map.of("offset", integer("zero-based offset"), "limit", integer("1-25 page size")), List.of()),
                        (exchange, request) -> { requireScope(exchange, "mcp:read"); return machinesList(agents, access, origin(exchange), request); }, scheduler),
                tool("machine_info", "Show one machine's detailed platform, capabilities, scope, runtime descriptor and heartbeat.", schema(
                        Map.of("machine_id", string("machine ID from machines_list")), List.of("machine_id")),
                        (exchange, request) -> { requireScope(exchange, "mcp:read"); return machineInfo(agents, access, origin(exchange), request); }, scheduler),
                tool("project", "List compact project/worktree summaries, fetch one bounded project detail page, register/remove projects, or queue one isolated Git/worktree operation. Listing omits local paths; detail paths require explicit include_paths=true.", schema(
                        Map.ofEntries(
                            Map.entry("operation", string("list, detail, register, remove, worktree_create, worktree_remove, git_status, git_diff, git_log, git_commit, git_merge, or git_merge_abort")),
                                Map.entry("machine_id", string("target machine ID")),
                                Map.entry("project_id", string("project ID from a previous response")),
                                Map.entry("worktree_id", string("worktree ID for remove")),
                                Map.entry("name", string("project display name for register")),
                                Map.entry("root_path", string("absolute project root on the Agent")),
                                Map.entry("repository_path", string("optional repository path inside root")),
                                Map.entry("default_ref", string("default Git ref")),
                                Map.entry("ref", string("Git ref for a worktree")),
                                Map.entry("message", string("single-line commit message for git_commit")),
                                Map.entry("mode", string("git_diff mode: stat or patch")),
                                Map.entry("offset", integer("project list offset")),
                                Map.entry("limit", integer("project list page size, at most 25")),
                                Map.entry("include_paths", Map.of("type", "boolean", "description", "project detail only: explicitly include local root/repository/worktree paths")),
                                Map.entry("idempotency_key", string("stable retry key"))),
                        List.of("operation")), (exchange, request) -> { requireScope(exchange, "mcp:project"); return project(projects, access, origin(exchange), request); }, scheduler),
                tool("desktop", "Queue a bounded screenshot, screen/window listing, launch, pointer, drag, key, text, clipboard, or window-focus action on an explicitly desktop-capable user-session Agent.", schema(
                        Map.ofEntries(
                                Map.entry("operation", string("screenshot, screenshot_region, screens, windows, launch, click, double_click, right_click, move, drag, key, type, clipboard_read, clipboard_write, focus, or result")),
                                Map.entry("machine_id", string("command-agent machine ID with desktop capability")),
                                Map.entry("task_id", string("existing desktop task for result")),
                                Map.entry("executable", string("literal application for launch")),
                                Map.entry("args", objectArray("literal launch arguments")),
                                Map.entry("cwd", string("optional working directory")),
                                Map.entry("text", string("text for type")),
                                Map.entry("x", integer("screen x or region left")),
                                Map.entry("y", integer("screen y or region top")),
                                Map.entry("x2", integer("screen x endpoint or region right")),
                                Map.entry("y2", integer("screen y endpoint or region bottom")),
                                Map.entry("duration_ms", integer("drag duration, 0-10000")),
                                Map.entry("screen", integer("monitor index for screenshot, 0-32")),
                                Map.entry("window_title", string("partial window title for focus")),
                                Map.entry("key", string("key name for key operation")),
                                Map.entry("timeout_seconds", integer("0 means default")),
                                Map.entry("wait_ms", integer("0-15000")),
                                Map.entry("idempotency_key", string("stable retry key")),
                                Map.entry("project_id", string("registered project ID for project/worktree scope")),
                                Map.entry("worktree_id", string("registered worktree ID for worktree scope")),
                                Map.entry("scope_mode", string("project, worktree, path, workspace, or explicit unrestricted")),
                                 Map.entry("scope_root", string("absolute root for path/workspace scope")),
                                 Map.entry("workspace_policy", string("isolated, shared_serial, or explicit host")),
                                 Map.entry("lane_mode", string("read, write, or exclusive scheduling lane")),
                                 Map.entry("session_id", string("optional stable user/session identifier")),
                                Map.entry("risk", string("low, high, or critical")),
                                Map.entry("elevation_required", Map.of("type", "boolean", "description", "explicitly request elevation"))),
                        List.of("operation")), (exchange, request) -> { requireScope(exchange, "mcp:execute"); return desktop(agents, tasks, projects, access, origin(exchange), request); }, scheduler),
                tool("browser", "Queue one bounded browser adapter request on an explicitly browser-capable Agent.", schema(
                        Map.ofEntries(
                                Map.entry("machine_id", string("command-agent machine ID with browser capability")),
                                Map.entry("command", string("adapter request; URL/selector data stays on the Agent")),
                                Map.entry("cwd", string("optional adapter working directory")),
                                Map.entry("timeout_seconds", integer("0 means default")),
                                Map.entry("wait_ms", integer("0-15000")),
                                Map.entry("idempotency_key", string("stable retry key")),
                                Map.entry("project_id", string("registered project ID for project/worktree scope")),
                                Map.entry("worktree_id", string("registered worktree ID for worktree scope")),
                                Map.entry("scope_mode", string("project, worktree, path, workspace, or explicit unrestricted")),
                                 Map.entry("scope_root", string("absolute root for path/workspace scope")),
                                 Map.entry("workspace_policy", string("isolated, shared_serial, or explicit host")),
                                 Map.entry("lane_mode", string("read, write, or exclusive scheduling lane")),
                                 Map.entry("session_id", string("optional stable user/session identifier")),
                                Map.entry("risk", string("low, high, or critical")),
                                Map.entry("elevation_required", Map.of("type", "boolean", "description", "explicitly request elevation"))),
                        List.of("machine_id", "command")), (exchange, request) -> { requireScope(exchange, "mcp:execute"); return browser(agents, tasks, projects, access, origin(exchange), request); }, scheduler),
                tool("command_start", "Queue a shell command and return immediately with a durable task ID.", schema(
                        Map.ofEntries(
                                Map.entry("machine_id", string("target machine ID")),
                                Map.entry("command", string("shell command")),
                                Map.entry("cwd", string("optional working directory")),
                                Map.entry("env", object("optional environment map")),
                                Map.entry("timeout_seconds", integer("0 means unlimited")),
                                Map.entry("idempotency_key", string("stable retry key")),
                                Map.entry("project_id", string("registered project ID for project/worktree scope")),
                                Map.entry("worktree_id", string("registered worktree ID for worktree scope")),
                                Map.entry("scope_mode", string("project, worktree, path, workspace, or explicit unrestricted")),
                                 Map.entry("scope_root", string("absolute root for path/workspace scope")),
                                 Map.entry("workspace_policy", string("isolated, shared_serial, or explicit host")),
                                 Map.entry("lane_mode", string("read, write, or exclusive scheduling lane")),
                                 Map.entry("session_id", string("optional stable user/session identifier")),
                                Map.entry("risk", string("low, high, or critical")),
                                Map.entry("elevation_required", Map.of("type", "boolean", "description", "explicitly request elevation"))),
                        List.of("machine_id", "command")),
                        (exchange, request) -> { requireScope(exchange, "mcp:execute"); return commandStart(agents, tasks, projects, access, origin(exchange), request); }, scheduler),
                tool("task_wait", "Read task state and one bounded output page; optionally wait briefly for a change.", schema(
                        Map.of("task_id", string("task ID"), "cursor", integer("known output cursor"), "wait_ms", integer("0-20000")), List.of("task_id")),
                        (exchange, request) -> { requireScope(exchange, "mcp:read"); return taskWait(tasks, origin(exchange), request); }, scheduler),
                tool("task_output", "Read one bounded output page from a byte cursor.", schema(
                        Map.of("task_id", string("task ID"), "cursor", integer("known output cursor"), "limit", integer("maximum 65536 bytes")), List.of("task_id")),
                        (exchange, request) -> { requireScope(exchange, "mcp:read"); return taskOutput(tasks, origin(exchange), request); }, scheduler),
                tool("task_cancel", "Cancel a queued or running task.", schema(
                        Map.of("task_id", string("task ID")), List.of("task_id")),
                        (exchange, request) -> { requireScope(exchange, "mcp:execute"); return taskCancel(tasks, transfers, origin(exchange), request); }, scheduler),
                tool("artifact_put", "Transfer one ChatGPT file to a target Agent path. Returns only a task/transfer handle; bytes never enter MCP text.",
                        schema(Map.ofEntries(
                                Map.entry("machine_id", string("target machine ID")),
                                Map.entry("file", fileObjectSchema()),
                                Map.entry("destination_path", string("complete target file path on the Agent; relative paths use the explicit scope root")),
                                Map.entry("file_name", string("optional display name; never appended to destination_path")),
                                Map.entry("mime_type", string("optional MIME type")),
                                Map.entry("expected_bytes", integer("optional file size")),
                                Map.entry("expected_sha256", string("optional SHA-256")),
                                Map.entry("overwrite", Map.of("type", "boolean", "description", "replace an existing file")),
                                Map.entry("cwd", string("optional working directory")),
                                Map.entry("idempotency_key", string("stable retry key")),
                                Map.entry("project_id", string("registered project ID")),
                                Map.entry("worktree_id", string("registered worktree ID")),
                                Map.entry("scope_mode", string("project, worktree, path, workspace, or explicit unrestricted")),
                                 Map.entry("scope_root", string("absolute root for path/workspace scope")),
                                 Map.entry("workspace_policy", string("isolated, shared_serial, or explicit host")),
                                 Map.entry("lane_mode", string("read, write, or exclusive scheduling lane")),
                                 Map.entry("session_id", string("optional stable user/session identifier")),
                                Map.entry("risk", string("low, high, or critical")),
                                Map.entry("elevation_required", Map.of("type", "boolean", "description", "explicitly request elevation"))),
                                List.of("machine_id", "file", "destination_path")),
                        Map.of("openai/fileParams", List.of("file")),
                        (exchange, request) -> { requireScope(exchange, "mcp:execute"); return artifactPut(agents, projects, access, transfers, origin(exchange), request); }, scheduler),
                tool("artifact_get", "Transfer one Agent file back to ChatGPT. Returns a compact file handle and a task ID; call artifact_read after task completion.",
                        schema(Map.ofEntries(
                                Map.entry("machine_id", string("source machine ID")),
                                Map.entry("source_path", string("complete source file path on the Agent")),
                                Map.entry("file_name", string("optional download display name; defaults to the source path leaf")),
                                Map.entry("mime_type", string("optional MIME type")),
                                Map.entry("cwd", string("optional working directory")),
                                Map.entry("idempotency_key", string("stable retry key")),
                                Map.entry("project_id", string("registered project ID")),
                                Map.entry("worktree_id", string("registered worktree ID")),
                                Map.entry("scope_mode", string("project, worktree, path, workspace, or explicit unrestricted")),
                                 Map.entry("scope_root", string("absolute root for path/workspace scope")),
                                 Map.entry("workspace_policy", string("isolated, shared_serial, or explicit host")),
                                 Map.entry("lane_mode", string("read, write, or exclusive scheduling lane")),
                                 Map.entry("session_id", string("optional stable user/session identifier")),
                                Map.entry("risk", string("low, high, or critical")),
                                Map.entry("elevation_required", Map.of("type", "boolean", "description", "explicitly request elevation"))),
                                List.of("machine_id", "source_path")),
                        (exchange, request) -> { requireScope(exchange, "mcp:execute"); return artifactGet(agents, projects, access, transfers, origin(exchange), request); }, scheduler),
                tool("artifact_read", "Read compact artifact metadata and a short-lived downloadable file object. It never inlines binary content.",
                        schema(Map.of("artifact_id", string("artifact ID returned by artifact_get or artifact_put"),
                                "transfer_id", string("optional transfer ID")), List.of()),
                        Map.of("openai/outputTemplate", ARTIFACT_VIEWER_URI,
                                "ui/resourceUri", ARTIFACT_VIEWER_URI),
                        (exchange, request) -> { requireScope(exchange, "mcp:read"); return artifactRead(transfers, origin(exchange), request); }, scheduler));
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
                .readOnlyHint(name.equals("machines_list") || name.equals("machine_info") || name.equals("task_wait")
                        || name.equals("task_output") || name.equals("artifact_read"))
                .destructiveHint(name.equals("command_start") || name.equals("task_cancel") || name.equals("project")
                        || name.equals("artifact_put") || name.equals("artifact_get"))
                .openWorldHint(name.equals("command_start") || name.equals("project")
                        || name.equals("artifact_put") || name.equals("artifact_get"))
                .build();
        var toolBuilder = McpSchema.Tool.builder(name)
                .description(description)
                .inputSchema(schema)
                .annotations(annotations)
                .meta(meta);
        if (name.equals("artifact_put") || name.equals("artifact_get") || name.equals("artifact_read")) {
            toolBuilder.outputSchema(artifactOutputSchema());
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

    private static Map<String, Object> fileObjectSchema() {
        return Map.of("type", "object", "description", "ChatGPT file object supplied by the host",
                "properties", Map.of(
                        "download_url", string("HTTPS URL the Center downloads once"),
                        "file_id", string("host file identifier"),
                        "file_name", string("original file name"),
                        "mime_type", string("MIME type"),
                        "bytes", integer("optional byte size"),
                        "sha256", string("optional SHA-256")),
                "required", List.of("download_url", "file_id"), "additionalProperties", false);
    }

    /**
     * Keep the file result machine-readable without placing bytes in the
     * model transcript.  The optional file object is deliberately identical
     * to the OpenAI file-bridge shape so a Host/Widget can render it directly.
     */
    private static Map<String, Object> artifactOutputSchema() {
        var executionScope = objectSchema(Map.ofEntries(
                Map.entry("mode", string("scope mode")),
                Map.entry("project_id", nullable("string", "project identifier")),
                Map.entry("worktree_id", nullable("string", "worktree identifier")),
                 Map.entry("root", nullable("string", "bounded scope root")),
                 Map.entry("workspace_policy", nullable("string", "workspace coordination policy")),
                 Map.entry("lane_mode", nullable("string", "execution lane mode")),
                 Map.entry("risk", nullable("string", "risk level")),
                Map.entry("expires_at", nullable("string", "contract expiry"))));
        var task = objectSchema(Map.ofEntries(
                Map.entry("id", string("task identifier")),
                Map.entry("machine_id", string("machine identifier")),
                Map.entry("kind", string("task kind")),
                Map.entry("required_capability", string("required capability")),
                Map.entry("command", string("bounded command summary")),
                Map.entry("cwd", string("bounded working directory")),
                Map.entry("timeout_seconds", integer("task timeout")),
                Map.entry("status", string("task status")),
                Map.entry("attempt", integer("dispatch attempt")),
                Map.entry("exit_code", nullable("integer", "process exit code")),
                Map.entry("error", nullable("string", "bounded error")),
                Map.entry("output_bytes", integer("output bytes")),
                Map.entry("output_truncated", Map.of("type", "boolean")),
                Map.entry("created_at", nullable("string", "creation time")),
                Map.entry("dispatched_at", nullable("string", "dispatch time")),
                Map.entry("started_at", nullable("string", "start time")),
                Map.entry("finished_at", nullable("string", "finish time")),
                Map.entry("artifact_bytes", integer("artifact bytes")),
                Map.entry("artifact_mime", nullable("string", "artifact MIME")),
                Map.entry("artifact_sha256", nullable("string", "artifact SHA-256")),
                Map.entry("execution_session_id", string("execution session")),
                Map.entry("result_channel", string("task result channel")),
                Map.entry("execution_scope", executionScope)));
        var transfer = objectSchema(Map.ofEntries(
                Map.entry("transfer_id", string("transfer identifier")),
                Map.entry("artifact_id", string("artifact identifier")),
                Map.entry("direction", string("transfer direction")),
                Map.entry("task_id", nullable("string", "task identifier")),
                 Map.entry("status", string("transfer status")),
                 Map.entry("bytes", integer("transferred bytes")),
                 Map.entry("bytes_transferred", integer("confirmed transfer progress")),
                Map.entry("sha256", nullable("string", "transfer SHA-256")),
                Map.entry("file_name", string("file name")),
                Map.entry("mime_type", nullable("string", "MIME type")),
                 Map.entry("download_url", nullable("string", "short-lived download URL")),
                 Map.entry("error", nullable("string", "bounded transfer error"))));
        var file = Map.of("type", "object", "properties", Map.of(
                         "download_url", string("short-lived artifact URL"),
                         "preview_url", string("short-lived inline preview URL"),
                         "file_id", string("artifact identifier"),
                         "mime_type", string("MIME type"),
                         "file_name", string("file name"),
                         "bytes", integer("artifact size"),
                         "sha256", string("artifact SHA-256")),
                 "required", List.of("download_url", "file_id"), "additionalProperties", false);
        return Map.of("type", "object", "properties", Map.ofEntries(
                        Map.entry("task", task),
                        Map.entry("transfer", transfer),
                        Map.entry("next_action", string("next MCP action")),
                        Map.entry("artifact_id", string("artifact identifier")),
                        Map.entry("transfer_id", string("transfer identifier")),
                        Map.entry("status", string("artifact status")),
                        Map.entry("bytes", integer("artifact size")),
                        Map.entry("sha256", string("artifact SHA-256")),
                        Map.entry("mime_type", string("MIME type")),
                        Map.entry("file_name", string("file name")),
                        Map.entry("file", file)),
                "additionalProperties", false);
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties) {
        return Map.of("type", "object", "properties", properties, "required", List.of(), "additionalProperties", false);
    }

    private static Map<String, Object> nullable(String type, String description) {
        return Map.of("type", List.of(type, "null"), "description", description);
    }

    private static McpSchema.CallToolResult artifactPut(AgentRegistry agents, ProjectService projects,
                                                        McpAccessService access, ArtifactTransferService transfers,
                                                        TaskOrigin origin, McpSchema.CallToolRequest request) {
        try {
            var args = args(request, ArtifactPutArgs.class);
            if (args.file() == null) throw new IllegalArgumentException("file is required");
            access.authorizeExecution(origin, args.machineId(), args.projectId());
            var scope = resolveScope(agents, projects, args.machineId(), args.projectId(), args.worktreeId(),
                    args.scopeMode(), args.scopeRoot(), args.cwd());
            var command = new TaskCommand("", TaskKind.FILE_TRANSFER, "file_transfer", null, scope.cwd(), Map.of(), 0, null, Instant.now());
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
                    "next_action", "call task_wait, then artifact_read with the returned artifact_id"));
        } catch (Exception exception) {
            return error(exception);
        }
    }

    private static McpSchema.CallToolResult artifactGet(AgentRegistry agents, ProjectService projects,
                                                        McpAccessService access, ArtifactTransferService transfers,
                                                        TaskOrigin origin, McpSchema.CallToolRequest request) {
        try {
            var args = args(request, ArtifactGetArgs.class);
            access.authorizeExecution(origin, args.machineId(), args.projectId());
            var scope = resolveScope(agents, projects, args.machineId(), args.projectId(), args.worktreeId(),
                    args.scopeMode(), args.scopeRoot(), args.cwd());
            var command = new TaskCommand("", TaskKind.FILE_TRANSFER, "file_transfer", null, scope.cwd(), Map.of(), 0, null, Instant.now());
            var create = new CreateTaskRequest(args.machineId(), command, args.idempotencyKey(), args.projectId(), args.worktreeId(),
                    scope.mode(), scope.root(), args.workspacePolicy(), args.laneMode(), args.sessionId(), args.risk(),
                    Boolean.TRUE.equals(args.elevationRequired()), origin);
            var result = transfers.createAgentToWeb(origin, create, args.sourcePath(), args.fileName(), args.mimeType());
            return structuredJson(Map.of("task", taskMap(result.task()), "transfer", transferMap(result.transfer()),
                    "next_action", "call task_wait until completed, then artifact_read with the returned artifact_id"));
        } catch (Exception exception) {
            return error(exception);
        }
    }

    private static McpSchema.CallToolResult artifactRead(ArtifactTransferService transfers, TaskOrigin origin,
                                                         McpSchema.CallToolRequest request) {
        try {
            var args = args(request, ArtifactReadArgs.class);
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

    private static McpSchema.CallToolResult machinesList(AgentRegistry agents, McpAccessService access,
                                                         TaskOrigin origin, McpSchema.CallToolRequest request) {
        try {
            var args = args(request, MachinesArgs.class);
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
                            ? "call machines_list with offset + limit" : "no more machines"));
        } catch (Exception exception) {
            return error(exception);
        }
    }

    private static McpSchema.CallToolResult machineInfo(AgentRegistry agents, McpAccessService access,
                                                        TaskOrigin origin, McpSchema.CallToolRequest request) {
        try {
            var args = args(request, MachineInfoArgs.class);
            access.authorizeMachine(origin, args.machineId(), "read");
            var machine = agents.findMachine(args.machineId(), Instant.now()).orElseThrow(() -> new IllegalArgumentException("machine not found"));
            return json(machineMap(machine));
        } catch (Exception exception) {
            return error(exception);
        }
    }

    private static McpSchema.CallToolResult project(ProjectService projects, McpAccessService access, TaskOrigin origin,
                                                    McpSchema.CallToolRequest request) {
        try {
            var args = args(request, ProjectArgs.class);
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
                    if (!origin.isShared()) {
                        // The principal that explicitly registered a project
                        // becomes its first admin member.  Shared legacy
                        // identity remains compatibility-only and does not
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
                    yield json(Map.of("worktree", worktreeSummary(value), "next_action", "use task_wait with the returned task_id, then submit project-scoped tasks"));
                }
                case "worktree_remove" -> {
                    var project = projects.find(args.projectId());
                    access.authorizeMachine(origin, project.machineId(), "execute");
                    access.authorizeProject(origin, project.id(), "write");
                    var value = projects.removeWorktree(args.projectId(), args.worktreeId(), args.idempotencyKey(), origin);
                    yield json(Map.of("worktree", worktreeSummary(value), "next_action", "use task_wait with the returned task_id"));
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
                            "next_action", "use task_wait or task_output with the returned task_id"));
                }
                default -> throw new IllegalArgumentException("operation must be list, detail, register, remove, worktree_create, worktree_remove, or git_* operation");
            };
        } catch (Exception exception) {
            return error(exception);
        }
    }

    private static McpSchema.CallToolResult commandStart(AgentRegistry agents, TaskService tasks,
                                                         ProjectService projects, McpAccessService access,
                                                         TaskOrigin origin,
                                                         McpSchema.CallToolRequest request) {
        try {
            var args = args(request, CommandArgs.class);
            access.authorizeExecution(origin, args.machineId(), args.projectId());
            var timeout = args.timeoutSeconds() == null ? 0 : args.timeoutSeconds();
            var scope = resolveScope(agents, projects, args.machineId(), args.projectId(), args.worktreeId(),
                    args.scopeMode(), args.scopeRoot(), args.cwd());
            var command = new com.prodigalgal.remoteconnectmcp.protocol.TaskCommand("", com.prodigalgal.remoteconnectmcp.protocol.TaskKind.COMMAND,
                    null, args.command(), scope.cwd(), args.env(), timeout, null, Instant.now());
            var task = tasks.create(new CreateTaskRequest(args.machineId(), command, args.idempotencyKey(),
                    args.projectId(), args.worktreeId(), scope.mode(), scope.root(), args.workspacePolicy(), args.laneMode(),
                    args.sessionId(), args.risk(), Boolean.TRUE.equals(args.elevationRequired()), origin), "mcp", origin);
            return json(Map.of("task", taskMap(task), "next_action", "use task_wait or task_output with this task_id"));
        } catch (Exception exception) {
            return error(exception);
        }
    }

    private static McpSchema.CallToolResult browser(AgentRegistry agents, TaskService tasks,
                                                    ProjectService projects, McpAccessService access,
                                                    TaskOrigin origin,
                                                    McpSchema.CallToolRequest request) {
        try {
            var args = args(request, BrowserArgs.class);
            access.authorizeExecution(origin, args.machineId(), args.projectId());
            var machine = agents.findMachine(args.machineId(), Instant.now()).orElseThrow(() -> new IllegalArgumentException("machine not found"));
            if (!machine.capabilities().contains("browser")) throw new IllegalArgumentException("machine does not advertise browser capability");
            var timeout = args.timeoutSeconds() == null ? 300 : args.timeoutSeconds();
            var scope = resolveScope(agents, projects, args.machineId(), args.projectId(), args.worktreeId(),
                    args.scopeMode(), args.scopeRoot(), args.cwd());
            var command = new com.prodigalgal.remoteconnectmcp.protocol.TaskCommand("", com.prodigalgal.remoteconnectmcp.protocol.TaskKind.BROWSER,
                    "browser", args.command(), scope.cwd(), Map.of(), timeout, null, Instant.now());
            var created = tasks.create(new CreateTaskRequest(args.machineId(), command, args.idempotencyKey(),
                    args.projectId(), args.worktreeId(), scope.mode(), scope.root(), args.workspacePolicy(), args.laneMode(),
                    args.sessionId(), args.risk(), Boolean.TRUE.equals(args.elevationRequired()), origin), "mcp", origin);
            var waitMs = args.waitMs() == null ? 0 : args.waitMs();
            if (waitMs < 0 || waitMs > 15000) throw new IllegalArgumentException("wait_ms must be between 0 and 15000");
            if (waitMs > 0) return taskResult(tasks, origin,
                    tasks.waitForTerminal(origin, created.id(), Duration.ofMillis(waitMs)), 0, 16 * 1024);
            return json(Map.of("task", taskMap(created), "next_action", "use task_wait or task_output with this task_id"));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return error(exception);
        } catch (Exception exception) {
            return error(exception);
        }
    }

    private static McpSchema.CallToolResult desktop(AgentRegistry agents, TaskService tasks,
                                                    ProjectService projects, McpAccessService access,
                                                    TaskOrigin origin,
                                                    McpSchema.CallToolRequest request) {
        try {
            var args = args(request, DesktopArgs.class);
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
                    "desktop", null, scope.cwd(), Map.of(), timeout, action, Instant.now());
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
            return "{\"message\":\"response omitted; use task_output cursor or the Console detail endpoint\"}";
        }
    }

    private static McpSchema.CallToolResult taskWait(TaskService tasks, TaskOrigin origin,
                                                     McpSchema.CallToolRequest request) {
        try {
            var args = args(request, TaskWaitArgs.class);
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

    private static McpSchema.CallToolResult taskOutput(TaskService tasks, TaskOrigin origin,
                                                       McpSchema.CallToolRequest request) {
        try {
            var args = args(request, TaskOutputArgs.class);
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

    private static McpSchema.CallToolResult taskCancel(TaskService tasks, ArtifactTransferService transfers,
                                                       TaskOrigin origin, McpSchema.CallToolRequest request) {
        try {
            var args = args(request, TaskCancelArgs.class);
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
        // bounded artifact.  Keep task_wait useful for those capabilities as
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
        if (!inlineImage) {
            return McpSchema.CallToolResult.builder().addTextContent(text).build();
        }
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
     * model transcript.  Those fields remain available through machine_info.
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
        try {
            var text = boundedJsonText(value);
            return McpSchema.CallToolResult.builder().addTextContent(text).build();
        } catch (IOException exception) {
            return error(exception);
        }
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
        return McpSchema.CallToolResult.builder().isError(true).addTextContent(message).build();
    }

    private static <T> T args(McpSchema.CallToolRequest request, Class<T> type) {
        return McpJsonDefaults.getMapper().convertValue(request.arguments() == null ? Map.of() : request.arguments(), type);
    }

    record MachinesArgs(Integer offset, Integer limit) {
    }

    record MachineInfoArgs(@JsonProperty("machine_id") String machineId) {
    }

    record ProjectArgs(String operation,
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

    record CommandArgs(@JsonProperty("machine_id") String machineId,
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

    record DesktopArgs(String operation,
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
        DesktopArgs {
            args = args == null ? List.of() : List.copyOf(args);
        }
    }

    record BrowserArgs(@JsonProperty("machine_id") String machineId,
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

    record TaskWaitArgs(@JsonProperty("task_id") String taskId,
                                Long cursor,
                                @JsonProperty("wait_ms") Integer waitMs) {
    }

    record TaskOutputArgs(@JsonProperty("task_id") String taskId, Long cursor, Integer limit) {
    }

    record TaskCancelArgs(@JsonProperty("task_id") String taskId) {
    }

    record ArtifactFile(@JsonProperty("download_url") String downloadUrl,
                        @JsonProperty("file_id") String fileId,
                        @JsonProperty("file_name") String fileName,
                        @JsonProperty("mime_type") String mimeType,
                        Long bytes,
                        String sha256) {
    }

    record ArtifactPutArgs(@JsonProperty("machine_id") String machineId,
                           ArtifactFile file,
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

    record ArtifactGetArgs(@JsonProperty("machine_id") String machineId,
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

    record ArtifactReadArgs(@JsonProperty("artifact_id") String artifactId,
                            @JsonProperty("transfer_id") String transferId) {
    }

    private record ResolvedScope(String cwd, String root, ScopeMode mode) {
    }

    private static String firstNonBlank(String primary, String fallback) {
        return primary != null && !primary.isBlank() ? primary.trim()
                : fallback != null && !fallback.isBlank() ? fallback.trim() : null;
    }
}
