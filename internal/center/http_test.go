package center

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/modelcontextprotocol/go-sdk/mcp"

	"github.com/Prodigalgal/remote_connect_mcp/internal/protocol"
)

type bearerTransport struct {
	token string
	base  http.RoundTripper
}

func (t bearerTransport) RoundTrip(request *http.Request) (*http.Response, error) {
	clone := request.Clone(request.Context())
	clone.Header = request.Header.Clone()
	clone.Header.Set("Authorization", "Bearer "+t.token)
	return t.base.RoundTrip(clone)
}

func TestHTTPRegistrationAdminAndMCP(t *testing.T) {
	store, err := OpenStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	handler, err := NewHTTPHandler(store, HTTPConfig{
		Version: "test", MCPToken: "mcp-secret", AdminToken: "admin-secret", EnrollmentToken: "enroll-secret",
		Logger: slog.New(slog.NewTextHandler(io.Discard, nil)),
	})
	if err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(handler)
	defer server.Close()

	registerBody, _ := json.Marshal(protocol.RegisterRequest{Name: "machine-a", OS: "linux", Arch: "arm64"})
	registerRequest, _ := http.NewRequest(http.MethodPost, server.URL+"/agent/v1/register", bytes.NewReader(registerBody))
	registerRequest.Header.Set("Authorization", "Bearer enroll-secret")
	registerRequest.Header.Set("Content-Type", "application/json")
	registerResponse, err := http.DefaultClient.Do(registerRequest)
	if err != nil {
		t.Fatal(err)
	}
	defer registerResponse.Body.Close()
	if registerResponse.StatusCode != http.StatusCreated {
		t.Fatalf("register status = %d", registerResponse.StatusCode)
	}
	var registered protocol.RegisterResponse
	if err := json.NewDecoder(registerResponse.Body).Decode(&registered); err != nil {
		t.Fatal(err)
	}
	adminMachines := func() []MachineView {
		request, _ := http.NewRequest(http.MethodGet, server.URL+"/api/v1/machines", nil)
		request.Header.Set("Authorization", "Bearer admin-secret")
		response, err := http.DefaultClient.Do(request)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		var body struct {
			Machines []MachineView `json:"machines"`
		}
		if err := json.NewDecoder(response.Body).Decode(&body); err != nil {
			t.Fatal(err)
		}
		return body.Machines
	}
	if machines := adminMachines(); len(machines) != 1 || machines[0].ScopeMode != "unrestricted" {
		t.Fatalf("registered machine scope = %+v", machines)
	}

	unauthorized, err := http.Get(server.URL + "/api/v1/machines")
	if err != nil {
		t.Fatal(err)
	}
	unauthorized.Body.Close()
	if unauthorized.StatusCode != http.StatusUnauthorized {
		t.Fatalf("unauthorized admin status = %d", unauthorized.StatusCode)
	}

	client := mcp.NewClient(&mcp.Implementation{Name: "test-client", Version: "test"}, nil)
	httpClient := &http.Client{Transport: bearerTransport{token: "mcp-secret", base: http.DefaultTransport}}
	session, err := client.Connect(context.Background(), &mcp.StreamableClientTransport{
		Endpoint: server.URL + "/", HTTPClient: httpClient, DisableStandaloneSSE: true,
	}, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer session.Close()
	if initialized := session.InitializeResult(); initialized == nil || initialized.ProtocolVersion != "2026-07-28" {
		t.Fatalf("protocol result = %+v", initialized)
	}
	tools, err := session.ListTools(context.Background(), nil)
	if err != nil {
		t.Fatal(err)
	}
	requiredTools := map[string]bool{"machines_list": true, "machine_info": true, "command_start": true, "task_wait": true, "task_output": true, "task_cancel": true, "desktop": true}
	for _, tool := range tools.Tools {
		delete(requiredTools, tool.Name)
	}
	if len(requiredTools) != 0 {
		t.Fatalf("missing core tools: %+v (reported %d tools)", requiredTools, len(tools.Tools))
	}
	result, err := session.CallTool(context.Background(), &mcp.CallToolParams{Name: "machines_list", Arguments: map[string]any{}})
	if err != nil || result.IsError {
		t.Fatalf("machines_list result=%+v err=%v", result, err)
	}
	if len(result.Content) != 1 {
		t.Fatalf("machines_list content = %+v", result.Content)
	}

	start := time.Now()
	result, err = session.CallTool(context.Background(), &mcp.CallToolParams{Name: "command_start", Arguments: map[string]any{
		"machine_id": registered.MachineID, "command": "echo detached", "env": map[string]any{"SECRET": "should-not-return"}, "idempotency_key": "http-test-command-1",
	}})
	if err != nil || result.IsError {
		t.Fatalf("command_start result=%+v err=%v", result, err)
	}
	if content, ok := result.Content[0].(*mcp.TextContent); ok && strings.Contains(content.Text, "should-not-return") {
		t.Fatalf("command_start echoed an environment secret: %s", content.Text)
	}
	if elapsed := time.Since(start); elapsed >= time.Second {
		t.Fatalf("command_start blocked for %s, want less than 1s", elapsed)
	}
	tasks := store.ListTasks(10)
	if len(tasks) != 1 {
		t.Fatalf("task count = %d, want 1", len(tasks))
	}
	result, err = session.CallTool(context.Background(), &mcp.CallToolParams{Name: "command_start", Arguments: map[string]any{
		"machine_id": registered.MachineID, "command": "echo detached", "env": map[string]any{"SECRET": "should-not-return"}, "idempotency_key": "http-test-command-1",
	}})
	if err != nil || result.IsError {
		t.Fatalf("idempotent command_start retry result=%+v err=%v", result, err)
	}
	if tasks = store.ListTasks(10); len(tasks) != 1 {
		t.Fatalf("idempotent MCP retry left %d tasks, want 1", len(tasks))
	}

	start = time.Now()
	result, err = session.CallTool(context.Background(), &mcp.CallToolParams{Name: "task_wait", Arguments: map[string]any{
		"task_id": tasks[0].ID,
	}})
	if err != nil || result.IsError {
		t.Fatalf("task_wait result=%+v err=%v", result, err)
	}
	if elapsed := time.Since(start); elapsed >= time.Second {
		t.Fatalf("default task_wait blocked for %s, want less than 1s", elapsed)
	}
}

func TestHTTPRegistrationAcceptsNewMetadataInOptionalHeader(t *testing.T) {
	store, err := OpenStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	handler, err := NewHTTPHandler(store, HTTPConfig{
		Version: "test", MCPToken: "mcp-secret", AdminToken: "admin-secret", EnrollmentToken: "enroll-secret",
		Logger: slog.New(slog.NewTextHandler(io.Discard, nil)),
	})
	if err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(handler)
	defer server.Close()

	body, _ := json.Marshal(protocol.RegisterRequest{Name: "header-workspace", OS: "linux", Arch: "amd64", DefaultCWD: "/srv/project"})
	metadata, _ := json.Marshal(protocol.AgentMetadata{
		ScopeMode: protocol.ScopeModeWorkspace, WorkspaceRoot: "/srv/project", DefaultCWD: "/srv/project", Capabilities: []string{"command", "workspace-policy"},
	})
	request, _ := http.NewRequest(http.MethodPost, server.URL+"/agent/v1/register", bytes.NewReader(body))
	request.Header.Set("Authorization", "Bearer enroll-secret")
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("X-Agent-Metadata", base64.RawURLEncoding.EncodeToString(metadata))
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != http.StatusCreated {
		t.Fatalf("registration status = %d", response.StatusCode)
	}
	machines := store.ListMachines(time.Now().UTC())
	if len(machines) != 1 || machines[0].ScopeMode != protocol.ScopeModeWorkspace || machines[0].WorkspaceRoot != "/srv/project" {
		t.Fatalf("header metadata was not applied: %+v", machines)
	}
}

func TestHTTPTaskStateAcceptsTruncationHeader(t *testing.T) {
	store, err := OpenStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	handler, err := NewHTTPHandler(store, HTTPConfig{
		Version: "test", MCPToken: "mcp-secret", AdminToken: "admin-secret", EnrollmentToken: "enroll-secret",
		Logger: slog.New(slog.NewTextHandler(io.Discard, nil)),
	})
	if err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(handler)
	defer server.Close()
	registered, err := store.Register(protocol.RegisterRequest{Name: "truncated-agent", OS: "linux", Arch: "amd64"})
	if err != nil {
		t.Fatal(err)
	}
	task, err := store.CreateTask(protocol.CreateTaskRequest{MachineID: registered.MachineID, Command: "echo output"})
	if err != nil {
		t.Fatal(err)
	}
	body := bytes.NewBufferString(`{"status":"completed"}`)
	request, _ := http.NewRequest(http.MethodPost, server.URL+"/agent/v1/tasks/"+task.ID+"/state", body)
	request.Header.Set("Authorization", "Bearer "+registered.Token)
	request.Header.Set("X-Machine-ID", registered.MachineID)
	request.Header.Set("X-Task-Output-Truncated", "1")
	request.Header.Set("Content-Type", "application/json")
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != http.StatusOK {
		t.Fatalf("task state status = %d", response.StatusCode)
	}
	updated, _ := store.GetTask(task.ID)
	if !updated.OutputTruncated {
		t.Fatalf("truncation header was not persisted: %+v", updated)
	}
}

func TestHTTPMetricsRequiresAdminAndRedactsTaskData(t *testing.T) {
	store, err := OpenStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	handler, err := NewHTTPHandler(store, HTTPConfig{
		Version: "test", MCPToken: "mcp-secret", AdminToken: "admin-secret", EnrollmentToken: "enroll-secret",
		Logger: slog.New(slog.NewTextHandler(io.Discard, nil)),
	})
	if err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(handler)
	defer server.Close()

	unauthorized, err := http.Get(server.URL + "/metrics")
	if err != nil {
		t.Fatal(err)
	}
	unauthorized.Body.Close()
	if unauthorized.StatusCode != http.StatusUnauthorized {
		t.Fatalf("unauthorized metrics status = %d", unauthorized.StatusCode)
	}

	request, _ := http.NewRequest(http.MethodGet, server.URL+"/metrics", nil)
	request.Header.Set("Authorization", "Bearer admin-secret")
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	data, _ := io.ReadAll(response.Body)
	if response.StatusCode != http.StatusOK || !strings.Contains(string(data), "remote_connect_mcp_machines_total") {
		t.Fatalf("metrics status=%d body=%q", response.StatusCode, data)
	}
	if strings.Contains(string(data), "admin-secret") || strings.Contains(string(data), "command") {
		t.Fatalf("metrics leaked sensitive data: %q", data)
	}
}

func TestHTTPAdminTokenRotation(t *testing.T) {
	store, err := OpenStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	handler, err := NewHTTPHandler(store, HTTPConfig{
		Version: "test", MCPToken: "mcp-secret", AdminToken: "admin-secret", EnrollmentToken: "enroll-secret",
		Logger: slog.New(slog.NewTextHandler(io.Discard, nil)),
	})
	if err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(handler)
	defer server.Close()

	newToken := strings.Repeat("n", 40)
	body, _ := json.Marshal(map[string]any{"new_token": newToken, "grace_seconds": 0})
	request, _ := http.NewRequest(http.MethodPost, server.URL+"/api/v1/tokens/admin/rotate", bytes.NewReader(body))
	request.Header.Set("Authorization", "Bearer admin-secret")
	request.Header.Set("Content-Type", "application/json")
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != http.StatusOK {
		t.Fatalf("rotate status = %d", response.StatusCode)
	}

	oldRequest, _ := http.NewRequest(http.MethodGet, server.URL+"/api/v1/tokens", nil)
	oldRequest.Header.Set("Authorization", "Bearer admin-secret")
	oldResponse, err := http.DefaultClient.Do(oldRequest)
	if err != nil {
		t.Fatal(err)
	}
	oldResponse.Body.Close()
	if oldResponse.StatusCode != http.StatusUnauthorized {
		t.Fatalf("old admin status = %d, want 401", oldResponse.StatusCode)
	}

	newRequest, _ := http.NewRequest(http.MethodGet, server.URL+"/api/v1/tokens", nil)
	newRequest.Header.Set("Authorization", "Bearer "+newToken)
	newResponse, err := http.DefaultClient.Do(newRequest)
	if err != nil {
		t.Fatal(err)
	}
	defer newResponse.Body.Close()
	if newResponse.StatusCode != http.StatusOK {
		t.Fatalf("new admin status = %d, want 200", newResponse.StatusCode)
	}
	var payload struct {
		Tokens []AccessTokenView `json:"tokens"`
	}
	if err := json.NewDecoder(newResponse.Body).Decode(&payload); err != nil || len(payload.Tokens) != 3 {
		t.Fatalf("token payload=%+v err=%v", payload, err)
	}
}

func TestHTTPScopedEnrollmentTokenIsReturnedOnceAndConsumed(t *testing.T) {
	store, err := OpenStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	handler, err := NewHTTPHandler(store, HTTPConfig{
		Version: "test", MCPToken: "mcp-secret", AdminToken: "admin-secret", EnrollmentToken: "enroll-secret",
		Logger: slog.New(slog.NewTextHandler(io.Discard, nil)),
	})
	if err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(handler)
	defer server.Close()

	legacyBody, _ := json.Marshal(map[string]any{"name": "machine-legacy", "persistent": true})
	legacyRequest, _ := http.NewRequest(http.MethodPost, server.URL+"/api/v1/enrollment-tokens", bytes.NewReader(legacyBody))
	legacyRequest.Header.Set("Authorization", "Bearer admin-secret")
	legacyRequest.Header.Set("Content-Type", "application/json")
	legacyResponse, err := http.DefaultClient.Do(legacyRequest)
	if err != nil {
		t.Fatal(err)
	}
	legacyResponse.Body.Close()
	if legacyResponse.StatusCode != http.StatusBadRequest {
		t.Fatalf("legacy persistent token status = %d, want %d", legacyResponse.StatusCode, http.StatusBadRequest)
	}

	createBody, _ := json.Marshal(map[string]any{"name": "machine-scoped", "expires_seconds": 3600})
	createRequest, _ := http.NewRequest(http.MethodPost, server.URL+"/api/v1/enrollment-tokens", bytes.NewReader(createBody))
	createRequest.Header.Set("Authorization", "Bearer admin-secret")
	createRequest.Header.Set("Content-Type", "application/json")
	createResponse, err := http.DefaultClient.Do(createRequest)
	if err != nil {
		t.Fatal(err)
	}
	defer createResponse.Body.Close()
	var created struct {
		Enrollment EnrollmentTokenView `json:"enrollment"`
		Token      string              `json:"token"`
	}
	if err := json.NewDecoder(createResponse.Body).Decode(&created); err != nil || createResponse.StatusCode != http.StatusCreated || created.Token == "" {
		t.Fatalf("created=%+v status=%d err=%v", created, createResponse.StatusCode, err)
	}

	registerBody, _ := json.Marshal(protocol.RegisterRequest{Name: "machine-scoped", OS: "linux", Arch: "amd64"})
	registerRequest, _ := http.NewRequest(http.MethodPost, server.URL+"/agent/v1/register", bytes.NewReader(registerBody))
	registerRequest.Header.Set("Authorization", "Bearer "+created.Token)
	registerRequest.Header.Set("Content-Type", "application/json")
	registerResponse, err := http.DefaultClient.Do(registerRequest)
	if err != nil {
		t.Fatal(err)
	}
	registerResponse.Body.Close()
	if registerResponse.StatusCode != http.StatusCreated {
		t.Fatalf("register status = %d", registerResponse.StatusCode)
	}

	listRequest, _ := http.NewRequest(http.MethodGet, server.URL+"/api/v1/enrollment-tokens", nil)
	listRequest.Header.Set("Authorization", "Bearer admin-secret")
	listResponse, err := http.DefaultClient.Do(listRequest)
	if err != nil {
		t.Fatal(err)
	}
	defer listResponse.Body.Close()
	data, err := io.ReadAll(listResponse.Body)
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Contains(data, []byte(created.Token)) || bytes.Contains(data, []byte("token_hash")) || bytes.Contains(data, []byte("persistent")) {
		t.Fatal("enrollment list leaked token material")
	}
	var listed struct {
		EnrollmentTokens []EnrollmentTokenView `json:"enrollment_tokens"`
	}
	if err := json.Unmarshal(data, &listed); err != nil || len(listed.EnrollmentTokens) != 1 || listed.EnrollmentTokens[0].Status != "used" {
		t.Fatalf("listed=%+v err=%v", listed, err)
	}
}

func TestUpgradeCampaignCachesArtifactAtCenter(t *testing.T) {
	payload := []byte("signed agent release payload")
	digest := sha256.Sum256(payload)
	checksum := fmt.Sprintf("%x", digest[:])
	release := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/v2.0.0/remote-connect-mcp-agent-v2.0.0-linux-amd64.sha256" {
			_, _ = fmt.Fprintf(w, "%s  artifact\n", checksum)
			return
		}
		if r.URL.Path == "/v2.0.0/remote-connect-mcp-agent-v2.0.0-linux-amd64" {
			_, _ = w.Write(payload)
			return
		}
		http.NotFound(w, r)
	}))
	defer release.Close()

	store, err := OpenStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	machine, err := store.Register(protocol.RegisterRequest{Name: "cache-test", OS: "linux", Arch: "amd64", Version: "v1.0.0"})
	if err != nil {
		t.Fatal(err)
	}
	handler, err := NewHTTPHandler(store, HTTPConfig{
		Version: "test", MCPToken: "mcp", AdminToken: "admin", EnrollmentToken: "enroll",
		ReleaseBaseURL: release.URL, AgentPublicURL: "https://agents.example.test",
		Logger: slog.New(slog.NewTextHandler(io.Discard, nil)),
	})
	if err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(handler)
	defer server.Close()

	body, _ := json.Marshal(map[string]any{
		"version": "v2.0.0", "canary_count": 1, "batch_size": 1, "machine_ids": []string{machine.MachineID},
	})
	request, _ := http.NewRequest(http.MethodPost, server.URL+"/api/v1/upgrades", bytes.NewReader(body))
	request.Header.Set("Authorization", "Bearer admin")
	request.Header.Set("Content-Type", "application/json")
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusCreated {
		data, _ := io.ReadAll(response.Body)
		t.Fatalf("create campaign status=%d body=%s", response.StatusCode, data)
	}
	var campaign UpgradeCampaign
	if err := json.NewDecoder(response.Body).Decode(&campaign); err != nil {
		t.Fatal(err)
	}
	if got := campaign.Artifacts["linux/amd64"].URL; got != "https://agents.example.test/agent-artifacts/v2.0.0/remote-connect-mcp-agent-v2.0.0-linux-amd64" {
		t.Fatalf("cached artifact URL = %s", got)
	}

	artifactResponse, err := http.Get(server.URL + "/agent-artifacts/v2.0.0/remote-connect-mcp-agent-v2.0.0-linux-amd64")
	if err != nil {
		t.Fatal(err)
	}
	defer artifactResponse.Body.Close()
	data, err := io.ReadAll(artifactResponse.Body)
	if err != nil {
		t.Fatal(err)
	}
	if artifactResponse.StatusCode != http.StatusOK || !bytes.Equal(data, payload) {
		t.Fatalf("cached artifact status=%d data=%q", artifactResponse.StatusCode, data)
	}
}
