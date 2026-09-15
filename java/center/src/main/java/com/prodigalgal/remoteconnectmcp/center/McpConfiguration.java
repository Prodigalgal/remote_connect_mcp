package com.prodigalgal.remoteconnectmcp.center;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
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
import com.prodigalgal.remoteconnectmcp.protocol.UpgradeArtifact;
import com.prodigalgal.remoteconnectmcp.protocol.UpgradePlan;
import com.prodigalgal.remoteconnectmcp.protocol.UpgradeStatusRequest;
import jakarta.servlet.http.HttpServlet;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
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
        McpSchema.Icon.class, McpSchema.PaginatedRequest.class,
        McpSchema.PaginatedResult.class,
        ArtifactRequest.class, ArtifactResponse.class, AgentMetadata.class, AgentRuntimeDescriptor.class,
        com.prodigalgal.remoteconnectmcp.protocol.ExecutionContract.class,
        com.prodigalgal.remoteconnectmcp.protocol.ExecutionContract.Budget.class,
        AgentConfigUpdate.class,
        OutputRequest.class, OutputResponse.class, PollRequest.class, PollResponse.class,
        RegisterRequest.class, RegisterResponse.class, TaskCommand.class,
        TaskCommand.DesktopAction.class, TaskUpdateRequest.class,
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
        AdminController.IssueEnrollmentRequest.class, TaskService.ArtifactGcResult.class})
public class McpConfiguration {
    private static final int MAX_MACHINE_PAGE = 50;
    private static final int MAX_OUTPUT_PAGE = 64 * 1024;
    // Keep screenshots useful in the ChatGPT conversation without allowing a
    // single desktop/browser result to consume the whole MCP context window.
    // Larger artifacts remain available through the authenticated Console
    // artifact endpoint using the returned SHA-256 metadata.
    private static final int MAX_INLINE_IMAGE_BYTES = 2 * 1024 * 1024;

    @Bean
    public HttpServletStreamableServerTransportProvider mcpTransport(CenterTokenConfig tokens) {
        ServerTransportSecurityValidator security = headers -> {
            var authorization = headers.entrySet().stream()
                    .filter(entry -> "authorization".equalsIgnoreCase(entry.getKey()))
                    .flatMap(entry -> entry.getValue().stream())
                    .findFirst()
                    .orElse("");
            var parts = authorization.trim().split("\\s+", 2);
            var bearer = parts.length == 2 && "Bearer".equalsIgnoreCase(parts[0]) ? parts[1].trim() : "";
            if (!tokens.acceptsMcp(bearer)) {
                throw new ServerTransportSecurityException(401, "invalid MCP bearer token");
            }
        };
        return HttpServletStreamableServerTransportProvider.builder()
                .mcpEndpoint("/mcp")
                .disallowDelete(true)
                .maxRequestSize(256 * 1024)
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
                                    ExecutorService mcpVirtualThreadExecutor,
                                    @Value("${rcm.version:dev}") String version) {
        var server = McpServer.async(transport)
                .serverInfo("remote-connect-mcp-center", version)
                .instructions("Use machines_list first and always pass an explicit machine_id and scope. Prefer project/worktree or workspace/path; unrestricted must be explicit. Tasks are asynchronous and bounded; report task_id for long work and read output with cursors.")
                .strictToolNameValidation(true)
                .validateToolInputs(true)
                .requestTimeout(Duration.ofSeconds(30))
                .tools(toolSpecs(agents, tasks, projects, mcpVirtualThreadExecutor))
                .build();
        return server;
    }

    private static List<McpServerFeatures.AsyncToolSpecification> toolSpecs(AgentRegistry agents, TaskService tasks,
                                                                             ProjectService projects,
                                                                             ExecutorService mcpVirtualThreadExecutor) {
        var scheduler = Schedulers.fromExecutor(mcpVirtualThreadExecutor);
        return List.of(
                tool("machines_list", "List a bounded page of registered machines.", schema(
                        Map.of("offset", integer("zero-based offset"), "limit", integer("1-50 page size")), List.of()),
                        request -> machinesList(agents, request), scheduler),
                tool("machine_info", "Show one machine's platform, capabilities, scope and heartbeat.", schema(
                        Map.of("machine_id", string("machine ID from machines_list")), List.of("machine_id")),
                        request -> machineInfo(agents, request), scheduler),
                tool("project", "List, register, remove projects or queue one isolated Git/worktree operation on a selected machine.", schema(
                        Map.ofEntries(
                            Map.entry("operation", string("list, register, remove, worktree_create, worktree_remove, git_status, git_diff, git_log, git_commit, git_merge, or git_merge_abort")),
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
                                Map.entry("limit", integer("project list page size, at most 50")),
                                Map.entry("idempotency_key", string("stable retry key"))),
                        List.of("operation")), request -> project(projects, request), scheduler),
                tool("desktop", "Queue a bounded screenshot, screen listing, launch, pointer, drag, key, text, clipboard, or window-focus action on an explicitly desktop-capable user-session Agent.", schema(
                        Map.ofEntries(
                                Map.entry("operation", string("screenshot, screenshot_region, screens, launch, click, double_click, right_click, move, drag, key, type, clipboard_read, clipboard_write, focus, or result")),
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
                                Map.entry("session_id", string("optional stable user/session identifier")),
                                Map.entry("risk", string("low, high, or critical")),
                                Map.entry("elevation_required", Map.of("type", "boolean", "description", "explicitly request elevation"))),
                        List.of("operation")), request -> desktop(agents, tasks, projects, request), scheduler),
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
                                Map.entry("session_id", string("optional stable user/session identifier")),
                                Map.entry("risk", string("low, high, or critical")),
                                Map.entry("elevation_required", Map.of("type", "boolean", "description", "explicitly request elevation"))),
                        List.of("machine_id", "command")), request -> browser(agents, tasks, projects, request), scheduler),
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
                                Map.entry("session_id", string("optional stable user/session identifier")),
                                Map.entry("risk", string("low, high, or critical")),
                                Map.entry("elevation_required", Map.of("type", "boolean", "description", "explicitly request elevation"))),
                        List.of("machine_id", "command")),
                        request -> commandStart(agents, tasks, projects, request), scheduler),
                tool("task_wait", "Read task state and one bounded output page; optionally wait briefly for a change.", schema(
                        Map.of("task_id", string("task ID"), "cursor", integer("known output cursor"), "wait_ms", integer("0-20000")), List.of("task_id")),
                        request -> taskWait(tasks, request), scheduler),
                tool("task_output", "Read one bounded output page from a byte cursor.", schema(
                        Map.of("task_id", string("task ID"), "cursor", integer("known output cursor"), "limit", integer("maximum 65536 bytes")), List.of("task_id")),
                        request -> taskOutput(tasks, request), scheduler),
                tool("task_cancel", "Cancel a queued or running task.", schema(
                        Map.of("task_id", string("task ID")), List.of("task_id")),
                        request -> taskCancel(tasks, request), scheduler));
    }

    private static McpServerFeatures.AsyncToolSpecification tool(String name, String description, Map<String, Object> schema,
                                                                   Function<McpSchema.CallToolRequest, McpSchema.CallToolResult> handler,
                                                                   reactor.core.scheduler.Scheduler scheduler) {
        var annotations = McpSchema.ToolAnnotations.builder()
                .readOnlyHint(name.equals("machines_list") || name.equals("machine_info") || name.equals("task_wait") || name.equals("task_output"))
                .destructiveHint(name.equals("command_start") || name.equals("task_cancel") || name.equals("project"))
                .openWorldHint(name.equals("command_start") || name.equals("project"))
                .build();
        var tool = McpSchema.Tool.builder(name)
                .description(description)
                .inputSchema(schema)
                .annotations(annotations)
                .build();
        return McpServerFeatures.AsyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> Mono.fromCallable(() -> handler.apply(request)).subscribeOn(scheduler))
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

    private static McpSchema.CallToolResult machinesList(AgentRegistry agents, McpSchema.CallToolRequest request) {
        try {
            var args = args(request, MachinesArgs.class);
            var offset = Math.max(0, args.offset() == null ? 0 : args.offset());
            var limit = args.limit() == null ? 25 : args.limit();
            if (limit < 1 || limit > MAX_MACHINE_PAGE) {
                throw new IllegalArgumentException("limit must be between 1 and " + MAX_MACHINE_PAGE);
            }
            var machines = agents.listMachines(offset, limit, Instant.now());
            var values = machines.stream().map(McpConfiguration::machineMap).toList();
            var total = agents.totalCount();
            return json(Map.of("machines", values, "offset", offset, "limit", limit,
                    "total", total, "has_more", offset + values.size() < total,
                    "next_action", offset + values.size() < total
                            ? "call machines_list with offset + limit" : "no more machines"));
        } catch (Exception exception) {
            return error(exception);
        }
    }

    private static McpSchema.CallToolResult machineInfo(AgentRegistry agents, McpSchema.CallToolRequest request) {
        try {
            var args = args(request, MachineInfoArgs.class);
            var machine = agents.findMachine(args.machineId(), Instant.now()).orElseThrow(() -> new IllegalArgumentException("machine not found"));
            return json(machineMap(machine));
        } catch (Exception exception) {
            return error(exception);
        }
    }

    private static McpSchema.CallToolResult project(ProjectService projects, McpSchema.CallToolRequest request) {
        try {
            var args = args(request, ProjectArgs.class);
            var operation = args.operation() == null ? "" : args.operation().trim().toLowerCase(java.util.Locale.ROOT);
            return switch (operation) {
                case "list" -> {
                    var offset = args.offset() == null ? 0 : args.offset();
                    var limit = args.limit() == null ? 25 : args.limit();
                    if (offset < 0 || limit < 1 || limit > 50) {
                        throw new IllegalArgumentException("project list offset must be non-negative and limit must be between 1 and 50");
                    }
                    var values = projects.list(args.machineId(), offset, limit);
                    var total = projects.count(args.machineId());
                    yield json(Map.of("projects", values, "offset", offset, "limit", limit,
                            "total", total, "has_more", offset + values.size() < total,
                            "next_action", offset + values.size() < total
                                    ? "call project list with offset + limit" : "no more projects"));
                }
                case "register" -> json(Map.of("project", projects.register(new ProjectRegistrationRequest(
                        args.machineId(), args.name(), args.rootPath(), args.repositoryPath(), args.defaultRef()))));
                case "remove" -> json(Map.of("project", projects.remove(args.projectId())));
                case "worktree_create" -> {
                    var value = projects.createWorktree(args.projectId(), new ProjectWorktreeRequest(args.ref(), args.idempotencyKey()));
                    yield json(Map.of("worktree", value, "next_action", "use task_wait with the returned task_id, then submit project-scoped tasks"));
                }
                case "worktree_remove" -> {
                    var value = projects.removeWorktree(args.projectId(), args.worktreeId(), args.idempotencyKey());
                    yield json(Map.of("worktree", value, "next_action", "use task_wait with the returned task_id"));
                }
                case "git_status", "git_diff", "git_log", "git_commit", "git_merge", "git_merge_abort" -> {
                    var gitOperation = operation.substring("git_".length());
                    var value = projects.gitOperation(args.projectId(), gitOperation,
                            new ProjectGitOperationRequest(args.worktreeId(), args.ref(), args.message(), args.mode(), args.idempotencyKey()));
                    yield json(Map.of("task", taskMap(value),
                            "next_action", "use task_wait or task_output with the returned task_id"));
                }
                default -> throw new IllegalArgumentException("operation must be list, register, remove, worktree_create, worktree_remove, or git_* operation");
            };
        } catch (Exception exception) {
            return error(exception);
        }
    }

    private static McpSchema.CallToolResult commandStart(AgentRegistry agents, TaskService tasks,
                                                         ProjectService projects, McpSchema.CallToolRequest request) {
        try {
            var args = args(request, CommandArgs.class);
            var timeout = args.timeoutSeconds() == null ? 0 : args.timeoutSeconds();
            var scope = resolveScope(agents, projects, args.machineId(), args.projectId(), args.worktreeId(),
                    args.scopeMode(), args.scopeRoot(), args.cwd());
            var command = new com.prodigalgal.remoteconnectmcp.protocol.TaskCommand("", com.prodigalgal.remoteconnectmcp.protocol.TaskKind.COMMAND,
                    null, args.command(), scope.cwd(), args.env(), timeout, null, Instant.now());
            var task = tasks.create(new CreateTaskRequest(args.machineId(), command, args.idempotencyKey(),
                    args.projectId(), args.worktreeId(), scope.mode(), scope.root(), args.sessionId(),
                    args.risk(), Boolean.TRUE.equals(args.elevationRequired())), "mcp");
            return json(Map.of("task", taskMap(task), "next_action", "use task_wait or task_output with this task_id"));
        } catch (Exception exception) {
            return error(exception);
        }
    }

    private static McpSchema.CallToolResult browser(AgentRegistry agents, TaskService tasks,
                                                    ProjectService projects, McpSchema.CallToolRequest request) {
        try {
            var args = args(request, BrowserArgs.class);
            var machine = agents.findMachine(args.machineId(), Instant.now()).orElseThrow(() -> new IllegalArgumentException("machine not found"));
            if (!machine.capabilities().contains("browser")) throw new IllegalArgumentException("machine does not advertise browser capability");
            var timeout = args.timeoutSeconds() == null ? 300 : args.timeoutSeconds();
            var scope = resolveScope(agents, projects, args.machineId(), args.projectId(), args.worktreeId(),
                    args.scopeMode(), args.scopeRoot(), args.cwd());
            var command = new com.prodigalgal.remoteconnectmcp.protocol.TaskCommand("", com.prodigalgal.remoteconnectmcp.protocol.TaskKind.BROWSER,
                    "browser", args.command(), scope.cwd(), Map.of(), timeout, null, Instant.now());
            var created = tasks.create(new CreateTaskRequest(args.machineId(), command, args.idempotencyKey(),
                    args.projectId(), args.worktreeId(), scope.mode(), scope.root(), args.sessionId(),
                    args.risk(), Boolean.TRUE.equals(args.elevationRequired())), "mcp");
            var waitMs = args.waitMs() == null ? 0 : args.waitMs();
            if (waitMs < 0 || waitMs > 15000) throw new IllegalArgumentException("wait_ms must be between 0 and 15000");
            if (waitMs > 0) return taskResult(tasks, tasks.waitForTerminal(created.id(), Duration.ofMillis(waitMs)), 0, 16 * 1024);
            return json(Map.of("task", taskMap(created), "next_action", "use task_wait or task_output with this task_id"));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return error(exception);
        } catch (Exception exception) {
            return error(exception);
        }
    }

    private static McpSchema.CallToolResult desktop(AgentRegistry agents, TaskService tasks,
                                                    ProjectService projects, McpSchema.CallToolRequest request) {
        try {
            var args = args(request, DesktopArgs.class);
            var operation = args.operation() == null ? "" : args.operation().trim().toLowerCase();
            // All desktop operations are queued by default.  A caller may opt
            // into a short bounded wait explicitly, but a screenshot must not
            // pin the MCP request simply because it is the default action.
            var waitMs = args.waitMs() == null ? 0 : args.waitMs();
            if (waitMs < 0 || waitMs > 15000) throw new IllegalArgumentException("wait_ms must be between 0 and 15000");
            if ("result".equals(operation)) {
                var taskState = tasks.find(args.taskId()).orElseThrow(() -> new IllegalArgumentException("task not found"));
                if (!"desktop".equals(taskState.command().kind().wireValue())) throw new IllegalArgumentException("task is not a desktop task");
                var task = new TaskView(taskState);
                if (waitMs > 0 && !TaskStatus.terminal(task.status())) {
                    task = tasks.waitForTerminal(task.id(), Duration.ofMillis(waitMs));
                }
                return desktopResult(tasks, task);
            }
            if (!"screenshot".equals(operation) && !"screenshot_region".equals(operation) && !"screens".equals(operation) && !"launch".equals(operation)
                    && !"click".equals(operation) && !"double_click".equals(operation) && !"right_click".equals(operation)
                    && !"move".equals(operation) && !"drag".equals(operation) && !"key".equals(operation)
                    && !"type".equals(operation) && !"clipboard_read".equals(operation)
                    && !"clipboard_write".equals(operation) && !"focus".equals(operation)) {
                throw new IllegalArgumentException("operation must be screenshot, screenshot_region, screens, launch, click, double_click, right_click, move, drag, key, type, clipboard_read, clipboard_write, focus, or result");
            }
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
                    args.projectId(), args.worktreeId(), scope.mode(), scope.root(), args.sessionId(),
                    args.risk(), Boolean.TRUE.equals(args.elevationRequired())), "mcp");
            if (waitMs > 0) {
                var completed = tasks.waitForTerminal(created.id(), Duration.ofMillis(waitMs));
                return desktopResult(tasks, completed);
            }
            return json(Map.of("task", taskMap(created), "next_action", "call desktop with operation=result and this task_id"));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return error(exception);
        } catch (Exception exception) {
            return error(exception);
        }
    }

    private static McpSchema.CallToolResult desktopResult(TaskService tasks, TaskView task) {
        if (!TaskStatus.terminal(task.status())) {
            return json(Map.of("task", taskMap(task), "next_action", "retry operation=result later"));
        }
        var page = tasks.readOutput(task.id(), 0, 16 * 1024);
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
        var artifact = tasks.readArtifact(task.id());
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
            return McpJsonDefaults.getMapper().writeValueAsString(value);
        } catch (IOException exception) {
            return "artifact ready";
        }
    }

    private static McpSchema.CallToolResult taskWait(TaskService tasks, McpSchema.CallToolRequest request) {
        try {
            var args = args(request, TaskWaitArgs.class);
            var cursor = args.cursor() == null ? 0 : args.cursor();
            var waitMs = args.waitMs() == null ? 0 : args.waitMs();
            if (waitMs < 0 || waitMs > 20000) {
                throw new IllegalArgumentException("wait_ms must be between 0 and 20000");
            }
            var task = tasks.find(args.taskId()).orElseThrow(() -> new IllegalArgumentException("task not found"));
            var view = waitMs == 0 ? new TaskView(task) : tasks.waitForChange(args.taskId(), cursor, Duration.ofMillis(waitMs));
            return taskResult(tasks, view, cursor, 16 * 1024);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return error(exception);
        } catch (Exception exception) {
            return error(exception);
        }
    }

    private static McpSchema.CallToolResult taskOutput(TaskService tasks, McpSchema.CallToolRequest request) {
        try {
            var args = args(request, TaskOutputArgs.class);
            var cursor = args.cursor() == null ? 0 : args.cursor();
            var limit = args.limit() == null ? 16 * 1024 : args.limit();
            if (limit < 1 || limit > MAX_OUTPUT_PAGE) {
                throw new IllegalArgumentException("limit must be between 1 and " + MAX_OUTPUT_PAGE);
            }
            var task = tasks.find(args.taskId()).orElseThrow(() -> new IllegalArgumentException("task not found"));
            return taskResult(tasks, new TaskView(task), cursor, limit);
        } catch (Exception exception) {
            return error(exception);
        }
    }

    private static McpSchema.CallToolResult taskCancel(TaskService tasks, McpSchema.CallToolRequest request) {
        try {
            var args = args(request, TaskCancelArgs.class);
            return json(Map.of("task", taskMap(tasks.cancel(args.taskId()))));
        } catch (Exception exception) {
            return error(exception);
        }
    }

    private static McpSchema.CallToolResult taskResult(TaskService tasks, TaskView view, long cursor, int limit) {
        var page = tasks.readOutput(view.id(), cursor, limit);
        var output = new LinkedHashMap<String, Object>();
        output.put("text", new String(page.data(), StandardCharsets.UTF_8));
        output.put("cursor", page.cursor());
        output.put("next_cursor", page.nextCursor());
        output.put("more", page.more());
        // Browser and desktop tasks can finish with a screenshot or another
        // bounded artifact.  Keep task_wait useful for those capabilities as
        // well as command tasks: return metadata for every artifact, and
        // inline only image bytes (the same bounded 8 MiB contract used by
        // the Center artifact endpoint).  Non-image downloads remain
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
        var artifact = tasks.readArtifact(view.id());
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
        value.put("command", TaskKind.BROWSER.equals(task.kind())
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
        if (task.scopeMode() != null) {
            var scope = new LinkedHashMap<String, Object>();
            scope.put("mode", task.scopeMode());
            scope.put("project_id", task.projectId());
            scope.put("worktree_id", task.worktreeId());
            scope.put("root", compact(task.scopeRoot(), 1024));
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

    private static McpSchema.CallToolResult json(Object value) {
        try {
            return McpSchema.CallToolResult.builder().addTextContent(McpJsonDefaults.getMapper().writeValueAsString(value)).build();
        } catch (IOException exception) {
            return error(exception);
        }
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

    private record ResolvedScope(String cwd, String root, ScopeMode mode) {
    }
}
