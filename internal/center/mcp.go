package center

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/modelcontextprotocol/go-sdk/mcp"

	"github.com/Prodigalgal/remote_connect_mcp/internal/desktop"
	"github.com/Prodigalgal/remote_connect_mcp/internal/protocol"
)

func NewMCPHandler(store *Store, version string) http.Handler {
	server := mcp.NewServer(&mcp.Implementation{Name: "remote-connect-mcp-center", Version: version}, &mcp.ServerOptions{
		Instructions: "Control registered machines through durable asynchronous tasks. A physical terminal may register multiple Agent identities (for example an unattended service Agent and a logged-in desktop Agent); always choose the explicit machine_id from machines_list. The bounded machine list includes capabilities and scope_mode; call machine_info when the workspace root or default cwd is needed. In workspace mode, cwd must remain under the registered workspace root; prefer a relative cwd. For command_start, generate one stable idempotency_key per logical command so a transport retry cannot run it twice. Return the task_id promptly. For long or unattended work, do not repeatedly poll in the same chat turn; check later with task_wait or task_output. Commands continue on the Agent when its Center connection is interrupted. The desktop tool is available only for a machine advertising desktop and returns bounded results; it never grants desktop capability to an ordinary service Agent.",
		PageSize:     50,
	})
	registerMCPTools(server, store)
	return mcp.NewStreamableHTTPHandler(func(*http.Request) *mcp.Server { return server }, &mcp.StreamableHTTPOptions{
		Stateless:                  true,
		DisableLocalhostProtection: true,
	})
}

const (
	mcpDefaultMachinePage = 25
	mcpMaxMachinePage     = 50
	mcpDefaultOutputPage  = 16 * 1024
	mcpMaxOutputPage      = 64 * 1024
	mcpMaxCommandEcho     = 1024
	mcpMaxCWDEcho         = 1024
	mcpMaxErrorEcho       = 2048
)

func registerMCPTools(server *mcp.Server, store *Store) {
	closedWorld := false
	openWorld := true
	destructive := true
	type machinesListArgs struct {
		Offset int `json:"offset,omitempty" jsonschema:"Zero-based page offset; default 0"`
		Limit  int `json:"limit,omitempty" jsonschema:"Page size; default 25, max 50"`
	}
	mcp.AddTool(server, &mcp.Tool{
		Name: "machines_list", Description: "List a bounded page of registered machines with IDs, platform, scope, capabilities, and online state. Use offset for the next page.",
		Annotations: &mcp.ToolAnnotations{ReadOnlyHint: true, OpenWorldHint: &closedWorld},
	}, func(_ context.Context, _ *mcp.CallToolRequest, args machinesListArgs) (*mcp.CallToolResult, any, error) {
		if args.Offset < 0 {
			return nil, nil, fmt.Errorf("offset must be non-negative")
		}
		limit := args.Limit
		if limit == 0 {
			limit = mcpDefaultMachinePage
		}
		if limit < 1 || limit > mcpMaxMachinePage {
			return nil, nil, fmt.Errorf("limit must be between 1 and %d", mcpMaxMachinePage)
		}
		machines, total := store.ListMachinesPage(time.Now().UTC(), args.Offset, limit)
		summaries := make([]mcpMachineSummary, 0, len(machines))
		for _, machine := range machines {
			summaries = append(summaries, compactMachine(machine))
		}
		nextOffset := args.Offset + len(summaries)
		return jsonToolResult(map[string]any{
			"machines": summaries, "offset": args.Offset, "limit": limit, "total": total,
			"has_more": nextOffset < total, "next_offset": nextOffset,
		})
	})

	type machineInfoArgs struct {
		MachineID string `json:"machine_id" jsonschema:"Registered machine ID"`
	}
	mcp.AddTool(server, &mcp.Tool{
		Name: "machine_info", Description: "Show one machine's platform, capabilities, scope mode, workspace root, default cwd, heartbeat, and online state.",
		Annotations: &mcp.ToolAnnotations{ReadOnlyHint: true, OpenWorldHint: &closedWorld},
	}, func(_ context.Context, _ *mcp.CallToolRequest, args machineInfoArgs) (*mcp.CallToolResult, any, error) {
		machine, ok := store.GetMachine(strings.TrimSpace(args.MachineID), time.Now().UTC())
		if !ok {
			return nil, nil, fmt.Errorf("machine %s not found", args.MachineID)
		}
		return jsonToolResult(machine)
	})

	type commandStartArgs struct {
		MachineID      string            `json:"machine_id" jsonschema:"Target machine ID from machines_list"`
		Command        string            `json:"command" jsonschema:"Shell command to execute"`
		CWD            string            `json:"cwd,omitempty" jsonschema:"Working directory; agent default when omitted"`
		Env            map[string]string `json:"env,omitempty" jsonschema:"Environment variables to add or override"`
		TimeoutSeconds int               `json:"timeout_seconds,omitempty" jsonschema:"Execution timeout; 0 means unlimited"`
		IdempotencyKey string            `json:"idempotency_key,omitempty" jsonschema:"Stable unique key for this logical command; reuse the same key when retrying"`
		WaitMS         int               `json:"wait_ms,omitempty" jsonschema:"Optional initial progress wait up to 15000 ms; default 0 returns immediately"`
	}
	mcp.AddTool(server, &mcp.Tool{
		Name: "command_start", Description: "Queue a shell command and immediately return a durable task ID. In workspace mode use a relative cwd inside the machine workspace. Use a stable idempotency_key so retries return the original task instead of executing twice.",
		Annotations: &mcp.ToolAnnotations{ReadOnlyHint: false, DestructiveHint: &destructive, OpenWorldHint: &openWorld},
	}, func(ctx context.Context, _ *mcp.CallToolRequest, args commandStartArgs) (*mcp.CallToolResult, any, error) {
		waitMS := args.WaitMS
		if waitMS < 0 || waitMS > 15000 {
			return nil, nil, fmt.Errorf("wait_ms must be between 0 and 15000")
		}
		task, err := store.CreateTask(protocol.CreateTaskRequest{
			MachineID: args.MachineID, Command: args.Command, CWD: args.CWD, Env: args.Env,
			TimeoutSeconds: args.TimeoutSeconds, IdempotencyKey: args.IdempotencyKey,
		})
		if err != nil {
			return nil, nil, err
		}
		if waitMS > 0 {
			task = waitForTask(ctx, store, task.ID, 0, time.Duration(waitMS)*time.Millisecond)
		}
		return taskResult(store, task, 0, mcpDefaultOutputPage)
	})

	type taskWaitArgs struct {
		TaskID string `json:"task_id" jsonschema:"Task ID returned by command_start"`
		Cursor int64  `json:"cursor,omitempty" jsonschema:"Known output byte cursor"`
		WaitMS int    `json:"wait_ms,omitempty" jsonschema:"Optional long-poll wait up to 20000 ms; default 0 returns current state immediately"`
	}
	mcp.AddTool(server, &mcp.Tool{
		Name: "task_wait", Description: "Read task state and the next bounded output page. Default is non-blocking; use a short positive wait only when the user asks to monitor now.",
		Annotations: &mcp.ToolAnnotations{ReadOnlyHint: true, OpenWorldHint: &closedWorld},
	}, func(ctx context.Context, _ *mcp.CallToolRequest, args taskWaitArgs) (*mcp.CallToolResult, any, error) {
		task, ok := store.GetTask(strings.TrimSpace(args.TaskID))
		if !ok {
			return nil, nil, fmt.Errorf("task %s not found", args.TaskID)
		}
		waitMS := args.WaitMS
		if waitMS < 0 || waitMS > 20000 {
			return nil, nil, fmt.Errorf("wait_ms must be between 0 and 20000")
		}
		if waitMS > 0 && task.OutputBytes <= args.Cursor && !terminalStatus(task.Status) {
			task = waitForTask(ctx, store, task.ID, args.Cursor, time.Duration(waitMS)*time.Millisecond)
		}
		return taskResult(store, task, args.Cursor, mcpDefaultOutputPage)
	})

	type taskOutputArgs struct {
		TaskID string `json:"task_id" jsonschema:"Task ID"`
		Cursor int64  `json:"cursor,omitempty" jsonschema:"Output byte cursor"`
		Limit  int    `json:"limit,omitempty" jsonschema:"Maximum output bytes; default 16384, max 65536"`
	}
	mcp.AddTool(server, &mcp.Tool{
		Name: "task_output", Description: "Read one bounded page of persisted task output from a byte cursor; request another page with next_cursor.",
		Annotations: &mcp.ToolAnnotations{ReadOnlyHint: true, OpenWorldHint: &closedWorld},
	}, func(_ context.Context, _ *mcp.CallToolRequest, args taskOutputArgs) (*mcp.CallToolResult, any, error) {
		task, ok := store.GetTask(strings.TrimSpace(args.TaskID))
		if !ok {
			return nil, nil, fmt.Errorf("task %s not found", args.TaskID)
		}
		if args.Cursor < 0 {
			return nil, nil, fmt.Errorf("cursor must be non-negative")
		}
		if args.Limit < 0 || args.Limit > mcpMaxOutputPage {
			return nil, nil, fmt.Errorf("limit must be between 0 and %d", mcpMaxOutputPage)
		}
		return taskResult(store, task, args.Cursor, args.Limit)
	})

	type taskCancelArgs struct {
		TaskID string `json:"task_id" jsonschema:"Task ID to cancel"`
	}
	mcp.AddTool(server, &mcp.Tool{
		Name: "task_cancel", Description: "Cancel a queued or running task; cancellation is delivered automatically when the agent reconnects.",
		Annotations: &mcp.ToolAnnotations{ReadOnlyHint: false, DestructiveHint: &destructive, OpenWorldHint: &closedWorld},
	}, func(_ context.Context, _ *mcp.CallToolRequest, args taskCancelArgs) (*mcp.CallToolResult, any, error) {
		task, err := store.CancelTask(strings.TrimSpace(args.TaskID))
		if err != nil {
			return nil, nil, err
		}
		return jsonToolResult(compactTask(task))
	})

	type desktopArgs struct {
		MachineID      string   `json:"machine_id,omitempty" jsonschema:"Machine ID with the desktop capability"`
		Operation      string   `json:"operation" jsonschema:"screenshot, screenshot_fetch, or launch"`
		TaskID         string   `json:"task_id,omitempty" jsonschema:"Existing screenshot task ID when operation is screenshot_fetch"`
		Executable     string   `json:"executable,omitempty" jsonschema:"Program to launch when operation is launch"`
		Args           []string `json:"args,omitempty" jsonschema:"Literal program arguments when operation is launch"`
		CWD            string   `json:"cwd,omitempty" jsonschema:"Working directory for the desktop task"`
		TimeoutSeconds int      `json:"timeout_seconds,omitempty" jsonschema:"Launch/screenshot timeout; default 30 seconds"`
		WaitMS         int      `json:"wait_ms,omitempty" jsonschema:"Initial wait up to 15000 ms; screenshot defaults to 10000 ms"`
		IdempotencyKey string   `json:"idempotency_key,omitempty" jsonschema:"Stable key for one launch/screenshot request"`
	}
	mcp.AddTool(server, &mcp.Tool{
		Name: "desktop", Description: "Use an explicitly registered desktop-capable Agent. operation=screenshot captures a bounded PNG from the logged-in user's desktop; operation=launch starts a literal executable in that user's session; operation=screenshot_fetch reads a previously queued screenshot. Desktop Agents are separate machine identities from an unattended service Agent.",
		Annotations: &mcp.ToolAnnotations{ReadOnlyHint: false, DestructiveHint: &destructive, OpenWorldHint: &openWorld},
	}, func(ctx context.Context, _ *mcp.CallToolRequest, args desktopArgs) (*mcp.CallToolResult, any, error) {
		operation := strings.ToLower(strings.TrimSpace(args.Operation))
		if args.WaitMS < 0 || args.WaitMS > 15000 {
			return nil, nil, fmt.Errorf("wait_ms must be between 0 and 15000")
		}
		if operation == "screenshot_fetch" {
			task, ok := store.GetTask(strings.TrimSpace(args.TaskID))
			if !ok {
				return nil, nil, fmt.Errorf("task %s not found", args.TaskID)
			}
			if task.Kind != protocol.TaskKindDesktop {
				return nil, nil, errors.New("task is not a desktop task")
			}
			if task.ArtifactBytes <= 0 {
				return jsonToolResult(map[string]any{"task": compactTask(task), "next_action": "screenshot is not ready; retry screenshot_fetch later"})
			}
			return desktopImageResult(store, task)
		}
		if operation != desktop.OperationScreenshot && operation != desktop.OperationLaunch {
			return nil, nil, errors.New("operation must be screenshot, screenshot_fetch, or launch")
		}
		machine, ok := store.GetMachine(strings.TrimSpace(args.MachineID), time.Now().UTC())
		if !ok {
			return nil, nil, fmt.Errorf("machine %s not found", args.MachineID)
		}
		if !hasCapability(machine.Capabilities, protocol.CapabilityDesktop) {
			return nil, nil, fmt.Errorf("machine %s does not advertise desktop capability", args.MachineID)
		}
		timeout := args.TimeoutSeconds
		if timeout == 0 {
			timeout = 30
		}
		action := &protocol.DesktopAction{Operation: operation, Executable: args.Executable, Args: append([]string(nil), args.Args...), CWD: args.CWD}
		task, err := store.CreateTask(protocol.CreateTaskRequest{
			MachineID: args.MachineID, Kind: protocol.TaskKindDesktop, RequiredCapability: protocol.CapabilityDesktop,
			CWD: args.CWD, TimeoutSeconds: timeout, IdempotencyKey: args.IdempotencyKey, Desktop: action,
		})
		if err != nil {
			return nil, nil, err
		}
		waitMS := args.WaitMS
		if operation == desktop.OperationScreenshot && waitMS == 0 {
			waitMS = 10000
		}
		if waitMS > 0 {
			task = waitForTask(ctx, store, task.ID, 0, time.Duration(waitMS)*time.Millisecond)
		}
		if operation == desktop.OperationScreenshot && task.ArtifactBytes > 0 {
			return desktopImageResult(store, task)
		}
		return jsonToolResult(map[string]any{"task": compactTask(task), "next_action": nextDesktopAction(task, operation)})
	})
}

func desktopImageResult(store *Store, task Task) (*mcp.CallToolResult, any, error) {
	data, mimeType, hash, err := store.ReadArtifact(task.ID)
	if err != nil {
		return nil, nil, err
	}
	metadata, err := json.Marshal(map[string]any{"task": compactTask(task), "artifact": map[string]any{"mime_type": mimeType, "bytes": len(data), "sha256": hash}})
	if err != nil {
		return nil, nil, err
	}
	return &mcp.CallToolResult{Content: []mcp.Content{
		&mcp.TextContent{Text: string(metadata)},
		&mcp.ImageContent{Data: data, MIMEType: mimeType},
	}}, nil, nil
}

func nextDesktopAction(task Task, operation string) string {
	if operation == desktop.OperationScreenshot {
		if task.ArtifactBytes > 0 {
			return "call desktop with operation screenshot_fetch and this task_id"
		}
		return "call desktop with operation screenshot_fetch later using this task_id"
	}
	return nextAction(task, false)
}

func waitForTask(ctx context.Context, store *Store, taskID string, cursor int64, timeout time.Duration) Task {
	deadline := time.NewTimer(timeout)
	defer deadline.Stop()
	for {
		task, ok := store.GetTask(taskID)
		if !ok {
			return Task{ID: taskID, Status: protocol.TaskFailed, Error: "task disappeared"}
		}
		if task.OutputBytes > cursor || terminalStatus(task.Status) || task.Status == protocol.TaskRunning {
			return task
		}
		// Output/state notifications use a task stream so a high-throughput
		// command does not wake the admin/Agent control-plane stream for every
		// chunk. The task row is still re-read after each event.
		changed := store.TaskChangedFor(taskID)
		if latest, ok := store.GetTask(taskID); ok && (latest.OutputBytes > cursor || terminalStatus(latest.Status) || latest.Status == protocol.TaskRunning) {
			store.ReleaseTaskChanged(taskID, changed)
			return latest
		}
		select {
		case <-ctx.Done():
			store.ReleaseTaskChanged(taskID, changed)
			return task
		case <-deadline.C:
			store.ReleaseTaskChanged(taskID, changed)
			if latest, ok := store.GetTask(taskID); ok {
				return latest
			}
			return task
		case <-changed:
			store.ReleaseTaskChanged(taskID, changed)
		}
	}
}

func taskResult(store *Store, task Task, cursor int64, limit int) (*mcp.CallToolResult, any, error) {
	if limit <= 0 {
		limit = mcpDefaultOutputPage
	}
	data, next, more, err := store.ReadOutput(task.ID, cursor, limit)
	if err != nil {
		return nil, nil, err
	}
	return jsonToolResult(map[string]any{
		"task":        compactTask(task),
		"output":      map[string]any{"text": string(data), "cursor": cursor, "next_cursor": next, "more": more},
		"next_action": nextAction(task, more),
	})
}

type mcpMachineSummary struct {
	ID           string   `json:"id"`
	Name         string   `json:"name"`
	HostID       string   `json:"host_id,omitempty"`
	OS           string   `json:"os,omitempty"`
	Arch         string   `json:"arch,omitempty"`
	ScopeMode    string   `json:"scope_mode"`
	Capabilities []string `json:"capabilities,omitempty"`
	Online       bool     `json:"online"`
}

func compactMachine(machine MachineView) mcpMachineSummary {
	return mcpMachineSummary{
		ID: machine.ID, Name: machine.Name, HostID: machine.HostID, OS: machine.OS, Arch: machine.Arch,
		ScopeMode: machine.ScopeMode, Capabilities: append([]string(nil), machine.Capabilities...), Online: machine.Online,
	}
}

type mcpTaskView struct {
	ID                 string     `json:"id"`
	MachineID          string     `json:"machine_id"`
	Kind               string     `json:"kind,omitempty"`
	RequiredCapability string     `json:"required_capability,omitempty"`
	Command            string     `json:"command,omitempty"`
	CommandTruncated   bool       `json:"command_truncated,omitempty"`
	CWD                string     `json:"cwd,omitempty"`
	CWDTruncated       bool       `json:"cwd_truncated,omitempty"`
	TimeoutSeconds     int        `json:"timeout_seconds,omitempty"`
	Status             string     `json:"status"`
	Attempt            int        `json:"attempt"`
	ExitCode           *int       `json:"exit_code,omitempty"`
	Error              string     `json:"error,omitempty"`
	OutputBytes        int64      `json:"output_bytes"`
	OutputTruncated    bool       `json:"output_truncated,omitempty"`
	ArtifactMIME       string     `json:"artifact_mime,omitempty"`
	ArtifactBytes      int64      `json:"artifact_bytes,omitempty"`
	ArtifactSHA256     string     `json:"artifact_sha256,omitempty"`
	CreatedAt          time.Time  `json:"created_at"`
	StartedAt          *time.Time `json:"started_at,omitempty"`
	FinishedAt         *time.Time `json:"finished_at,omitempty"`
}

func compactTask(task Task) mcpTaskView {
	command, commandTruncated := compactText(task.Command, mcpMaxCommandEcho)
	cwd, cwdTruncated := compactText(task.CWD, mcpMaxCWDEcho)
	errorText, _ := compactText(task.Error, mcpMaxErrorEcho)
	return mcpTaskView{
		ID: task.ID, MachineID: task.MachineID, Kind: task.Kind, RequiredCapability: task.RequiredCapability, Command: command, CommandTruncated: commandTruncated,
		CWD: cwd, CWDTruncated: cwdTruncated, TimeoutSeconds: task.TimeoutSeconds, Status: task.Status, Attempt: task.Attempt, ExitCode: task.ExitCode,
		Error: errorText, OutputBytes: task.OutputBytes, OutputTruncated: task.OutputTruncated, ArtifactMIME: task.ArtifactMIME, ArtifactBytes: task.ArtifactBytes, ArtifactSHA256: task.ArtifactSHA256,
		CreatedAt: task.CreatedAt, StartedAt: task.StartedAt, FinishedAt: task.FinishedAt,
	}
}

func compactText(value string, max int) (string, bool) {
	if max < 1 || len(value) <= max {
		return value, false
	}
	cut := 0
	for index := range value {
		if index > max {
			break
		}
		cut = index
	}
	return value[:cut], true
}

func nextAction(task Task, more bool) string {
	if more {
		return "call task_output with next_cursor"
	}
	if terminalStatus(task.Status) {
		return "task is finished"
	}
	return "task is still active; for long or unattended work report the task_id now and check later; do not repeatedly poll in the same chat turn"
}

func terminalStatus(status string) bool {
	return status == protocol.TaskCompleted || status == protocol.TaskFailed || status == protocol.TaskCanceled
}

func jsonToolResult(value any) (*mcp.CallToolResult, any, error) {
	data, err := json.Marshal(value)
	if err != nil {
		return nil, nil, err
	}
	// Return one JSON text representation only.  Sending the same payload in
	// both Content and StructuredContent makes ChatGPT Web ingest it twice and
	// needlessly expands the conversation context.
	return &mcp.CallToolResult{Content: []mcp.Content{&mcp.TextContent{Text: string(data)}}}, nil, nil
}
