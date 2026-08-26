package center

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/Prodigalgal/remote_connect_mcp/internal/auth"
	"github.com/Prodigalgal/remote_connect_mcp/internal/protocol"
)

type HTTPConfig struct {
	Version         string
	MCPToken        string
	AdminToken      string
	EnrollmentToken string
	ConsoleHostname string
	ReleaseBaseURL  string
	AgentPublicURL  string
	Logger          *slog.Logger
}

type HTTPServer struct {
	store  *Store
	config HTTPConfig
}

func NewHTTPHandler(store *Store, config HTTPConfig) (http.Handler, error) {
	if strings.TrimSpace(config.ReleaseBaseURL) == "" {
		config.ReleaseBaseURL = "https://github.com/Prodigalgal/remote_connect_mcp/releases/download"
	}
	config.ReleaseBaseURL = strings.TrimRight(config.ReleaseBaseURL, "/")
	if strings.TrimSpace(config.AgentPublicURL) == "" {
		config.AgentPublicURL = "https://agent.example.invalid"
	}
	config.AgentPublicURL = strings.TrimRight(config.AgentPublicURL, "/")
	if err := store.ConfigureAccessTokens(map[string]string{
		AccessTokenMCP: config.MCPToken, AccessTokenAdmin: config.AdminToken, AccessTokenEnrollment: config.EnrollmentToken,
	}, time.Now().UTC()); err != nil {
		return nil, err
	}
	config.MCPToken = ""
	config.AdminToken = ""
	config.EnrollmentToken = ""
	server := &HTTPServer{store: store, config: config}
	mcpHandler := auth.BearerValidator(func(token string) bool {
		return store.AuthenticateAccessToken(AccessTokenMCP, token, time.Now().UTC())
	}, NewMCPHandler(store, config.Version))
	adminHandler := auth.BearerValidator(func(token string) bool {
		return store.AuthenticateAccessToken(AccessTokenAdmin, token, time.Now().UTC())
	}, http.HandlerFunc(server.serveAdminAPI))
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, map[string]string{"status": "ok", "version": config.Version})
	})
	mux.HandleFunc("/readyz", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, map[string]string{"status": "ready"})
	})
	mux.Handle("/mcp", mcpHandler)
	mux.Handle("/api/v1/", adminHandler)
	mux.HandleFunc("/agent/v1/register", server.serveRegister)
	mux.HandleFunc("/agent/v1/", server.serveAgentAPI)
	mux.HandleFunc("/agent-artifacts/", server.serveAgentArtifact)
	mux.HandleFunc("/console", serveConsole)
	mux.HandleFunc("/console/", serveConsole)
	mux.Handle("/", http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		host := strings.Split(r.Host, ":")[0]
		if config.ConsoleHostname != "" && strings.EqualFold(host, config.ConsoleHostname) {
			http.Redirect(w, r, "/console/", http.StatusTemporaryRedirect)
			return
		}
		mcpHandler.ServeHTTP(w, r)
	}))
	return requestAudit(config.Logger, mux), nil
}

func (s *HTTPServer) serveRegister(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		methodNotAllowed(w, http.MethodPost)
		return
	}
	var req protocol.RegisterRequest
	if err := decodeJSON(r, &req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	token := bearerValue(r)
	result, matched, err := s.store.RegisterWithScopedEnrollmentToken(req, token, time.Now().UTC())
	if matched {
		if err != nil {
			writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "invalid enrollment token"})
			return
		}
		writeJSON(w, http.StatusCreated, result)
		return
	}
	if !s.store.AuthenticateAccessToken(AccessTokenEnrollment, token, time.Now().UTC()) {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "invalid enrollment token"})
		return
	}
	result, err = s.store.Register(req)
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusCreated, result)
}

func (s *HTTPServer) serveAgentAPI(w http.ResponseWriter, r *http.Request) {
	machineID := strings.TrimSpace(r.Header.Get("X-Machine-ID"))
	token := bearerValue(r)
	if machineID == "" || !s.store.AuthenticateAgent(machineID, token) {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "invalid agent credentials"})
		return
	}
	path := strings.TrimPrefix(r.URL.Path, "/agent/v1/")
	if path == "poll" {
		s.serveAgentPoll(w, r, machineID)
		return
	}
	parts := strings.Split(path, "/")
	if len(parts) == 2 && parts[0] == "upgrade" && parts[1] == "status" {
		s.serveAgentUpgradeStatus(w, r, machineID)
		return
	}
	if len(parts) == 3 && parts[0] == "tasks" {
		switch parts[2] {
		case "state":
			s.serveAgentTaskState(w, r, machineID, parts[1])
			return
		case "output":
			s.serveAgentTaskOutput(w, r, machineID, parts[1])
			return
		}
	}
	http.NotFound(w, r)
}

func (s *HTTPServer) serveAgentUpgradeStatus(w http.ResponseWriter, r *http.Request, machineID string) {
	if r.Method != http.MethodPost {
		methodNotAllowed(w, http.MethodPost)
		return
	}
	var req protocol.UpgradeStatusRequest
	if err := decodeJSON(r, &req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	campaign, err := s.store.UpdateUpgradeStatus(machineID, req)
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, campaign)
}

func (s *HTTPServer) serveAgentPoll(w http.ResponseWriter, r *http.Request, machineID string) {
	if r.Method != http.MethodPost {
		methodNotAllowed(w, http.MethodPost)
		return
	}
	var req protocol.PollRequest
	if err := decodeJSON(r, &req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	metadata, err := decodeAgentMetadata(r)
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	deadline := time.NewTimer(20 * time.Second)
	defer deadline.Stop()
	for {
		result, err := s.store.Poll(machineID, req, metadata)
		if err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
			return
		}
		if result.Task != nil || len(result.CancelTaskIDs) > 0 {
			writeJSON(w, http.StatusOK, result)
			return
		}
		changed := s.store.Changed()
		select {
		case <-r.Context().Done():
			return
		case <-deadline.C:
			writeJSON(w, http.StatusOK, result)
			return
		case <-changed:
		}
	}
}

func decodeAgentMetadata(r *http.Request) (protocol.AgentMetadata, error) {
	encoded := strings.TrimSpace(r.Header.Get("X-Agent-Metadata"))
	if encoded == "" {
		return protocol.AgentMetadata{}, nil
	}
	if len(encoded) > 16*1024 {
		return protocol.AgentMetadata{}, errors.New("agent metadata header is too large")
	}
	data, err := base64.RawURLEncoding.DecodeString(encoded)
	if err != nil {
		return protocol.AgentMetadata{}, errors.New("invalid agent metadata encoding")
	}
	var metadata protocol.AgentMetadata
	if err := json.Unmarshal(data, &metadata); err != nil {
		return protocol.AgentMetadata{}, errors.New("invalid agent metadata JSON")
	}
	return metadata, nil
}

func (s *HTTPServer) serveAgentTaskState(w http.ResponseWriter, r *http.Request, machineID, taskID string) {
	if r.Method != http.MethodPost {
		methodNotAllowed(w, http.MethodPost)
		return
	}
	var req protocol.TaskUpdateRequest
	if err := decodeJSON(r, &req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	task, err := s.store.UpdateTask(machineID, taskID, req)
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, task)
}

func (s *HTTPServer) serveAgentTaskOutput(w http.ResponseWriter, r *http.Request, machineID, taskID string) {
	if r.Method != http.MethodPost {
		methodNotAllowed(w, http.MethodPost)
		return
	}
	var req protocol.OutputRequest
	if err := decodeJSON(r, &req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	data, err := base64.StdEncoding.DecodeString(req.Data)
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid base64 output"})
		return
	}
	next, err := s.store.AppendOutput(machineID, taskID, req.Offset, data)
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, protocol.OutputResponse{NextOffset: next})
}

func (s *HTTPServer) serveAdminAPI(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/api/v1/")
	if path == "machines" && r.Method == http.MethodGet {
		writeJSON(w, http.StatusOK, map[string]any{"machines": s.store.ListMachines(time.Now().UTC())})
		return
	}
	if path == "tasks" {
		switch r.Method {
		case http.MethodGet:
			limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
			writeJSON(w, http.StatusOK, map[string]any{"tasks": s.store.ListTasks(limit)})
		case http.MethodPost:
			var req protocol.CreateTaskRequest
			if err := decodeJSON(r, &req); err != nil {
				writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
				return
			}
			task, err := s.store.CreateTask(req)
			if err != nil {
				writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
				return
			}
			writeJSON(w, http.StatusCreated, task)
		default:
			methodNotAllowed(w, http.MethodGet, http.MethodPost)
		}
		return
	}
	if path == "upgrades" {
		switch r.Method {
		case http.MethodGet:
			limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
			writeJSON(w, http.StatusOK, map[string]any{"upgrades": s.store.ListUpgradeCampaigns(limit)})
		case http.MethodPost:
			var req struct {
				Version     string   `json:"version"`
				CanaryCount int      `json:"canary_count"`
				BatchSize   int      `json:"batch_size"`
				MachineIDs  []string `json:"machine_ids,omitempty"`
			}
			if err := decodeJSON(r, &req); err != nil {
				writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
				return
			}
			campaign, err := s.createUpgradeCampaign(r.Context(), req.Version, req.CanaryCount, req.BatchSize, req.MachineIDs)
			if err != nil {
				writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
				return
			}
			writeJSON(w, http.StatusCreated, campaign)
		default:
			methodNotAllowed(w, http.MethodGet, http.MethodPost)
		}
		return
	}
	if path == "tokens" && r.Method == http.MethodGet {
		writeJSON(w, http.StatusOK, map[string]any{"tokens": s.store.ListAccessTokens(time.Now().UTC())})
		return
	}
	if path == "enrollment-tokens" {
		switch r.Method {
		case http.MethodGet:
			limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
			writeJSON(w, http.StatusOK, map[string]any{"enrollment_tokens": s.store.ListEnrollmentTokens(time.Now().UTC(), limit)})
		case http.MethodPost:
			var req struct {
				Name           string `json:"name"`
				ExpiresSeconds int    `json:"expires_seconds"`
				MaxUses        int    `json:"max_uses"`
			}
			if err := decodeJSON(r, &req); err != nil {
				writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
				return
			}
			if req.ExpiresSeconds == 0 {
				req.ExpiresSeconds = 86400
			}
			if req.MaxUses == 0 {
				req.MaxUses = 1
			}
			if req.ExpiresSeconds < 300 || req.ExpiresSeconds > 2592000 {
				writeJSON(w, http.StatusBadRequest, map[string]string{"error": "expires_seconds must be between 300 and 2592000"})
				return
			}
			view, token, err := s.store.CreateEnrollmentToken(req.Name, time.Duration(req.ExpiresSeconds)*time.Second, req.MaxUses, time.Now().UTC())
			if err != nil {
				writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
				return
			}
			writeJSON(w, http.StatusCreated, map[string]any{"enrollment": view, "token": token})
		default:
			methodNotAllowed(w, http.MethodGet, http.MethodPost)
		}
		return
	}
	parts := strings.Split(path, "/")
	if len(parts) == 3 && parts[0] == "enrollment-tokens" && parts[2] == "revoke" && r.Method == http.MethodPost {
		result, err := s.store.RevokeEnrollmentToken(parts[1], time.Now().UTC())
		if err != nil {
			writeJSON(w, http.StatusNotFound, map[string]string{"error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, result)
		return
	}
	if len(parts) == 3 && parts[0] == "tokens" && parts[2] == "rotate" && r.Method == http.MethodPost {
		var req struct {
			NewToken     string `json:"new_token"`
			GraceSeconds int    `json:"grace_seconds"`
		}
		if err := decodeJSON(r, &req); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
			return
		}
		if req.GraceSeconds < 0 || req.GraceSeconds > 86400 {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "grace_seconds must be between 0 and 86400"})
			return
		}
		result, err := s.store.RotateAccessToken(parts[1], req.NewToken, time.Duration(req.GraceSeconds)*time.Second, time.Now().UTC())
		if err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, result)
		return
	}
	if len(parts) == 3 && parts[0] == "upgrades" && r.Method == http.MethodPost {
		campaign, err := s.store.ControlUpgradeCampaign(parts[1], parts[2])
		if err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, campaign)
		return
	}
	if len(parts) >= 2 && parts[0] == "tasks" {
		taskID := parts[1]
		if len(parts) == 2 && r.Method == http.MethodGet {
			task, ok := s.store.GetTask(taskID)
			if !ok {
				writeJSON(w, http.StatusNotFound, map[string]string{"error": "task not found"})
				return
			}
			writeJSON(w, http.StatusOK, task)
			return
		}
		if len(parts) == 3 && parts[2] == "cancel" && r.Method == http.MethodPost {
			task, err := s.store.CancelTask(taskID)
			if err != nil {
				writeJSON(w, http.StatusNotFound, map[string]string{"error": err.Error()})
				return
			}
			writeJSON(w, http.StatusOK, task)
			return
		}
		if len(parts) == 3 && parts[2] == "output" && r.Method == http.MethodGet {
			offset, _ := strconv.ParseInt(r.URL.Query().Get("cursor"), 10, 64)
			limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
			data, next, more, err := s.store.ReadOutput(taskID, offset, limit)
			if err != nil {
				writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
				return
			}
			writeJSON(w, http.StatusOK, map[string]any{"text": string(data), "cursor": offset, "next_cursor": next, "more": more})
			return
		}
	}
	http.NotFound(w, r)
}

func (s *HTTPServer) createUpgradeCampaign(ctx context.Context, version string, canaryCount, batchSize int, machineIDs []string) (UpgradeCampaign, error) {
	version = strings.TrimSpace(version)
	if version == "" || !strings.HasPrefix(version, "v") || strings.ContainsAny(version, "/\\\r\n\t ") {
		return UpgradeCampaign{}, errors.New("version must be a release tag such as v1.3.0")
	}
	machines := s.store.ListMachines(time.Now().UTC())
	byID := make(map[string]MachineView, len(machines))
	for _, machine := range machines {
		byID[machine.ID] = machine
	}
	if len(machineIDs) == 0 {
		for _, machine := range machines {
			if machine.Online {
				machineIDs = append(machineIDs, machine.ID)
			}
		}
	}
	platforms := map[string]bool{}
	for _, machineID := range machineIDs {
		machine, ok := byID[machineID]
		if !ok {
			return UpgradeCampaign{}, fmt.Errorf("machine %s not found", machineID)
		}
		platforms[machine.OS+"/"+machine.Arch] = true
	}
	artifacts := make(map[string]protocol.UpgradeArtifact, len(platforms))
	for platform := range platforms {
		parts := strings.SplitN(platform, "/", 2)
		if len(parts) != 2 || parts[0] != "linux" && parts[0] != "windows" || parts[1] != "amd64" && parts[1] != "arm64" {
			return UpgradeCampaign{}, fmt.Errorf("platform %s is unsupported", platform)
		}
		suffix := ""
		if parts[0] == "windows" {
			suffix = ".exe"
		}
		name := "remote-connect-mcp-agent-" + version + "-" + parts[0] + "-" + parts[1] + suffix
		url := s.config.ReleaseBaseURL + "/" + version + "/" + name
		sha, err := fetchReleaseSHA(ctx, url+".sha256")
		if err != nil {
			return UpgradeCampaign{}, fmt.Errorf("resolve %s: %w", platform, err)
		}
		if err := s.cacheReleaseArtifact(ctx, version, name, url, sha); err != nil {
			return UpgradeCampaign{}, fmt.Errorf("cache %s: %w", platform, err)
		}
		cachedURL := s.config.AgentPublicURL + "/agent-artifacts/" + version + "/" + name
		artifacts[platform] = protocol.UpgradeArtifact{OS: parts[0], Arch: parts[1], URL: cachedURL, SHA256: sha}
	}
	return s.store.CreateUpgradeCampaign(CreateUpgradeCampaignRequest{
		Version: version, CanaryCount: canaryCount, BatchSize: batchSize, MachineIDs: machineIDs, Artifacts: artifacts,
	})
}

func (s *HTTPServer) serveAgentArtifact(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		methodNotAllowed(w, http.MethodGet, http.MethodHead)
		return
	}
	parts := strings.Split(strings.TrimPrefix(r.URL.Path, "/agent-artifacts/"), "/")
	if len(parts) != 2 || !safeArtifactComponent(parts[0]) || !safeArtifactComponent(parts[1]) {
		http.NotFound(w, r)
		return
	}
	path := filepath.Join(s.store.dir, "agent-artifacts", parts[0], parts[1])
	info, err := os.Stat(path)
	if err != nil || !info.Mode().IsRegular() {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	http.ServeFile(w, r, path)
}

func (s *HTTPServer) cacheReleaseArtifact(ctx context.Context, version, name, sourceURL, expectedSHA string) error {
	if !safeArtifactComponent(version) || !safeArtifactComponent(name) {
		return errors.New("artifact path is invalid")
	}
	dir := filepath.Join(s.store.dir, "agent-artifacts", version)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	destination := filepath.Join(dir, name)
	if data, err := os.ReadFile(destination); err == nil {
		sum := sha256.Sum256(data)
		if hex.EncodeToString(sum[:]) == expectedSHA {
			return nil
		}
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, sourceURL, nil)
	if err != nil {
		return err
	}
	request.Header.Set("User-Agent", "remote-connect-mcp-center-artifact-cache")
	response, err := (&http.Client{Timeout: 15 * time.Minute}).Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return fmt.Errorf("artifact endpoint returned HTTP %d", response.StatusCode)
	}
	temp, err := os.CreateTemp(dir, name+"-*.tmp")
	if err != nil {
		return err
	}
	tempName := temp.Name()
	defer os.Remove(tempName)
	hash := sha256.New()
	written, copyErr := io.Copy(io.MultiWriter(temp, hash), io.LimitReader(response.Body, 100*1024*1024+1))
	closeErr := temp.Close()
	if copyErr != nil {
		return copyErr
	}
	if closeErr != nil {
		return closeErr
	}
	if written > 100*1024*1024 {
		return errors.New("artifact exceeds 100 MiB")
	}
	if actual := hex.EncodeToString(hash.Sum(nil)); actual != expectedSHA {
		return fmt.Errorf("artifact SHA-256 mismatch: got %s", actual)
	}
	if err := os.Chmod(tempName, 0o600); err != nil {
		return err
	}
	_ = os.Remove(destination)
	return os.Rename(tempName, destination)
}

func safeArtifactComponent(value string) bool {
	if value == "" || value == "." || value == ".." {
		return false
	}
	for _, character := range value {
		if character >= 'a' && character <= 'z' || character >= 'A' && character <= 'Z' || character >= '0' && character <= '9' || strings.ContainsRune("._-", character) {
			continue
		}
		return false
	}
	return true
}

func fetchReleaseSHA(ctx context.Context, url string) (string, error) {
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return "", err
	}
	client := &http.Client{Timeout: 30 * time.Second}
	response, err := client.Do(request)
	if err != nil {
		return "", err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return "", fmt.Errorf("checksum endpoint returned HTTP %d", response.StatusCode)
	}
	data, err := io.ReadAll(io.LimitReader(response.Body, 4096))
	if err != nil {
		return "", err
	}
	fields := strings.Fields(string(data))
	if len(fields) == 0 || len(fields[0]) != 64 {
		return "", errors.New("checksum file is invalid")
	}
	if _, err := hex.DecodeString(fields[0]); err != nil {
		return "", errors.New("checksum is not hexadecimal")
	}
	return strings.ToLower(fields[0]), nil
}

func decodeJSON(r *http.Request, target any) error {
	defer r.Body.Close()
	decoder := json.NewDecoder(io.LimitReader(r.Body, 2*1024*1024))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(target); err != nil {
		return fmt.Errorf("invalid JSON: %w", err)
	}
	return nil
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func bearerValue(r *http.Request) string {
	parts := strings.SplitN(r.Header.Get("Authorization"), " ", 2)
	if len(parts) != 2 || !strings.EqualFold(parts[0], "Bearer") {
		return ""
	}
	return parts[1]
}

func methodNotAllowed(w http.ResponseWriter, methods ...string) {
	w.Header().Set("Allow", strings.Join(methods, ", "))
	writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "method not allowed"})
}

type auditResponseWriter struct {
	http.ResponseWriter
	status int
}

func (w *auditResponseWriter) Write(data []byte) (int, error) {
	if w.status == 0 {
		w.status = http.StatusOK
	}
	return w.ResponseWriter.Write(data)
}

func (w *auditResponseWriter) Flush() {
	if flusher, ok := w.ResponseWriter.(http.Flusher); ok {
		flusher.Flush()
	}
}

func (w *auditResponseWriter) WriteHeader(status int) {
	w.status = status
	w.ResponseWriter.WriteHeader(status)
}

func requestAudit(logger *slog.Logger, next http.Handler) http.Handler {
	if logger == nil {
		logger = slog.Default()
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		recorder := &auditResponseWriter{ResponseWriter: w}
		next.ServeHTTP(recorder, r)
		status := recorder.status
		if status == 0 {
			status = http.StatusOK
		}
		logger.InfoContext(context.Background(), "http request", "method", r.Method, "path", r.URL.Path, "status", status, "duration_ms", time.Since(start).Milliseconds())
	})
}
