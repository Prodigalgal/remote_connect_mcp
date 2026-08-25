package center

import (
	"context"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
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
	Logger          *slog.Logger
}

type HTTPServer struct {
	store  *Store
	config HTTPConfig
}

func NewHTTPHandler(store *Store, config HTTPConfig) http.Handler {
	server := &HTTPServer{store: store, config: config}
	mcpHandler := auth.Bearer(config.MCPToken, NewMCPHandler(store, config.Version))
	adminHandler := auth.Bearer(config.AdminToken, http.HandlerFunc(server.serveAdminAPI))
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
	return requestAudit(config.Logger, mux)
}

func (s *HTTPServer) serveRegister(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		methodNotAllowed(w, http.MethodPost)
		return
	}
	if !validBearer(r, s.config.EnrollmentToken) {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "invalid enrollment token"})
		return
	}
	var req protocol.RegisterRequest
	if err := decodeJSON(r, &req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	result, err := s.store.Register(req)
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
	deadline := time.NewTimer(20 * time.Second)
	defer deadline.Stop()
	for {
		result, err := s.store.Poll(machineID, req)
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
	parts := strings.Split(path, "/")
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

func validBearer(r *http.Request, expected string) bool {
	actual := bearerValue(r)
	return len(actual) == len(expected) && subtle.ConstantTimeCompare([]byte(actual), []byte(expected)) == 1
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
