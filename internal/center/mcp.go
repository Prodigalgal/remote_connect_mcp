package center

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/modelcontextprotocol/go-sdk/mcp"

	"github.com/Prodigalgal/remote_connect_mcp/internal/protocol"
)

func NewMCPHandler(store *Store, version string) http.Handler {
	server := mcp.NewServer(&mcp.Implementation{Name: "remote-connect-mcp-center", Version: version}, &mcp.ServerOptions{
		Instructions: "Control registered machines through durable asynchronous tasks. Call machines_list first, pass machine_id explicitly, start commands, then use task_wait or task_output. Commands continue on the agent when its center connection is interrupted.",
		PageSize:     50,
	})
	registerMCPTools(server, store)
	return mcp.NewStreamableHTTPHandler(func(*http.Request) *mcp.Server { return server }, &mcp.StreamableHTTPOptions{
		Stateless:                  true,
		DisableLocalhostProtection: true,
	})
}

func registerMCPTools(server *mcp.Server, store *Store) {
	closedWorld := false
	openWorld := true
	destructive := true
	mcp.AddTool(server, &mcp.Tool{
		Name: "machines_list", Description: "List registered machines with IDs and online state.",
		Annotations: &mcp.ToolAnnotations{ReadOnlyHint: true, OpenWorldHint: &closedWorld},
	}, func(_ context.Context, _ *mcp.CallToolRequest, _ struct{}) (*mcp.CallToolResult, any, error) {
		return jsonToolResult(map[string]any{"machines": store.ListMachines(time.Now().UTC())})
	})

	type machineInfoArgs struct {
		MachineID string `json:"machine_id" jsonschema:"Registered machine ID"`
	}
	mcp.AddTool(server, &mcp.Tool{
		Name: "machine_info", Description: "Show one machine's platform, default cwd, heartbeat, and online state.",
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
		WaitMS         int               `json:"wait_ms,omitempty" jsonschema:"Wait up to 15000 ms for initial progress; default 1500"`
	}
	mcp.AddTool(server, &mcp.Tool{
		Name: "command_start", Description: "Queue a shell command and return a durable task ID; long commands never hold the MCP request open indefinitely.",
		Annotations: &mcp.ToolAnnotations{ReadOnlyHint: false, DestructiveHint: &destructive, OpenWorldHint: &openWorld},
	}, func(ctx context.Context, _ *mcp.CallToolRequest, args commandStartArgs) (*mcp.CallToolResult, any, error) {
		waitMS := args.WaitMS
		if waitMS == 0 {
			waitMS = 1500
		}
		if waitMS < 0 || waitMS > 15000 {
			return nil, nil, fmt.Errorf("wait_ms must be between 0 and 15000")
		}
		task, err := store.CreateTask(protocol.CreateTaskRequest{
			MachineID: args.MachineID, Command: args.Command, CWD: args.CWD, Env: args.Env, TimeoutSeconds: args.TimeoutSeconds,
		})
		if err != nil {
			return nil, nil, err
		}
		task = waitForTask(ctx, store, task.ID, 0, time.Duration(waitMS)*time.Millisecond)
		return taskResult(store, task, 0, 16*1024)
	})

	type taskWaitArgs struct {
		TaskID string `json:"task_id" jsonschema:"Task ID returned by command_start"`
		Cursor int64  `json:"cursor,omitempty" jsonschema:"Known output byte cursor"`
		WaitMS int    `json:"wait_ms,omitempty" jsonschema:"Long-poll wait up to 20000 ms; default 15000"`
	}
	mcp.AddTool(server, &mcp.Tool{
		Name: "task_wait", Description: "Wait for task state or output progress and return the next bounded output page.",
		Annotations: &mcp.ToolAnnotations{ReadOnlyHint: true, OpenWorldHint: &closedWorld},
	}, func(ctx context.Context, _ *mcp.CallToolRequest, args taskWaitArgs) (*mcp.CallToolResult, any, error) {
		task, ok := store.GetTask(strings.TrimSpace(args.TaskID))
		if !ok {
			return nil, nil, fmt.Errorf("task %s not found", args.TaskID)
		}
		waitMS := args.WaitMS
		if waitMS == 0 {
			waitMS = 15000
		}
		if waitMS < 0 || waitMS > 20000 {
			return nil, nil, fmt.Errorf("wait_ms must be between 0 and 20000")
		}
		if task.OutputBytes <= args.Cursor && !terminalStatus(task.Status) {
			task = waitForTask(ctx, store, task.ID, args.Cursor, time.Duration(waitMS)*time.Millisecond)
		}
		return taskResult(store, task, args.Cursor, 32*1024)
	})

	type taskOutputArgs struct {
		TaskID string `json:"task_id" jsonschema:"Task ID"`
		Cursor int64  `json:"cursor,omitempty" jsonschema:"Output byte cursor"`
		Limit  int    `json:"limit,omitempty" jsonschema:"Maximum output bytes; default 32768, max 1048576"`
	}
	mcp.AddTool(server, &mcp.Tool{
		Name: "task_output", Description: "Read persisted task output from a byte cursor without loading the entire log.",
		Annotations: &mcp.ToolAnnotations{ReadOnlyHint: true, OpenWorldHint: &closedWorld},
	}, func(_ context.Context, _ *mcp.CallToolRequest, args taskOutputArgs) (*mcp.CallToolResult, any, error) {
		task, ok := store.GetTask(strings.TrimSpace(args.TaskID))
		if !ok {
			return nil, nil, fmt.Errorf("task %s not found", args.TaskID)
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
		return jsonToolResult(task)
	})
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
		changed := store.Changed()
		select {
		case <-ctx.Done():
			return task
		case <-deadline.C:
			return task
		case <-changed:
		}
	}
}

func taskResult(store *Store, task Task, cursor int64, limit int) (*mcp.CallToolResult, any, error) {
	data, next, more, err := store.ReadOutput(task.ID, cursor, limit)
	if err != nil {
		return nil, nil, err
	}
	return jsonToolResult(map[string]any{
		"task":        task,
		"output":      map[string]any{"text": string(data), "cursor": cursor, "next_cursor": next, "more": more},
		"next_action": nextAction(task, more),
	})
}

func nextAction(task Task, more bool) string {
	if more {
		return "call task_output with next_cursor"
	}
	if terminalStatus(task.Status) {
		return "task is finished"
	}
	return "call task_wait with next_cursor"
}

func terminalStatus(status string) bool {
	return status == protocol.TaskCompleted || status == protocol.TaskFailed || status == protocol.TaskCanceled
}

func jsonToolResult(value any) (*mcp.CallToolResult, any, error) {
	data, err := json.Marshal(value)
	if err != nil {
		return nil, nil, err
	}
	return &mcp.CallToolResult{Content: []mcp.Content{&mcp.TextContent{Text: string(data)}}}, nil, nil
}
