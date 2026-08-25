package protocol

import "time"

const (
	TaskQueued          = "queued"
	TaskDispatching     = "dispatching"
	TaskRunning         = "running"
	TaskCancelRequested = "cancel_requested"
	TaskCompleted       = "completed"
	TaskFailed          = "failed"
	TaskCanceled        = "canceled"
)

type RegisterRequest struct {
	Name       string `json:"name"`
	Hostname   string `json:"hostname"`
	OS         string `json:"os"`
	Arch       string `json:"arch"`
	Version    string `json:"version"`
	DefaultCWD string `json:"default_cwd"`
}

type RegisterResponse struct {
	MachineID string `json:"machine_id"`
	Token     string `json:"token"`
}

type PollRequest struct {
	RunningTaskIDs []string `json:"running_task_ids,omitempty"`
	AvailableSlots int      `json:"available_slots"`
}

type PollResponse struct {
	Task          *TaskCommand `json:"task,omitempty"`
	CancelTaskIDs []string     `json:"cancel_task_ids,omitempty"`
}

type TaskCommand struct {
	ID             string            `json:"id"`
	Command        string            `json:"command"`
	CWD            string            `json:"cwd,omitempty"`
	Env            map[string]string `json:"env,omitempty"`
	TimeoutSeconds int               `json:"timeout_seconds,omitempty"`
	CreatedAt      time.Time         `json:"created_at"`
}

type TaskUpdateRequest struct {
	Status     string     `json:"status"`
	ExitCode   *int       `json:"exit_code,omitempty"`
	Error      string     `json:"error,omitempty"`
	StartedAt  *time.Time `json:"started_at,omitempty"`
	FinishedAt *time.Time `json:"finished_at,omitempty"`
}

type OutputRequest struct {
	Offset int64  `json:"offset"`
	Data   string `json:"data"`
}

type OutputResponse struct {
	NextOffset int64 `json:"next_offset"`
}

type CreateTaskRequest struct {
	MachineID      string            `json:"machine_id"`
	Command        string            `json:"command"`
	CWD            string            `json:"cwd,omitempty"`
	Env            map[string]string `json:"env,omitempty"`
	TimeoutSeconds int               `json:"timeout_seconds,omitempty"`
}
