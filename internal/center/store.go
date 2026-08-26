package center

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/Prodigalgal/remote_connect_mcp/internal/protocol"
)

type Machine struct {
	ID         string    `json:"id"`
	Name       string    `json:"name"`
	Hostname   string    `json:"hostname"`
	OS         string    `json:"os"`
	Arch       string    `json:"arch"`
	Version    string    `json:"version"`
	DefaultCWD string    `json:"default_cwd"`
	CreatedAt  time.Time `json:"created_at"`
	UpdatedAt  time.Time `json:"updated_at"`
	LastSeen   time.Time `json:"last_seen"`
	TokenHash  string    `json:"token_hash,omitempty"`
}

type MachineView struct {
	ID         string    `json:"id"`
	Name       string    `json:"name"`
	Hostname   string    `json:"hostname"`
	OS         string    `json:"os"`
	Arch       string    `json:"arch"`
	Version    string    `json:"version"`
	DefaultCWD string    `json:"default_cwd"`
	CreatedAt  time.Time `json:"created_at"`
	LastSeen   time.Time `json:"last_seen"`
	Online     bool      `json:"online"`
}

type Task struct {
	ID             string            `json:"id"`
	MachineID      string            `json:"machine_id"`
	Command        string            `json:"command"`
	CWD            string            `json:"cwd,omitempty"`
	Env            map[string]string `json:"env,omitempty"`
	TimeoutSeconds int               `json:"timeout_seconds,omitempty"`
	Status         string            `json:"status"`
	ExitCode       *int              `json:"exit_code,omitempty"`
	Error          string            `json:"error,omitempty"`
	OutputBytes    int64             `json:"output_bytes"`
	CreatedAt      time.Time         `json:"created_at"`
	DispatchedAt   *time.Time        `json:"dispatched_at,omitempty"`
	StartedAt      *time.Time        `json:"started_at,omitempty"`
	FinishedAt     *time.Time        `json:"finished_at,omitempty"`
	LeaseUntil     *time.Time        `json:"lease_until,omitempty"`
}

type persistedState struct {
	Machines map[string]*Machine `json:"machines"`
	Tasks    map[string]*Task    `json:"tasks"`
}

type Store struct {
	mu        sync.Mutex
	dir       string
	statePath string
	outputDir string
	state     persistedState
	changed   chan struct{}
}

func OpenStore(dir string) (*Store, error) {
	if strings.TrimSpace(dir) == "" {
		return nil, errors.New("center state directory is required")
	}
	dir, err := filepath.Abs(dir)
	if err != nil {
		return nil, err
	}
	outputDir := filepath.Join(dir, "task-output")
	if err := os.MkdirAll(outputDir, 0o700); err != nil {
		return nil, fmt.Errorf("create center state directory: %w", err)
	}
	s := &Store{
		dir: dir, statePath: filepath.Join(dir, "state.json"), outputDir: outputDir,
		state:   persistedState{Machines: map[string]*Machine{}, Tasks: map[string]*Task{}},
		changed: make(chan struct{}),
	}
	data, err := os.ReadFile(s.statePath)
	if err == nil {
		if err := json.Unmarshal(data, &s.state); err != nil {
			return nil, fmt.Errorf("decode center state: %w", err)
		}
	} else if !os.IsNotExist(err) {
		return nil, fmt.Errorf("read center state: %w", err)
	}
	if s.state.Machines == nil {
		s.state.Machines = map[string]*Machine{}
	}
	if s.state.Tasks == nil {
		s.state.Tasks = map[string]*Task{}
	}
	return s, nil
}

func (s *Store) Register(req protocol.RegisterRequest) (protocol.RegisterResponse, error) {
	name := strings.TrimSpace(req.Name)
	if name == "" {
		name = strings.TrimSpace(req.Hostname)
	}
	if name == "" {
		return protocol.RegisterResponse{}, errors.New("machine name is required")
	}
	token, err := randomToken(32)
	if err != nil {
		return protocol.RegisterResponse{}, err
	}
	now := time.Now().UTC()
	s.mu.Lock()
	defer s.mu.Unlock()
	var machine *Machine
	for _, candidate := range s.state.Machines {
		if strings.EqualFold(candidate.Name, name) {
			machine = candidate
			break
		}
	}
	if machine == nil {
		id, err := randomID("machine")
		if err != nil {
			return protocol.RegisterResponse{}, err
		}
		machine = &Machine{ID: id, Name: name, CreatedAt: now}
		s.state.Machines[id] = machine
	}
	machine.Name = name
	machine.Hostname = strings.TrimSpace(req.Hostname)
	machine.OS = strings.TrimSpace(req.OS)
	machine.Arch = strings.TrimSpace(req.Arch)
	machine.Version = strings.TrimSpace(req.Version)
	machine.DefaultCWD = strings.TrimSpace(req.DefaultCWD)
	machine.UpdatedAt = now
	machine.LastSeen = now
	machine.TokenHash = hashToken(token)
	if err := s.saveLocked(); err != nil {
		return protocol.RegisterResponse{}, err
	}
	s.notifyLocked()
	return protocol.RegisterResponse{MachineID: machine.ID, Token: token}, nil
}

func (s *Store) AuthenticateAgent(machineID, token string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	machine := s.state.Machines[machineID]
	if machine == nil || machine.TokenHash == "" {
		return false
	}
	actual := hashToken(token)
	return len(actual) == len(machine.TokenHash) && subtle.ConstantTimeCompare([]byte(actual), []byte(machine.TokenHash)) == 1
}

func (s *Store) ListMachines(now time.Time) []MachineView {
	s.mu.Lock()
	defer s.mu.Unlock()
	result := make([]MachineView, 0, len(s.state.Machines))
	for _, machine := range s.state.Machines {
		result = append(result, machineView(machine, now))
	}
	sort.Slice(result, func(i, j int) bool { return result[i].Name < result[j].Name })
	return result
}

func (s *Store) GetMachine(id string, now time.Time) (MachineView, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	machine := s.state.Machines[id]
	if machine == nil {
		return MachineView{}, false
	}
	return machineView(machine, now), true
}

func machineView(machine *Machine, now time.Time) MachineView {
	return MachineView{
		ID: machine.ID, Name: machine.Name, Hostname: machine.Hostname, OS: machine.OS, Arch: machine.Arch,
		Version: machine.Version, DefaultCWD: machine.DefaultCWD, CreatedAt: machine.CreatedAt,
		LastSeen: machine.LastSeen, Online: now.Sub(machine.LastSeen) <= 45*time.Second,
	}
}

func (s *Store) CreateTask(req protocol.CreateTaskRequest) (Task, error) {
	command := strings.TrimSpace(req.Command)
	if command == "" {
		return Task{}, errors.New("command is required")
	}
	if req.TimeoutSeconds < 0 {
		return Task{}, errors.New("timeout_seconds cannot be negative")
	}
	id, err := randomID("task")
	if err != nil {
		return Task{}, err
	}
	now := time.Now().UTC()
	task := &Task{
		ID: id, MachineID: strings.TrimSpace(req.MachineID), Command: command, CWD: strings.TrimSpace(req.CWD),
		Env: cloneMap(req.Env), TimeoutSeconds: req.TimeoutSeconds, Status: protocol.TaskQueued, CreatedAt: now,
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.state.Machines[task.MachineID] == nil {
		return Task{}, fmt.Errorf("machine %s not found", task.MachineID)
	}
	s.state.Tasks[task.ID] = task
	if err := s.saveLocked(); err != nil {
		delete(s.state.Tasks, task.ID)
		return Task{}, err
	}
	s.notifyLocked()
	return cloneTask(task), nil
}

func (s *Store) GetTask(id string) (Task, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	task := s.state.Tasks[id]
	if task == nil {
		return Task{}, false
	}
	return cloneTask(task), true
}

func (s *Store) ListTasks(limit int) []Task {
	if limit <= 0 || limit > 500 {
		limit = 100
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	result := make([]Task, 0, len(s.state.Tasks))
	for _, task := range s.state.Tasks {
		result = append(result, cloneTask(task))
	}
	sort.Slice(result, func(i, j int) bool { return result[i].CreatedAt.After(result[j].CreatedAt) })
	if len(result) > limit {
		result = result[:limit]
	}
	return result
}

func (s *Store) CancelTask(id string) (Task, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	task := s.state.Tasks[id]
	if task == nil {
		return Task{}, fmt.Errorf("task %s not found", id)
	}
	switch task.Status {
	case protocol.TaskCompleted, protocol.TaskFailed, protocol.TaskCanceled:
		return cloneTask(task), nil
	case protocol.TaskQueued:
		now := time.Now().UTC()
		task.Status = protocol.TaskCanceled
		task.FinishedAt = &now
	case protocol.TaskDispatching, protocol.TaskRunning, protocol.TaskCancelRequested:
		task.Status = protocol.TaskCancelRequested
	}
	if err := s.saveLocked(); err != nil {
		return Task{}, err
	}
	s.notifyLocked()
	return cloneTask(task), nil
}

func (s *Store) Poll(machineID string, req protocol.PollRequest, metadata ...protocol.AgentMetadata) (protocol.PollResponse, error) {
	now := time.Now().UTC()
	s.mu.Lock()
	defer s.mu.Unlock()
	machine := s.state.Machines[machineID]
	if machine == nil {
		return protocol.PollResponse{}, fmt.Errorf("machine %s not found", machineID)
	}
	if len(metadata) > 0 {
		updateMachineMetadata(machine, metadata[0])
	}
	machine.LastSeen = now
	machine.UpdatedAt = now
	response := protocol.PollResponse{}
	for _, task := range s.state.Tasks {
		if task.MachineID == machineID && task.Status == protocol.TaskCancelRequested {
			response.CancelTaskIDs = append(response.CancelTaskIDs, task.ID)
		}
	}
	sort.Strings(response.CancelTaskIDs)
	if req.AvailableSlots > 0 {
		var candidates []*Task
		for _, task := range s.state.Tasks {
			if task.MachineID != machineID {
				continue
			}
			if task.Status == protocol.TaskQueued || task.Status == protocol.TaskDispatching && task.LeaseUntil != nil && now.After(*task.LeaseUntil) {
				candidates = append(candidates, task)
			}
		}
		sort.Slice(candidates, func(i, j int) bool { return candidates[i].CreatedAt.Before(candidates[j].CreatedAt) })
		if len(candidates) > 0 {
			task := candidates[0]
			lease := now.Add(45 * time.Second)
			task.Status = protocol.TaskDispatching
			task.DispatchedAt = &now
			task.LeaseUntil = &lease
			response.Task = &protocol.TaskCommand{
				ID: task.ID, Command: task.Command, CWD: task.CWD, Env: cloneMap(task.Env),
				TimeoutSeconds: task.TimeoutSeconds, CreatedAt: task.CreatedAt,
			}
		}
	}
	if err := s.saveLocked(); err != nil {
		return protocol.PollResponse{}, err
	}
	return response, nil
}

func updateMachineMetadata(machine *Machine, metadata protocol.AgentMetadata) {
	if value := strings.TrimSpace(metadata.Name); value != "" {
		machine.Name = value
	}
	if value := strings.TrimSpace(metadata.Hostname); value != "" {
		machine.Hostname = value
	}
	if value := strings.TrimSpace(metadata.OS); value != "" {
		machine.OS = value
	}
	if value := strings.TrimSpace(metadata.Arch); value != "" {
		machine.Arch = value
	}
	if value := strings.TrimSpace(metadata.Version); value != "" {
		machine.Version = value
	}
	if value := strings.TrimSpace(metadata.DefaultCWD); value != "" {
		machine.DefaultCWD = value
	}
}

func (s *Store) UpdateTask(machineID, taskID string, req protocol.TaskUpdateRequest) (Task, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	task := s.state.Tasks[taskID]
	if task == nil || task.MachineID != machineID {
		return Task{}, fmt.Errorf("task %s not found", taskID)
	}
	if terminalStatus(task.Status) {
		return cloneTask(task), nil
	}
	switch req.Status {
	case protocol.TaskRunning, protocol.TaskCompleted, protocol.TaskFailed, protocol.TaskCanceled:
	default:
		return Task{}, fmt.Errorf("invalid task status %q", req.Status)
	}
	if task.Status == protocol.TaskCancelRequested && req.Status == protocol.TaskFailed {
		req.Status = protocol.TaskCanceled
	}
	task.Status = req.Status
	task.ExitCode = req.ExitCode
	task.Error = strings.TrimSpace(req.Error)
	if req.StartedAt != nil {
		started := req.StartedAt.UTC()
		task.StartedAt = &started
	}
	if req.FinishedAt != nil {
		finished := req.FinishedAt.UTC()
		task.FinishedAt = &finished
	}
	if (req.Status == protocol.TaskCompleted || req.Status == protocol.TaskFailed || req.Status == protocol.TaskCanceled) && task.FinishedAt == nil {
		now := time.Now().UTC()
		task.FinishedAt = &now
	}
	task.LeaseUntil = nil
	if err := s.saveLocked(); err != nil {
		return Task{}, err
	}
	s.notifyLocked()
	return cloneTask(task), nil
}

func (s *Store) AppendOutput(machineID, taskID string, offset int64, data []byte) (int64, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	task := s.state.Tasks[taskID]
	if task == nil || task.MachineID != machineID {
		return 0, fmt.Errorf("task %s not found", taskID)
	}
	if offset != task.OutputBytes {
		return task.OutputBytes, nil
	}
	if len(data) == 0 {
		return task.OutputBytes, nil
	}
	file, err := os.OpenFile(s.outputPath(taskID), os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		return task.OutputBytes, err
	}
	n, writeErr := file.Write(data)
	closeErr := file.Close()
	task.OutputBytes += int64(n)
	if writeErr != nil {
		return task.OutputBytes, writeErr
	}
	if closeErr != nil {
		return task.OutputBytes, closeErr
	}
	if err := s.saveLocked(); err != nil {
		return task.OutputBytes, err
	}
	s.notifyLocked()
	return task.OutputBytes, nil
}

func (s *Store) ReadOutput(taskID string, offset int64, limit int) ([]byte, int64, bool, error) {
	if limit <= 0 {
		limit = 32 * 1024
	}
	if limit > 1024*1024 {
		limit = 1024 * 1024
	}
	s.mu.Lock()
	task := s.state.Tasks[taskID]
	if task == nil {
		s.mu.Unlock()
		return nil, 0, false, fmt.Errorf("task %s not found", taskID)
	}
	total := task.OutputBytes
	path := s.outputPath(taskID)
	s.mu.Unlock()
	if offset < 0 || offset > total {
		return nil, total, false, errors.New("invalid output offset")
	}
	if offset == total {
		return []byte{}, total, false, nil
	}
	file, err := os.Open(path)
	if err != nil {
		return nil, offset, false, err
	}
	defer file.Close()
	want := min(int64(limit), total-offset)
	data := make([]byte, int(want))
	n, err := file.ReadAt(data, offset)
	if err != nil && !errors.Is(err, io.EOF) {
		return nil, offset, false, err
	}
	next := offset + int64(n)
	return data[:n], next, next < total, nil
}

func (s *Store) Changed() <-chan struct{} {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.changed
}

func (s *Store) outputPath(taskID string) string {
	return filepath.Join(s.outputDir, taskID+".log")
}

func (s *Store) notifyLocked() {
	close(s.changed)
	s.changed = make(chan struct{})
}

func (s *Store) saveLocked() error {
	data, err := json.MarshalIndent(s.state, "", "  ")
	if err != nil {
		return err
	}
	temp, err := os.CreateTemp(s.dir, "state-*.tmp")
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
	return os.Rename(tempName, s.statePath)
}

func cloneTask(task *Task) Task {
	copy := *task
	copy.Env = cloneMap(task.Env)
	return copy
}

func cloneMap(source map[string]string) map[string]string {
	if len(source) == 0 {
		return nil
	}
	result := make(map[string]string, len(source))
	for key, value := range source {
		result[key] = value
	}
	return result
}

func randomID(prefix string) (string, error) {
	value, err := randomToken(12)
	if err != nil {
		return "", err
	}
	return prefix + "-" + value, nil
}

func randomToken(bytes int) (string, error) {
	data := make([]byte, bytes)
	if _, err := rand.Read(data); err != nil {
		return "", err
	}
	return hex.EncodeToString(data), nil
}

func hashToken(token string) string {
	digest := sha256.Sum256([]byte(token))
	return hex.EncodeToString(digest[:])
}
