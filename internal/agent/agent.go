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
	"sync/atomic"
	"time"

	"github.com/Prodigalgal/remote_connect_mcp/internal/desktop"
	processes "github.com/Prodigalgal/remote_connect_mcp/internal/process"
	"github.com/Prodigalgal/remote_connect_mcp/internal/protocol"
	"github.com/Prodigalgal/remote_connect_mcp/internal/updater"
	"github.com/Prodigalgal/remote_connect_mcp/internal/workspace"
)

type Config struct {
	CenterURL       string
	EnrollmentToken string
	Name            string
	HostID          string
	DefaultCWD      string
	ScopeMode       string
	WorkspaceRoot   string
	Capabilities    []string
	DesktopEnabled  bool
	StateDir        string
	MaxConcurrency  int
	MaxOutputBytes  int64
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

	mu        sync.Mutex
	identity  identity
	running   map[string]*runningTask
	upgrading bool
	// The response header is an explicit capability signal.  It prevents a
	// compatibility server that returns immediately from turning the Agent
	// loop into a hot poller.
	longPollHonored atomic.Bool
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
	config.HostID = strings.TrimSpace(config.HostID)
	if config.CenterURL == "" || config.EnrollmentToken == "" || config.Name == "" {
		return nil, errors.New("center URL, enrollment token, and agent name are required")
	}
	if config.HostID == "" {
		config.HostID = config.Name
	}
	if err := validateHostID(config.HostID); err != nil {
		return nil, err
	}
	if config.MaxConcurrency < 1 || config.MaxConcurrency > 32 {
		return nil, errors.New("agent max concurrency must be between 1 and 32")
	}
	if config.MaxOutputBytes < 0 || config.MaxOutputBytes > 1024*1024*1024 {
		return nil, errors.New("agent max output bytes must be between 0 and 1073741824")
	}
	scopeMode, workspaceRoot, defaultCWD, err := workspace.PrepareLocal(config.ScopeMode, config.WorkspaceRoot, config.DefaultCWD)
	if err != nil {
		return nil, err
	}
	config.ScopeMode = scopeMode
	config.WorkspaceRoot = workspaceRoot
	config.DefaultCWD = defaultCWD
	capabilities := append([]string{"command", "durable_tasks"}, config.Capabilities...)
	if config.ScopeMode == workspace.ModeWorkspace {
		capabilities = append(capabilities, "workspace-policy")
	}
	if config.DesktopEnabled {
		if !desktop.Supported() {
			return nil, errors.New("desktop capability is not supported on this platform")
		}
		capabilities = append(capabilities, protocol.CapabilityDesktop)
	} else {
		filtered := capabilities[:0]
		for _, capability := range capabilities {
			if capability != protocol.CapabilityDesktop {
				filtered = append(filtered, capability)
			}
		}
		capabilities = filtered
	}
	config.Capabilities = workspace.NormalizeCapabilities(capabilities)
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
		processes: processes.NewManagerWithLimit(stateDir, config.MaxOutputBytes),
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
			a.recoverTasks(ctx)
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
		if err := a.reportUpgradeResult(ctx); err != nil {
			a.config.Logger.Warn("could not report upgrade result; retrying", "error", err, "delay", delay)
			if !waitRetry(ctx, delay) {
				return nil
			}
			delay = nextRetryDelay(delay)
			continue
		}
		response, err := a.poll(ctx)
		if err == nil {
			delay = time.Second
			a.applyPoll(ctx, response)
			if !a.longPollHonored.Load() && response.Task == nil && response.Upgrade == nil && len(response.CancelTaskIDs) == 0 {
				// Older Centers may not implement the long-poll contract. Keep a
				// bounded compatibility delay instead of spinning on an immediate
				// empty response; modern Centers advertise the header and take the
				// event-driven path without this delay.
				if !waitRetry(ctx, delay) {
					return nil
				}
			}
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
		Name: a.config.Name, HostID: a.config.HostID, Hostname: hostname, OS: runtime.GOOS, Arch: runtime.GOARCH,
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
	if a.upgrading {
		available = 0
	}
	a.mu.Unlock()
	sort.Strings(running)
	var response protocol.PollResponse
	// Both the Java Center and the Go compatibility Center understand this
	// bounded long-poll hint.  Keeping it on the legacy Agent prevents an
	// immediate-response Java Center from turning an idle Agent into a hot
	// request loop; older Centers simply ignore the query and retain their
	// existing server-side hold.
	err := a.agentJSON(ctx, http.MethodPost, "/agent/v1/poll?wait_ms=25000", protocol.PollRequest{RunningTaskIDs: running, AvailableSlots: available, AvailableCapabilities: append([]string(nil), a.config.Capabilities...)}, &response)
	return response, err
}

func (a *Agent) applyPoll(ctx context.Context, response protocol.PollResponse) {
	for _, id := range response.CancelTaskIDs {
		a.cancelTask(id)
	}
	if response.Upgrade != nil {
		a.startUpgrade(ctx, *response.Upgrade)
		return
	}
	if response.Task != nil {
		a.startTask(ctx, *response.Task)
	}
}

func (a *Agent) startUpgrade(ctx context.Context, plan protocol.UpgradePlan) {
	a.mu.Lock()
	if a.upgrading || a.hasAttachedTasksLocked() {
		a.mu.Unlock()
		return
	}
	a.upgrading = true
	a.mu.Unlock()
	go func() {
		a.config.Logger.Info("agent upgrade started", "campaign_id", plan.CampaignID, "version", plan.Version)
		if err := a.reportUpgrade(ctx, plan.CampaignID, UpgradeDownloading, ""); err != nil {
			a.failUpgrade(ctx, plan, err)
			return
		}
		err := updater.PrepareAndLaunch(ctx, plan, a.config.StateDir, platformServiceName(), a.config.Logger, func() error {
			return a.reportUpgrade(ctx, plan.CampaignID, UpgradeInstalling, "")
		})
		if err != nil {
			a.failUpgrade(ctx, plan, err)
			return
		}
		a.config.Logger.Info("upgrade helper launched; waiting for service restart", "campaign_id", plan.CampaignID, "version", plan.Version)
		<-ctx.Done()
	}()
}

func (a *Agent) hasAttachedTasksLocked() bool {
	for _, running := range a.running {
		if running.entry == nil || !running.entry.Info().Durable {
			return true
		}
	}
	return false
}

func (a *Agent) failUpgrade(ctx context.Context, plan protocol.UpgradePlan, err error) {
	a.config.Logger.Error("agent upgrade failed", "campaign_id", plan.CampaignID, "version", plan.Version, "error", err)
	_ = a.reportUpgrade(ctx, plan.CampaignID, UpgradeFailed, err.Error())
	a.mu.Lock()
	a.upgrading = false
	a.mu.Unlock()
}

const (
	UpgradeDownloading = "downloading"
	UpgradeInstalling  = "installing"
	UpgradeFailed      = "failed"
)

func (a *Agent) reportUpgrade(ctx context.Context, campaignID, status, errorText string) error {
	return a.agentJSON(ctx, http.MethodPost, "/agent/v1/upgrade/status", protocol.UpgradeStatusRequest{
		CampaignID: campaignID, Status: status, Error: errorText,
	}, nil)
}

func (a *Agent) reportUpgradeResult(ctx context.Context) error {
	result, err := updater.ConsumeResult(a.config.StateDir)
	if err != nil || result == nil {
		return err
	}
	if err := a.reportUpgrade(ctx, result.CampaignID, result.Status, result.Error); err != nil {
		return err
	}
	return updater.RemoveResult(a.config.StateDir)
}

func (a *Agent) startTask(parent context.Context, command protocol.TaskCommand) {
	if strings.EqualFold(strings.TrimSpace(command.Kind), protocol.TaskKindDesktop) {
		a.startDesktopTask(parent, command)
		return
	}
	if command.Kind != "" && !strings.EqualFold(strings.TrimSpace(command.Kind), protocol.TaskKindCommand) {
		now := time.Now().UTC()
		go a.retryState(parent, command.ID, protocol.TaskUpdateRequest{Status: protocol.TaskFailed, Error: "unsupported task kind", FinishedAt: &now})
		return
	}
	if err := protocol.ValidateTaskInput(command.Command, command.CWD, command.Env, command.TimeoutSeconds); err != nil {
		now := time.Now().UTC()
		go a.retryState(parent, command.ID, protocol.TaskUpdateRequest{Status: protocol.TaskFailed, Error: err.Error(), FinishedAt: &now})
		return
	}
	a.mu.Lock()
	if _, exists := a.running[command.ID]; exists || len(a.running) >= a.config.MaxConcurrency {
		a.mu.Unlock()
		return
	}
	cwd, cwdErr := workspace.ResolveLocal(a.config.ScopeMode, a.config.WorkspaceRoot, a.config.DefaultCWD, command.CWD)
	if cwdErr != nil {
		a.mu.Unlock()
		now := time.Now().UTC()
		go a.retryState(parent, command.ID, protocol.TaskUpdateRequest{Status: protocol.TaskFailed, Error: cwdErr.Error(), FinishedAt: &now})
		return
	}
	runCtx := parent
	cancel := func() {}
	if command.TimeoutSeconds > 0 {
		runCtx, cancel = context.WithTimeout(parent, time.Duration(command.TimeoutSeconds)*time.Second)
	} else {
		runCtx, cancel = context.WithCancel(parent)
	}
	var entry *processes.Entry
	var err error
	if command.TimeoutSeconds == 0 {
		entry, err = a.processes.StartDurable(command.ID, command.Command, filepath.Clean(cwd), command.Env)
	} else {
		entry, err = a.processes.Start(runCtx, command.Command, filepath.Clean(cwd), command.Env)
	}
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

func (a *Agent) startDesktopTask(parent context.Context, command protocol.TaskCommand) {
	if !a.config.DesktopEnabled || !agentHasCapability(a.config.Capabilities, protocol.CapabilityDesktop) {
		now := time.Now().UTC()
		go a.retryState(parent, command.ID, protocol.TaskUpdateRequest{Status: protocol.TaskFailed, Error: "desktop Agent is not enabled", FinishedAt: &now})
		return
	}
	if command.Desktop == nil {
		now := time.Now().UTC()
		go a.retryState(parent, command.ID, protocol.TaskUpdateRequest{Status: protocol.TaskFailed, Error: "desktop action is required", FinishedAt: &now})
		return
	}
	if command.RequiredCapability != "" && command.RequiredCapability != protocol.CapabilityDesktop {
		now := time.Now().UTC()
		go a.retryState(parent, command.ID, protocol.TaskUpdateRequest{Status: protocol.TaskFailed, Error: "unsupported desktop capability", FinishedAt: &now})
		return
	}
	if err := desktop.ValidateAction(desktop.Action{Operation: command.Desktop.Operation, Executable: command.Desktop.Executable, Args: command.Desktop.Args, CWD: command.Desktop.CWD}); err != nil {
		now := time.Now().UTC()
		go a.retryState(parent, command.ID, protocol.TaskUpdateRequest{Status: protocol.TaskFailed, Error: err.Error(), FinishedAt: &now})
		return
	}
	a.mu.Lock()
	if _, exists := a.running[command.ID]; exists || len(a.running) >= a.config.MaxConcurrency {
		a.mu.Unlock()
		return
	}
	cwd, cwdErr := workspace.ResolveLocal(a.config.ScopeMode, a.config.WorkspaceRoot, a.config.DefaultCWD, command.CWD)
	if cwdErr != nil {
		a.mu.Unlock()
		now := time.Now().UTC()
		go a.retryState(parent, command.ID, protocol.TaskUpdateRequest{Status: protocol.TaskFailed, Error: cwdErr.Error(), FinishedAt: &now})
		return
	}
	if command.Desktop != nil && strings.EqualFold(strings.TrimSpace(command.Desktop.Operation), desktop.OperationLaunch) {
		command.Desktop = cloneDesktopAction(command.Desktop)
		command.Desktop.CWD = filepath.Clean(cwd)
	}
	timeout := command.TimeoutSeconds
	if timeout <= 0 {
		timeout = 30
	}
	runCtx, cancel := context.WithTimeout(parent, time.Duration(timeout)*time.Second)
	running := &runningTask{command: command, cancel: cancel}
	a.running[command.ID] = running
	a.addPendingLocked(command.ID)
	a.mu.Unlock()
	a.config.Logger.Info("desktop task started", "task_id", command.ID, "operation", command.Desktop.Operation)
	go a.relayDesktopTask(parent, running, runCtx)
}

func (a *Agent) relayDesktopTask(ctx context.Context, running *runningTask, runCtx context.Context) {
	started := time.Now().UTC()
	if err := a.retryState(ctx, running.command.ID, protocol.TaskUpdateRequest{Status: protocol.TaskRunning, StartedAt: &started}); err != nil {
		a.finishRunningTask(running.command.ID)
		return
	}
	result, executeErr := desktop.Execute(runCtx, running.command.Desktop)
	status := protocol.TaskCompleted
	errorText := ""
	if executeErr != nil {
		status = protocol.TaskFailed
		errorText = executeErr.Error()
	}
	a.mu.Lock()
	if running.canceled {
		status = protocol.TaskCanceled
		errorText = "canceled"
	} else if errors.Is(runCtx.Err(), context.DeadlineExceeded) {
		status = protocol.TaskFailed
		errorText = "desktop operation timeout exceeded"
	}
	a.mu.Unlock()
	if status == protocol.TaskCompleted {
		if len(result.Data) > 0 {
			if err := a.retryArtifact(ctx, running.command.ID, result.MIMEType, result.Data); err != nil {
				status = protocol.TaskFailed
				errorText = err.Error()
			}
		}
		if result.Summary != "" {
			if _, err := a.retryOutput(ctx, running.command.ID, 0, []byte(result.Summary+"\n")); err != nil && status == protocol.TaskCompleted {
				status = protocol.TaskFailed
				errorText = err.Error()
			}
		}
	}
	finished := time.Now().UTC()
	_ = a.retryState(ctx, running.command.ID, protocol.TaskUpdateRequest{Status: status, Error: errorText, FinishedAt: &finished})
	a.finishRunningTask(running.command.ID)
	a.config.Logger.Info("desktop task finished", "task_id", running.command.ID, "status", status)
}

func (a *Agent) retryArtifact(ctx context.Context, taskID, mimeType string, data []byte) error {
	if len(data) == 0 || len(data) > desktop.MaxScreenshotBytes {
		return fmt.Errorf("desktop artifact must be between 1 and %d bytes", desktop.MaxScreenshotBytes)
	}
	request := protocol.ArtifactUploadRequest{MIMEType: mimeType, Data: base64.StdEncoding.EncodeToString(data)}
	delay := time.Second
	for {
		if err := a.agentJSON(ctx, http.MethodPost, "/agent/v1/tasks/"+taskID+"/artifact", request, nil); err == nil {
			return nil
		} else {
			a.config.Logger.Warn("desktop artifact upload failed", "task_id", taskID, "error", err)
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(delay):
		}
		if delay < 30*time.Second {
			delay *= 2
		}
	}
}

func (a *Agent) finishRunningTask(taskID string) {
	a.mu.Lock()
	delete(a.running, taskID)
	a.removePendingLocked(taskID)
	a.mu.Unlock()
}

func agentHasCapability(values []string, wanted string) bool {
	for _, value := range values {
		if value == wanted {
			return true
		}
	}
	return false
}

func cloneDesktopAction(value *protocol.DesktopAction) *protocol.DesktopAction {
	if value == nil {
		return nil
	}
	copy := *value
	copy.Args = append([]string(nil), value.Args...)
	return &copy
}

func (a *Agent) relayTask(ctx context.Context, running *runningTask, runCtx context.Context) {
	started := time.Now().UTC()
	a.retryState(ctx, running.command.ID, protocol.TaskUpdateRequest{Status: protocol.TaskRunning, StartedAt: &started})
	localOffset := 0
	remoteOffset := int64(0)
	outputSyncError := ""
	for {
		outputChanged := running.entry.OutputChanged()
		info := running.entry.Info()
		data, nextLocal, _, err := running.entry.Output(localOffset, 64*1024)
		if err == nil && len(data) > 0 {
			nextRemote, uploadErr := a.retryOutput(ctx, running.command.ID, remoteOffset, data)
			if uploadErr != nil {
				return
			}
			if nextRemote == remoteOffset+int64(len(data)) {
				localOffset = nextLocal
			} else if nextRemote >= 0 && nextRemote <= int64(info.Bytes) {
				// On recovery the Center may already contain a prefix uploaded
				// before the Agent restarted. Skip that confirmed prefix locally
				// instead of retrying offset zero forever.
				localOffset = int(nextRemote)
			} else if nextRemote > int64(info.Bytes) {
				outputSyncError = fmt.Sprintf("local task output is shorter than Center cursor (%d > %d)", nextRemote, info.Bytes)
				_ = running.entry.Kill()
				break
			}
			remoteOffset = nextRemote
		}
		if !info.Running && localOffset >= info.Bytes {
			break
		}
		select {
		case <-ctx.Done():
			return
		case <-outputChanged:
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
	if outputSyncError != "" {
		status = protocol.TaskFailed
		errorText = outputSyncError
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
	if err := a.retryState(ctx, running.command.ID, protocol.TaskUpdateRequest{Status: status, ExitCode: info.ExitCode, Error: errorText, FinishedAt: &finished, OutputTruncated: info.OutputTruncated}); err == nil {
		a.processes.ForgetTask(running.command.ID)
	}
	running.cancel()
	a.mu.Lock()
	delete(a.running, running.command.ID)
	a.removePendingLocked(running.command.ID)
	a.mu.Unlock()
	a.config.Logger.Info("task finished", "task_id", running.command.ID, "status", status)
}

func (a *Agent) recoverTasks(ctx context.Context) {
	a.mu.Lock()
	pending := append([]string(nil), a.identity.PendingTaskIDs...)
	a.mu.Unlock()
	for _, taskID := range pending {
		entry, ok := a.processes.DurableTask(taskID)
		if !ok {
			continue
		}
		if _, err := workspace.ResolveLocal(a.config.ScopeMode, a.config.WorkspaceRoot, a.config.DefaultCWD, entry.Cwd); err != nil {
			a.config.Logger.Warn("recovered task cwd rejected by workspace policy", "task_id", taskID, "error", err)
			continue
		}
		a.config.Logger.Info("recovered task cwd accepted", "task_id", taskID, "cwd", entry.Cwd)
		runCtx, cancel := context.WithCancel(ctx)
		running := &runningTask{command: protocol.TaskCommand{ID: taskID, Command: entry.Command, CWD: entry.Cwd}, entry: entry, cancel: cancel}
		a.mu.Lock()
		_, exists := a.running[taskID]
		atCapacity := !exists && len(a.running) >= a.config.MaxConcurrency
		if !exists && !atCapacity {
			a.running[taskID] = running
		}
		a.mu.Unlock()
		if atCapacity {
			a.config.Logger.Warn("recovered task capacity reached", "task_id", taskID, "limit", a.config.MaxConcurrency)
			cancel()
			break
		}
		if !exists {
			a.config.Logger.Info("durable task recovered", "task_id", taskID, "process_id", entry.ID)
			go a.relayTask(ctx, running, runCtx)
		}
	}
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
		if running.entry != nil {
			_ = running.entry.Kill()
		}
	}
}

func (a *Agent) retryState(ctx context.Context, taskID string, request protocol.TaskUpdateRequest) error {
	delay := time.Second
	for {
		err := a.agentJSON(ctx, http.MethodPost, "/agent/v1/tasks/"+taskID+"/state", request, nil)
		if err == nil {
			return nil
		}
		a.config.Logger.Warn("task state upload failed", "task_id", taskID, "error", err)
		select {
		case <-ctx.Done():
			return ctx.Err()
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
		a.mu.Lock()
		_, recovered := a.running[taskID]
		a.mu.Unlock()
		if recovered {
			continue
		}
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
	outputTruncated := false
	switch value := request.(type) {
	case protocol.TaskUpdateRequest:
		outputTruncated = value.OutputTruncated
		if outputTruncated {
			value.OutputTruncated = false
			request = value
		}
	case *protocol.TaskUpdateRequest:
		if value != nil && value.OutputTruncated {
			outputTruncated = true
			copyValue := *value
			copyValue.OutputTruncated = false
			request = copyValue
		}
	}
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
	if outputTruncated {
		// Keep the JSON body compatible with older Centers that reject unknown
		// fields; newer Centers recover this advisory bit from the header.
		httpRequest.Header.Set("X-Task-Output-Truncated", "1")
	}
	if machineID != "" {
		httpRequest.Header.Set("X-Machine-ID", machineID)
	}
	// New metadata travels in an optional header so a newer Agent remains
	// registerable against an older Center whose strict JSON decoder only knows
	// the original RegisterRequest fields. Older Centers ignore this header.
	hostname, _ := os.Hostname()
	metadata, err := json.Marshal(protocol.AgentMetadata{
		Name: a.config.Name, HostID: a.config.HostID, Hostname: hostname, OS: runtime.GOOS, Arch: runtime.GOARCH,
		Version: a.config.Version, DefaultCWD: a.config.DefaultCWD, ScopeMode: a.config.ScopeMode,
		WorkspaceRoot: a.config.WorkspaceRoot, Capabilities: append([]string(nil), a.config.Capabilities...),
	})
	if err != nil {
		return err
	}
	httpRequest.Header.Set("X-Agent-Metadata", base64.RawURLEncoding.EncodeToString(metadata))
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
	if strings.HasPrefix(path, "/agent/v1/poll") {
		a.longPollHonored.Store(strings.EqualFold(strings.TrimSpace(httpResponse.Header.Get("X-RCM-Long-Poll")), "accepted"))
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

func validateHostID(value string) error {
	if value == "" || len(value) > 128 {
		return errors.New("host_id must be 1-128 characters")
	}
	for _, character := range value {
		if character >= 'a' && character <= 'z' || character >= 'A' && character <= 'Z' || character >= '0' && character <= '9' || strings.ContainsRune("._:-", character) {
			continue
		}
		return errors.New("host_id may contain only letters, digits, dot, colon, underscore, and hyphen")
	}
	return nil
}
