package tools

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"time"

	"github.com/modelcontextprotocol/go-sdk/mcp"

	processes "github.com/Prodigalgal/remote_connect_mcp/internal/process"
)

type Service struct {
	DefaultCWD string
	Version   string
	Processes *processes.Manager
	Logger    *slog.Logger
}

func (s *Service) Register(server *mcp.Server) {
	closedWorld := false
	openWorld := true
	destructive := true

	mcp.AddTool(server, &mcp.Tool{Name: "server_info", Description: "Show server, platform, default cwd, and paging details. Authenticated calls can access the whole machine.", Annotations: &mcp.ToolAnnotations{ReadOnlyHint: true, OpenWorldHint: &closedWorld}}, s.serverInfo)
	mcp.AddTool(server, &mcp.Tool{Name: "list_files", Description: "List files and directories with compact pagination.", Annotations: &mcp.ToolAnnotations{ReadOnlyHint: true, OpenWorldHint: &closedWorld}}, s.listFiles)
	mcp.AddTool(server, &mcp.Tool{Name: "search_text", Description: "Search file text using literal or regular-expression matching.", Annotations: &mcp.ToolAnnotations{ReadOnlyHint: true, OpenWorldHint: &closedWorld}}, s.searchText)
	mcp.AddTool(server, &mcp.Tool{Name: "read_file", Description: "Read text or bytes from any local path in bounded pages.", Annotations: &mcp.ToolAnnotations{ReadOnlyHint: true, OpenWorldHint: &closedWorld}}, s.readFile)
	mcp.AddTool(server, &mcp.Tool{Name: "apply_patch", Description: "Atomically add, update, move, or delete files using patch text.", Annotations: &mcp.ToolAnnotations{DestructiveHint: &destructive, ReadOnlyHint: false, OpenWorldHint: &closedWorld}}, s.applyPatch)
	mcp.AddTool(server, &mcp.Tool{Name: "exec_command", Description: "Run any shell command in foreground or background.", Annotations: &mcp.ToolAnnotations{DestructiveHint: &destructive, ReadOnlyHint: false, OpenWorldHint: &openWorld}}, s.execCommand)
	mcp.AddTool(server, &mcp.Tool{Name: "process", Description: "List, inspect, read, write, or stop jobs; read paged outputs.", Annotations: &mcp.ToolAnnotations{DestructiveHint: &destructive, ReadOnlyHint: false, OpenWorldHint: &openWorld}}, s.process)
	mcp.AddTool(server, &mcp.Tool{Name: "view_image", Description: "Return a local image for visual inspection.", Annotations: &mcp.ToolAnnotations{ReadOnlyHint: true, OpenWorldHint: &closedWorld}}, s.viewImage)
}

type serverInfoArgs struct{}

func (s *Service) serverInfo(ctx context.Context, _ *mcp.CallToolRequest, _ serverInfoArgs) (*mcp.CallToolResult, any, error) {
	start := time.Now()
	result := map[string]any{
		"name": "remote_connect_mcp", "version": s.Version, "os": runtime.GOOS, "arch": runtime.GOARCH,
		"default_cwd": s.DefaultCWD, "shell": shellName(), "tools": 8,
		"scope": "authenticated calls may access any local path and run any command; default_cwd is not an access boundary",
	}
	s.log("server_info", start, nil)
	return jsonResult(result)
}

func (s *Service) resolvePath(path string) (string, error) {
	path = strings.TrimSpace(path)
	if path == "" || path == "." {
		return s.DefaultCWD, nil
	}
	if strings.HasPrefix(path, "~"+string(filepath.Separator)) || path == "~" {
		home, err := os.UserHomeDir()
		if err != nil {
			return "", err
		}
		path = filepath.Join(home, strings.TrimPrefix(strings.TrimPrefix(path, "~"), string(filepath.Separator)))
	}
	if !filepath.IsAbs(path) {
		path = filepath.Join(s.DefaultCWD, path)
	}
	return filepath.Clean(path), nil
}

func (s *Service) log(name string, start time.Time, err error) {
	status := "ok"
	if err != nil {
		status = "error"
	}
	s.Logger.Info("mcp tool", "tool", name, "status", status, "duration_ms", time.Since(start).Milliseconds())
}

func jsonResult(value any) (*mcp.CallToolResult, any, error) {
	data, err := json.Marshal(value)
	if err != nil {
		return nil, nil, err
	}
	return &mcp.CallToolResult{Content: []mcp.Content{&mcp.TextContent{Text: string(data)}}}, nil, nil
}

func fail(tool string, s *Service, start time.Time, err error) (*mcp.CallToolResult, any, error) {
	s.log(tool, start, err)
	return nil, nil, err
}

func shellName() string {
	if runtime.GOOS == "windows" {
		if path, err := exec.LookPath("pwsh.exe"); err == nil {
			return path
		}
		return "cmd.exe"
	}
	return "/bin/sh"
}

func parseCursor(cursor string) (int, error) {
	if cursor == "" {
		return 0, nil
	}
	var offset int
	if _, err := fmt.Sscanf(cursor, "%d", &offset); err != nil || offset < 0 {
		return 0, fmt.Errorf("invalid cursor")
	}
	return offset, nil
}
