package agent

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"math/rand/v2"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"sync"
	"time"

	processes "github.com/Prodigalgal/remote_connect_mcp/internal/process"
	"github.com/Prodigalgal/remote_connect_mcp/internal/protocol"
)

type Config struct {
	CenterURL       string
	EnrollmentToken string
	Name            string
	DefaultCWD      string
	StateDir        string
	MaxConcurrency  int
	Version         string
	Logger          *slog.Logger
}

type identity struct {
	MachineID      string   `json:"machine_id"`
	Token          string   `json:"token"`
	PendingTaskIDs []string `json:"pending_task_ids,omitempty"`
}

type Agent struct {
	config    Config
	client    *http.Client
	processes *processes.Manager

	mu       sync.Mutex
	identity identity
	running  map[string]*runningTask
}

type runningTask struct {
	command  protocol.TaskCommand
	entry    *processes.Entry
	cancel   context.CancelFunc
	canceled bool
}

type httpStatusError struct {
	Status int
	Body   string
}

func (e *httpStatusError) Error() string {
	return fmt.Sprintf("center returned HTTP %d: %s", e.Status, e.Body)
}

func New(config Config) (*Agent, error) {
	config.CenterURL = strings.TrimRight(strings.TrimSpace(config.CenterURL), "/")
	config.EnrollmentToken = strings.TrimSpace(config.EnrollmentToken)
	config.Name = strings.TrimSpace(config.Name)
	if config.CenterURL == "" || config.EnrollmentToken == "" || config.Name == "" {
		return nil, errors.New("center URL, enrollment token, and agent name are required")
	}
	if config.MaxConcurrency < 1 || config.MaxConcurrency > 32 {
		return nil, errors.New("agent max concurrency must be between 1 and 32")
	}
	defaultCWD, err := filepath.Abs(config.DefaultCWD)
	if err != nil {
		return nil, fmt.Errorf("resolve default cwd: %w", err)
	}
	info, err := os.Stat(defaultCWD)
	if err != nil || !info.IsDir() {
		return nil, fmt.Errorf("default cwd is unavailable: %s", defaultCWD)
	}
	config.DefaultCWD = defaultCWD
	stateDir, err := filepath.Abs(config.StateDir)
	if err != nil {
		return nil, err
	}
	if err := os.MkdirAll(stateDir, 0o700); err != nil {
		return nil, err
	}
	config.StateDir = stateDir
	if config.Logger == nil {
		config.Logger = slog.Default()
	}
	a := &Agent{
		config:    config,
		client:    &http.Client{Timeout: 35 * time.Second},
		processes: processes.NewManager(stateDir),
		running:   map[string]*runningTask{},
	}
	if err := a.loadIdentity(); err != nil {
		return nil, err
	}
	return a, nil
}

func (a *Agent) Close() {
	a.processes.Close()
}

func (a *Agent) Run(ctx context.Context) error {
	delay := time.Second
	interruptedReported := false
	for {
		if err := ctx.Err(); err != nil {
			return nil
		}
		if err := a.ensureRegistered(ctx); err != nil {
			a.config.Logger.Warn("agent enrollment failed; retrying", "error", err, "delay", delay)
			if !waitRetry(ctx, delay) {
				return nil
			}
			delay = nextRetryDelay(delay)
			continue
		}
		if !interruptedReported {
			if err := a.reportInterrupted(ctx); err != nil {
				a.config.Logger.Warn("could not report interrupted tasks; retrying", "error", err, "delay", delay)
				if !waitRetry(ctx, delay) {
					return nil
				}
				delay = nextRetryDelay(delay)
				continue
			}
			interruptedReported = true
		}
		response, err := a.poll(ctx)
		if err == nil {
			delay = time.Second
			a.applyPoll(ctx, response)
			continue
		}
		var statusErr *httpStatusError
		if errors.As(err, &statusErr) && statusErr.Status == http.StatusUnauthorized {
			a.config.Logger.Warn("agent credentials rejected; re-enrolling")
			a.clearCredentials()
			interruptedReported = false
			continue
		}
		a.config.Logger.Warn("center connection failed; retrying", "error", err, "delay", delay)
		if !waitRetry(ctx, delay) {
			return nil
		}
		delay = nextRetryDelay(delay)
	}
}

func waitRetry(ctx context.Context, delay time.Duration) bool {
	jitter := time.Duration(rand.IntN(500)) * time.Millisecond
	select {
	case <-ctx.Done():
		return false
	case <-time.After(delay + jitter):
		return true
	}
}

func nextRetryDelay(delay time.Duration) time.Duration {
	if delay >= 30*time.Second {
		return 30 * time.Second
	}
	delay *= 2
	if delay > 30*time.Second {
		return 30 * time.Second
	}
	return delay
}

func (a *Agent) ensureRegistered(ctx context.Context) error {
	a.mu.Lock()
	registered := a.identity.MachineID != "" && a.identity.Token != ""
	a.mu.Unlock()
	if registered {
		return nil
	}
	hostname, _ := os.Hostname()
	request := protocol.RegisterRequest{
		Name: a.config.Name, Hostname: hostname, OS: runtime.GOOS, Arch: runtime.GOARCH,
		Version: a.config.Version, DefaultCWD: a.config.DefaultCWD,
	}
	var response protocol.RegisterResponse
	if err := a.doJSON(ctx, http.MethodPost, "/agent/v1/register", request, &response, a.config.EnrollmentToken, ""); err != nil {
		return fmt.Errorf("register agent: %w", err)
	}
	if response.MachineID == "" || response.Token == "" {
		return errors.New("center returned incomplete agent credentials")
	}
	a.mu.Lock()
	a.identity.MachineID = response.MachineID
	a.identity.Token = response.Token
	err := a.saveIdentityLocked()
	a.mu.Unlock()
	if err != nil {
		return err
	}
	a.config.Logger.Info("agent registered", "machine_id", response.MachineID, "name", a.config.Name)
	return nil
}

func (a *Agent) poll(ctx context.Context) (protocol.PollResponse, error) {
	a.mu.Lock()
	running := make([]string, 0, len(a.running))
	for id := range a.running {
		running = append(running, id)
	}
	available := a.config.MaxConcurrency - len(a.running)
	a.mu.Unlock()
	sort.Strings(running)
	var response protocol.PollResponse
	err := a.agentJSON(ctx, http.MethodPost, "/agent/v1/poll", protocol.PollRequest{RunningTaskIDs: running, AvailableSlots: available}, &response)
	return response, err
}

func (a *Agent) applyPoll(ctx context.Context, response protocol.PollResponse) {
	for _, id := range response.CancelTaskIDs {
		a.cancelTask(id)
	}
	if response.Task != nil {
		a.startTask(ctx, *response.Task)
	}
}

func (a *Agent) startTask(parent context.Context, command protocol.TaskCommand) {
	a.mu.Lock()
	if _, exists := a.running[command.ID]; exists || len(a.running) >= a.config.MaxConcurrency {
		a.mu.Unlock()
		return
	}
	cwd := strings.TrimSpace(command.CWD)
	if cwd == "" {
		cwd = a.config.DefaultCWD
	} else if !filepath.IsAbs(cwd) {
		cwd = filepath.Join(a.config.DefaultCWD, cwd)
	}
	runCtx := parent
	cancel := func() {}
	if command.TimeoutSeconds > 0 {
		runCtx, cancel = context.WithTimeout(parent, time.Duration(command.TimeoutSeconds)*time.Second)
	} else {
		runCtx, cancel = context.WithCancel(parent)
	}
	entry, err := a.processes.Start(runCtx, command.Command, filepath.Clean(cwd), command.Env)
	if err != nil {
		a.mu.Unlock()
		cancel()
		go a.retryState(parent, command.ID, protocol.TaskUpdateRequest{Status: protocol.TaskFailed, Error: err.Error(), FinishedAt: timePointer(time.Now().UTC())})
		return
	}
	running := &runningTask{command: command, entry: entry, cancel: cancel}
	a.running[command.ID] = running
	a.addPendingLocked(command.ID)
	a.mu.Unlock()
	a.config.Logger.Info("task started", "task_id", command.ID, "process_id", entry.ID)
	go a.relayTask(parent, running, runCtx)
}

func (a *Agent) relayTask(ctx context.Context, running *runningTask, runCtx context.Context) {
	started := time.Now().UTC()
	a.retryState(ctx, running.command.ID, protocol.TaskUpdateRequest{Status: protocol.TaskRunning, StartedAt: &started})
	localOffset := 0
	remoteOffset := int64(0)
	for {
		info := running.entry.Info()
		data, nextLocal, _, err := running.entry.Output(localOffset, 64*1024)
		if err == nil && len(data) > 0 {
			nextRemote, uploadErr := a.retryOutput(ctx, running.command.ID, remoteOffset, data)
			if uploadErr != nil {
				return
			}
			if nextRemote == remoteOffset+int64(len(data)) {
				localOffset = nextLocal
			}
			remoteOffset = nextRemote
		}
		if !info.Running && localOffset >= info.Bytes {
			break
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(350 * time.Millisecond):
		}
	}
	info := running.entry.Info()
	finished := time.Now().UTC()
	status := protocol.TaskCompleted
	errorText := ""
	if info.ExitCode == nil || *info.ExitCode != 0 {
		status = protocol.TaskFailed
		errorText = info.Error
	}
	a.mu.Lock()
	if running.canceled {
		status = protocol.TaskCanceled
		errorText = "canceled"
	} else if errors.Is(runCtx.Err(), context.DeadlineExceeded) {
		status = protocol.TaskFailed
		errorText = "command timeout exceeded"
	}
	a.mu.Unlock()
	a.retryState(ctx, running.command.ID, protocol.TaskUpdateRequest{Status: status, ExitCode: info.ExitCode, Error: errorText, FinishedAt: &finished})
	running.cancel()
	a.mu.Lock()
	delete(a.running, running.command.ID)
	a.removePendingLocked(running.command.ID)
	a.mu.Unlock()
	a.config.Logger.Info("task finished", "task_id", running.command.ID, "status", status)
}

func (a *Agent) cancelTask(id string) {
	a.mu.Lock()
	running := a.running[id]
	if running != nil {
		running.canceled = true
	}
	a.mu.Unlock()
	if running != nil {
		running.cancel()
		_ = running.entry.Kill()
	}
}

func (a *Agent) retryState(ctx context.Context, taskID string, request protocol.TaskUpdateRequest) {
	delay := time.Second
	for {
		err := a.agentJSON(ctx, http.MethodPost, "/agent/v1/tasks/"+taskID+"/state", request, nil)
		if err == nil {
			return
		}
		a.config.Logger.Warn("task state upload failed", "task_id", taskID, "error", err)
		select {
		case <-ctx.Done():
			return
		case <-time.After(delay):
		}
		if delay < 30*time.Second {
			delay *= 2
		}
	}
}

func (a *Agent) retryOutput(ctx context.Context, taskID string, offset int64, data []byte) (int64, error) {
	delay := time.Second
	request := protocol.OutputRequest{Offset: offset, Data: base64.StdEncoding.EncodeToString(data)}
	for {
		var response protocol.OutputResponse
		err := a.agentJSON(ctx, http.MethodPost, "/agent/v1/tasks/"+taskID+"/output", request, &response)
		if err == nil {
			return response.NextOffset, nil
		}
		a.config.Logger.Warn("task output upload failed", "task_id", taskID, "error", err)
		select {
		case <-ctx.Done():
			return offset, ctx.Err()
		case <-time.After(delay):
		}
		if delay < 30*time.Second {
			delay *= 2
		}
	}
}

func (a *Agent) reportInterrupted(ctx context.Context) error {
	a.mu.Lock()
	pending := append([]string(nil), a.identity.PendingTaskIDs...)
	a.mu.Unlock()
	for _, taskID := range pending {
		now := time.Now().UTC()
		if err := a.agentJSON(ctx, http.MethodPost, "/agent/v1/tasks/"+taskID+"/state", protocol.TaskUpdateRequest{
			Status: protocol.TaskFailed, Error: "agent restarted before task completion", FinishedAt: &now,
		}, nil); err != nil {
			return err
		}
		a.mu.Lock()
		a.removePendingLocked(taskID)
		a.mu.Unlock()
	}
	return nil
}

func (a *Agent) agentJSON(ctx context.Context, method, path string, request, response any) error {
	a.mu.Lock()
	machineID, token := a.identity.MachineID, a.identity.Token
	a.mu.Unlock()
	return a.doJSON(ctx, method, path, request, response, token, machineID)
}

func (a *Agent) doJSON(ctx context.Context, method, path string, request, response any, token, machineID string) error {
	var body io.Reader
	if request != nil {
		data, err := json.Marshal(request)
		if err != nil {
			return err
		}
		body = bytes.NewReader(data)
	}
	httpRequest, err := http.NewRequestWithContext(ctx, method, a.config.CenterURL+path, body)
	if err != nil {
		return err
	}
	httpRequest.Header.Set("Authorization", "Bearer "+token)
	httpRequest.Header.Set("Accept", "application/json")
	if machineID != "" {
		httpRequest.Header.Set("X-Machine-ID", machineID)
	}
	if request != nil {
		httpRequest.Header.Set("Content-Type", "application/json")
	}
	httpResponse, err := a.client.Do(httpRequest)
	if err != nil {
		return err
	}
	defer httpResponse.Body.Close()
	data, err := io.ReadAll(io.LimitReader(httpResponse.Body, 4*1024*1024))
	if err != nil {
		return err
	}
	if httpResponse.StatusCode < 200 || httpResponse.StatusCode >= 300 {
		return &httpStatusError{Status: httpResponse.StatusCode, Body: strings.TrimSpace(string(data))}
	}
	if response != nil && len(bytes.TrimSpace(data)) > 0 {
		if err := json.Unmarshal(data, response); err != nil {
			return err
		}
	}
	return nil
}

func (a *Agent) loadIdentity() error {
	data, err := os.ReadFile(a.identityPath())
	if os.IsNotExist(err) {
		return nil
	}
	if err != nil {
		return err
	}
	if err := json.Unmarshal(data, &a.identity); err != nil {
		return fmt.Errorf("decode agent identity: %w", err)
	}
	return nil
}

func (a *Agent) clearCredentials() {
	a.mu.Lock()
	a.identity.MachineID = ""
	a.identity.Token = ""
	_ = a.saveIdentityLocked()
	a.mu.Unlock()
}

func (a *Agent) addPendingLocked(id string) {
	for _, existing := range a.identity.PendingTaskIDs {
		if existing == id {
			return
		}
	}
	a.identity.PendingTaskIDs = append(a.identity.PendingTaskIDs, id)
	_ = a.saveIdentityLocked()
}

func (a *Agent) removePendingLocked(id string) {
	filtered := a.identity.PendingTaskIDs[:0]
	for _, existing := range a.identity.PendingTaskIDs {
		if existing != id {
			filtered = append(filtered, existing)
		}
	}
	a.identity.PendingTaskIDs = filtered
	_ = a.saveIdentityLocked()
}

func (a *Agent) saveIdentityLocked() error {
	data, err := json.MarshalIndent(a.identity, "", "  ")
	if err != nil {
		return err
	}
	temp, err := os.CreateTemp(a.config.StateDir, "identity-*.tmp")
	if err != nil {
		return err
	}
	tempName := temp.Name()
	defer os.Remove(tempName)
	if err := temp.Chmod(0o600); err != nil {
		temp.Close()
		return err
	}
	if _, err := temp.Write(data); err != nil {
		temp.Close()
		return err
	}
	if err := temp.Sync(); err != nil {
		temp.Close()
		return err
	}
	if err := temp.Close(); err != nil {
		return err
	}
	return os.Rename(tempName, a.identityPath())
}

func (a *Agent) identityPath() string {
	return filepath.Join(a.config.StateDir, "identity.json")
}

func timePointer(value time.Time) *time.Time { return &value }
