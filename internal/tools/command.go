package tools

import (
	"context"
	"fmt"
	"time"

	"github.com/modelcontextprotocol/go-sdk/mcp"
)

type execCommandArgs struct {
	Command        string            `json:"command" jsonschema:"Shell command to run"`
	Cwd            string            `json:"cwd,omitempty" jsonschema:"Working directory; default current directory"`
	Env            map[string]string `json:"env,omitempty" jsonschema:"Environment variables to add or override"`
	Background     bool              `json:"background,omitempty" jsonschema:"Return immediately with a process ID"`
	TimeoutSeconds *int              `json:"timeout_seconds,omitempty" jsonschema:"Foreground timeout; default 120; 0 means unlimited"`
}

func (s *Service) execCommand(ctx context.Context, _ *mcp.CallToolRequest, args execCommandArgs) (*mcp.CallToolResult, any, error) {
	start := time.Now()
	cwd, err := s.resolvePath(args.Cwd)
	if err != nil {
		return fail("exec_command", s, start, err)
	}
	runCtx := context.Background()
	cancel := func() {}
	if !args.Background {
		timeout := 120
		if args.TimeoutSeconds != nil {
			timeout = *args.TimeoutSeconds
		}
		if timeout < 0 {
			return fail("exec_command", s, start, fmt.Errorf("timeout_seconds cannot be negative"))
		}
		if timeout > 0 {
			runCtx, cancel = context.WithTimeout(ctx, time.Duration(timeout)*time.Second)
		} else {
			runCtx, cancel = context.WithCancel(ctx)
		}
	}
	defer cancel()
	entry, err := s.Processes.Start(runCtx, args.Command, cwd, args.Env)
	if err != nil {
		return fail("exec_command", s, start, err)
	}
	if args.Background {
		s.log("exec_command", start, nil)
		return jsonResult(map[string]any{"process_id": entry.ID, "running": true, "cwd": cwd})
	}
	entry.Wait()
	info := entry.Info()
	data, next, more, err := entry.Output(0, 32*1024)
	if err != nil {
		return fail("exec_command", s, start, err)
	}
	result := map[string]any{
		"process_id": entry.ID, "running": false, "exit_code": info.ExitCode,
		"output": map[string]any{"text": string(data), "next_offset": next, "more": more, "total_bytes": info.Bytes},
	}
	if info.Error != "" {
		result["error"] = info.Error
	}
	s.log("exec_command", start, nil)
	return jsonResult(result)
}

type processArgs struct {
	Action string `json:"action" jsonschema:"list, status, read, write, close_stdin, or stop"`
	ID     string `json:"id,omitempty" jsonschema:"Process ID"`
	Offset int    `json:"offset,omitempty" jsonschema:"Process output byte offset"`
	Limit  int    `json:"limit,omitempty" jsonschema:"Maximum bytes; default 32768"`
	Text   string `json:"text,omitempty" jsonschema:"Text to write to stdin"`
}

func (s *Service) process(ctx context.Context, _ *mcp.CallToolRequest, args processArgs) (*mcp.CallToolResult, any, error) {
	start := time.Now()
	switch args.Action {
	case "list":
		s.log("process", start, nil)
		return jsonResult(map[string]any{"processes": s.Processes.List()})
	case "status":
		entry, err := s.Processes.Get(args.ID)
		if err != nil {
			return fail("process", s, start, err)
		}
		s.log("process", start, nil)
		return jsonResult(entry.Info())
	case "read":
		entry, err := s.Processes.Get(args.ID)
		if err != nil {
			return fail("process", s, start, err)
		}
		limit := args.Limit
		if limit <= 0 {
			limit = 32 * 1024
		}
		limit = min(limit, 1024*1024)
		data, next, more, err := entry.Output(args.Offset, limit)
		if err != nil {
			return fail("process", s, start, err)
		}
		result := map[string]any{"process": entry.Info(), "text": string(data), "next_offset": next, "more": more}
		s.log("process", start, nil)
		return jsonResult(result)
	case "write":
		entry, err := s.Processes.Get(args.ID)
		if err != nil {
			return fail("process", s, start, err)
		}
		if err := entry.Write(args.Text); err != nil {
			return fail("process", s, start, err)
		}
		s.log("process", start, nil)
		return jsonResult(map[string]any{"process_id": args.ID, "written_bytes": len(args.Text)})
	case "close_stdin":
		entry, err := s.Processes.Get(args.ID)
		if err != nil {
			return fail("process", s, start, err)
		}
		if err := entry.CloseInput(); err != nil {
			return fail("process", s, start, err)
		}
		s.log("process", start, nil)
		return jsonResult(map[string]any{"process_id": args.ID, "stdin_closed": true})
	case "stop":
		entry, err := s.Processes.Get(args.ID)
		if err != nil {
			return fail("process", s, start, err)
		}
		if err := entry.Kill(); err != nil {
			return fail("process", s, start, err)
		}
		s.log("process", start, nil)
		return jsonResult(map[string]any{"process_id": args.ID, "stopped": true})
	default:
		return fail("process", s, start, fmt.Errorf("action must be list, status, read, write, close_stdin, or stop"))
	}
}
