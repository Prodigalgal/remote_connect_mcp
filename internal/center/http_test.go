package center

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

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
	if len(tools.Tools) != 6 {
		t.Fatalf("tool count = %d, want 6", len(tools.Tools))
	}
	result, err := session.CallTool(context.Background(), &mcp.CallToolParams{Name: "machines_list", Arguments: map[string]any{}})
	if err != nil || result.IsError {
		t.Fatalf("machines_list result=%+v err=%v", result, err)
	}
	if len(result.Content) != 1 {
		t.Fatalf("machines_list content = %+v", result.Content)
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
