package protocol

import "time"

const (
	// ScopeModeUnrestricted keeps the legacy full-machine behaviour.  It is
	// intentionally explicit in new Agent registrations while empty values
	// remain compatible with older state files and Agents.
	ScopeModeUnrestricted = "unrestricted"
	// ScopeModeWorkspace restricts task working directories to the registered
	// workspace root.  The Agent performs the authoritative local check.
	ScopeModeWorkspace = "workspace"
)

const (
	TaskQueued          = "queued"
	TaskDispatching     = "dispatching"
	TaskRunning         = "running"
	TaskCancelRequested = "cancel_requested"
	TaskCompleted       = "completed"
	TaskFailed          = "failed"
	TaskCanceled        = "canceled"
)

const (
	// TaskKindCommand is the default shell task. Empty Kind values remain
	// compatible with older Agents and are treated as command tasks.
	TaskKindCommand = "command"
	// TaskKindDesktop is handled only by an Agent that explicitly advertises
	// the desktop capability and runs in a user session.
	TaskKindDesktop = "desktop"

	CapabilityCommand = "command"
	CapabilityDesktop = "desktop"
)

type RegisterRequest struct {
	Name          string   `json:"name"`
	HostID        string   `json:"host_id,omitempty"`
	Hostname      string   `json:"hostname"`
	OS            string   `json:"os"`
	Arch          string   `json:"arch"`
	Version       string   `json:"version"`
	DefaultCWD    string   `json:"default_cwd"`
	ScopeMode     string   `json:"scope_mode,omitempty"`
	WorkspaceRoot string   `json:"workspace_root,omitempty"`
	Capabilities  []string `json:"capabilities,omitempty"`
}

type RegisterResponse struct {
	MachineID string `json:"machine_id"`
	Token     string `json:"token"`
}

type AgentMetadata struct {
	Name          string   `json:"name"`
	HostID        string   `json:"host_id,omitempty"`
	Hostname      string   `json:"hostname"`
	OS            string   `json:"os"`
	Arch          string   `json:"arch"`
	Version       string   `json:"version"`
	DefaultCWD    string   `json:"default_cwd"`
	ScopeMode     string   `json:"scope_mode,omitempty"`
	WorkspaceRoot string   `json:"workspace_root,omitempty"`
	Capabilities  []string `json:"capabilities,omitempty"`
}

type PollRequest struct {
	RunningTaskIDs        []string `json:"running_task_ids,omitempty"`
	AvailableSlots        int      `json:"available_slots"`
	AvailableCapabilities []string `json:"available_capabilities,omitempty"`
}

type PollResponse struct {
	Task          *TaskCommand `json:"task,omitempty"`
	CancelTaskIDs []string     `json:"cancel_task_ids,omitempty"`
	Upgrade       *UpgradePlan `json:"upgrade,omitempty"`
}

type UpgradeArtifact struct {
	OS     string `json:"os"`
	Arch   string `json:"arch"`
	URL    string `json:"url"`
	SHA256 string `json:"sha256"`
}

type UpgradePlan struct {
	CampaignID string `json:"campaign_id"`
	Version    string `json:"version"`
	URL        string `json:"url"`
	SHA256     string `json:"sha256"`
}

type UpgradeStatusRequest struct {
	CampaignID string `json:"campaign_id"`
	Status     string `json:"status"`
	Error      string `json:"error,omitempty"`
}

type TaskCommand struct {
	ID                 string            `json:"id"`
	Kind               string            `json:"kind,omitempty"`
	RequiredCapability string            `json:"required_capability,omitempty"`
	Command            string            `json:"command"`
	CWD                string            `json:"cwd,omitempty"`
	Env                map[string]string `json:"env,omitempty"`
	TimeoutSeconds     int               `json:"timeout_seconds,omitempty"`
	Desktop            *DesktopAction    `json:"desktop,omitempty"`
	CreatedAt          time.Time         `json:"created_at"`
}

// DesktopAction is intentionally small. A single desktop tool can dispatch
// either a screenshot or an application launch without exposing a large GUI
// event/tree schema to ChatGPT.
type DesktopAction struct {
	Operation  string   `json:"operation"`
	Executable string   `json:"executable,omitempty"`
	Args       []string `json:"args,omitempty"`
	CWD        string   `json:"cwd,omitempty"`
}

type TaskUpdateRequest struct {
	Status          string     `json:"status"`
	ExitCode        *int       `json:"exit_code,omitempty"`
	Error           string     `json:"error,omitempty"`
	StartedAt       *time.Time `json:"started_at,omitempty"`
	FinishedAt      *time.Time `json:"finished_at,omitempty"`
	OutputTruncated bool       `json:"output_truncated,omitempty"`
}

type OutputRequest struct {
	Offset int64  `json:"offset"`
	Data   string `json:"data"`
}

type OutputResponse struct {
	NextOffset int64 `json:"next_offset"`
}

type CreateTaskRequest struct {
	MachineID          string            `json:"machine_id"`
	Kind               string            `json:"kind,omitempty"`
	RequiredCapability string            `json:"required_capability,omitempty"`
	Command            string            `json:"command"`
	CWD                string            `json:"cwd,omitempty"`
	Env                map[string]string `json:"env,omitempty"`
	TimeoutSeconds     int               `json:"timeout_seconds,omitempty"`
	IdempotencyKey     string            `json:"idempotency_key,omitempty"`
	Desktop            *DesktopAction    `json:"desktop,omitempty"`
}

type ArtifactUploadRequest struct {
	MIMEType string `json:"mime_type"`
	Data     string `json:"data"`
}
