package tools

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"

	"github.com/modelcontextprotocol/go-sdk/mcp"

	remoteauth "github.com/Prodigalgal/remote_connect_mcp/internal/auth"
	processes "github.com/Prodigalgal/remote_connect_mcp/internal/process"
)

type bearerRoundTripper struct {
	token string
	base  http.RoundTripper
}

func (t bearerRoundTripper) RoundTrip(request *http.Request) (*http.Response, error) {
	clone := request.Clone(request.Context())
	clone.Header = request.Header.Clone()
	clone.Header.Set("Authorization", "Bearer "+t.token)
	return t.base.RoundTrip(clone)
}

func TestStreamableHTTPToolSurface(t *testing.T) {
	workspace := t.TempDir()
	if err := os.WriteFile(filepath.Join(workspace, "hello.txt"), []byte("alpha\nbeta\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	image, err := base64.StdEncoding.DecodeString("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl2nH0AAAAASUVORK5CYII=")
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(workspace, "pixel.png"), image, 0o644); err != nil {
		t.Fatal(err)
	}
	processManager := processes.NewManager(t.TempDir())
	defer processManager.Close()
	server := mcp.NewServer(&mcp.Implementation{Name: "test", Version: "test"}, nil)
	service := &Service{
		DefaultCWD: workspace, Version: "test", Processes: processManager,
		Logger: slog.New(slog.NewTextHandler(io.Discard, nil)),
	}
	service.Register(server)
	mcpHandler := mcp.NewStreamableHTTPHandler(func(*http.Request) *mcp.Server { return server }, &mcp.StreamableHTTPOptions{Stateless: true})
	authenticatedMCP := remoteauth.Bearer("test-token", mcpHandler)
	mux := http.NewServeMux()
	mux.Handle("/mcp", authenticatedMCP)
	mux.Handle("/", authenticatedMCP)
	httpServer := httptest.NewServer(mux)
	defer httpServer.Close()

	response, err := http.Post(httpServer.URL, "application/json", strings.NewReader("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"))
	if err != nil {
		t.Fatal(err)
	}
	_ = response.Body.Close()
	if response.StatusCode != http.StatusUnauthorized {
		t.Fatalf("unauthenticated status = %d", response.StatusCode)
	}

	client := mcp.NewClient(&mcp.Implementation{Name: "test-client", Version: "test"}, nil)
	httpClient := &http.Client{Transport: bearerRoundTripper{token: "test-token", base: http.DefaultTransport}}
	session, err := client.Connect(context.Background(), &mcp.StreamableClientTransport{
		Endpoint: httpServer.URL + "/", HTTPClient: httpClient, DisableStandaloneSSE: true,
	}, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer session.Close()
	if initialized := session.InitializeResult(); initialized == nil || initialized.ProtocolVersion != "2026-07-28" {
		t.Fatalf("protocol version = %v, want 2026-07-28", initialized)
	}

	listed, err := session.ListTools(context.Background(), nil)
	if err != nil {
		t.Fatal(err)
	}
	if len(listed.Tools) != 8 {
		t.Fatalf("tool count = %d, want 8", len(listed.Tools))
	}
	metadata, err := json.Marshal(listed)
	if err != nil {
		t.Fatal(err)
	}
	if len(metadata) > 12*1024 {
		t.Fatalf("tool metadata = %d bytes, want <= 12288", len(metadata))
	}
	t.Logf("tool metadata: %d bytes", len(metadata))

	call := func(name string, arguments map[string]any) *mcp.CallToolResult {
		t.Helper()
		result, err := session.CallTool(context.Background(), &mcp.CallToolParams{Name: name, Arguments: arguments})
		if err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		if result.IsError {
			t.Fatalf("%s returned error: %+v", name, result.Content)
		}
		return result
	}
	infoResult := call("server_info", map[string]any{})
	var info map[string]any
	if err := json.Unmarshal([]byte(infoResult.Content[0].(*mcp.TextContent).Text), &info); err != nil {
		t.Fatal(err)
	}
	if info["default_cwd"] != workspace {
		t.Fatalf("default_cwd = %v, want %s", info["default_cwd"], workspace)
	}
	if !strings.Contains(info["scope"].(string), "any local path") {
		t.Fatalf("scope does not describe full-machine access: %v", info["scope"])
	}
	call("list_files", map[string]any{"path": ".", "depth": 1})
	call("search_text", map[string]any{"query": "beta", "path": "."})
	call("read_file", map[string]any{"path": "hello.txt"})
	call("read_file", map[string]any{"path": filepath.Join(workspace, "hello.txt")})
	call("apply_patch", map[string]any{"patch": "*** Begin Patch\n*** Update File: hello.txt\n@@\n alpha\n-beta\n+gamma\n*** End Patch"})
	data, err := os.ReadFile(filepath.Join(workspace, "hello.txt"))
	if err != nil {
		t.Fatal(err)
	}
	if string(data) != "alpha\ngamma\n" {
		t.Fatalf("patched data = %q", data)
	}
	command := "echo protocol-ok"
	if runtime.GOOS != "windows" {
		command = "printf protocol-ok"
	}
	execResult := call("exec_command", map[string]any{"command": command})
	execText := execResult.Content[0].(*mcp.TextContent).Text
	if !strings.Contains(execText, "protocol-ok") {
		t.Fatalf("exec output = %s", execText)
	}
	call("process", map[string]any{"action": "list"})
	imageResult := call("view_image", map[string]any{"path": "pixel.png"})
	if len(imageResult.Content) != 1 {
		t.Fatalf("image content count = %d", len(imageResult.Content))
	}
	if _, ok := imageResult.Content[0].(*mcp.ImageContent); !ok {
		t.Fatalf("image content type = %T", imageResult.Content[0])
	}
}
