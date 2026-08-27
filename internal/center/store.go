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

const (
	AccessTokenMCP        = "mcp"
	AccessTokenAdmin      = "admin"
	AccessTokenEnrollment = "enrollment"

	UpgradeRunning   = "running"
	UpgradePaused    = "paused"
	UpgradeCompleted = "completed"
	UpgradeCanceled  = "canceled"

	UpgradePending     = "pending"
	UpgradeOffered     = "offered"
	UpgradeDownloading = "downloading"
	UpgradeInstalling  = "installing"
	UpgradeSucceeded   = "completed"
	UpgradeFailed      = "failed"
)

type AccessTokenState struct {
	CurrentHash        string     `json:"current_hash"`
	PreviousHash       string     `json:"previous_hash,omitempty"`
	PreviousValidUntil *time.Time `json:"previous_valid_until,omitempty"`
	EnvironmentHash    string     `json:"environment_hash"`
	UpdatedAt          time.Time  `json:"updated_at"`
	Source             string     `json:"source"`
}

type AccessTokenView struct {
	Kind               string     `json:"kind"`
	UpdatedAt          time.Time  `json:"updated_at"`
	Source             string     `json:"source"`
	PreviousValidUntil *time.Time `json:"previous_valid_until,omitempty"`
}

type EnrollmentToken struct {
	ID               string     `json:"id"`
	Name             string     `json:"name"`
	TokenHash        string     `json:"token_hash"`
	MaxUses          int        `json:"max_uses"`
	Uses             int        `json:"uses"`
	LegacyPersistent bool       `json:"persistent,omitempty"`
	CreatedAt        time.Time  `json:"created_at"`
	ExpiresAt        *time.Time `json:"expires_at,omitempty"`
	LastUsedAt       *time.Time `json:"last_used_at,omitempty"`
	RevokedAt        *time.Time `json:"revoked_at,omitempty"`
}

type EnrollmentTokenView struct {
	ID         string     `json:"id"`
	Name       string     `json:"name"`
	Status     string     `json:"status"`
	MaxUses    int        `json:"max_uses"`
	Uses       int        `json:"uses"`
	CreatedAt  time.Time  `json:"created_at"`
	ExpiresAt  *time.Time `json:"expires_at,omitempty"`
	LastUsedAt *time.Time `json:"last_used_at,omitempty"`
	RevokedAt  *time.Time `json:"revoked_at,omitempty"`
}

type UpgradeTarget struct {
	MachineID  string     `json:"machine_id"`
	Status     string     `json:"status"`
	Error      string     `json:"error,omitempty"`
	Attempts   int        `json:"attempts"`
	UpdatedAt  time.Time  `json:"updated_at"`
	FinishedAt *time.Time `json:"finished_at,omitempty"`
	LeaseUntil *time.Time `json:"lease_until,omitempty"`
}

type UpgradeCampaign struct {
	ID          string                              `json:"id"`
	Version     string                              `json:"version"`
	Status      string                              `json:"status"`
	CanaryCount int                                 `json:"canary_count"`
	BatchSize   int                                 `json:"batch_size"`
	ActiveLimit int                                 `json:"active_limit"`
	Artifacts   map[string]protocol.UpgradeArtifact `json:"artifacts"`
	Targets     []UpgradeTarget                     `json:"targets"`
	CreatedAt   time.Time                           `json:"created_at"`
	UpdatedAt   time.Time                           `json:"updated_at"`
	FinishedAt  *time.Time                          `json:"finished_at,omitempty"`
}

type CreateUpgradeCampaignRequest struct {
	Version     string
	CanaryCount int
	BatchSize   int
	MachineIDs  []string
	Artifacts   map[string]protocol.UpgradeArtifact
}

type persistedState struct {
	Machines     map[string]*Machine          `json:"machines"`
	Tasks        map[string]*Task             `json:"tasks"`
	Upgrades     map[string]*UpgradeCampaign  `json:"upgrades,omitempty"`
	AccessTokens map[string]*AccessTokenState `json:"access_tokens,omitempty"`
	Enrollments  map[string]*EnrollmentToken  `json:"enrollment_tokens,omitempty"`
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
		state: persistedState{
			Machines: map[string]*Machine{}, Tasks: map[string]*Task{}, Upgrades: map[string]*UpgradeCampaign{}, AccessTokens: map[string]*AccessTokenState{}, Enrollments: map[string]*EnrollmentToken{},
		},
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
	if s.state.Upgrades == nil {
		s.state.Upgrades = map[string]*UpgradeCampaign{}
	}
	if s.state.AccessTokens == nil {
		s.state.AccessTokens = map[string]*AccessTokenState{}
	}
	if s.state.Enrollments == nil {
		s.state.Enrollments = map[string]*EnrollmentToken{}
	}
	if migrateLegacyEnrollmentTokens(s.state.Enrollments, time.Now().UTC()) {
		if err := s.saveLocked(); err != nil {
			return nil, fmt.Errorf("persist enrollment token migration: %w", err)
		}
	}
	return s, nil
}

// migrateLegacyEnrollmentTokens invalidates the removed persistent enrollment
// token variant and clamps all historical records to one successful use. The
// plaintext token was never persisted, so revocation is the only safe way to
// retire an old token without affecting Agent identity credentials.
func migrateLegacyEnrollmentTokens(records map[string]*EnrollmentToken, now time.Time) bool {
	changed := false
	for _, record := range records {
		if record == nil {
			continue
		}
		if record.LegacyPersistent {
			revoked := now.UTC()
			if record.RevokedAt == nil {
				record.RevokedAt = &revoked
			}
			record.LegacyPersistent = false
			changed = true
		}
		if record.MaxUses != 1 {
			record.MaxUses = 1
			changed = true
		}
	}
	return changed
}

func (s *Store) ConfigureAccessTokens(tokens map[string]string, now time.Time) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	changed := false
	for _, kind := range accessTokenKinds() {
		token := strings.TrimSpace(tokens[kind])
		if err := validateConfiguredAccessToken(token); err != nil {
			return fmt.Errorf("%s token: %w", kind, err)
		}
		environmentHash := hashToken(token)
		state := s.state.AccessTokens[kind]
		if state == nil || state.CurrentHash == "" {
			s.state.AccessTokens[kind] = &AccessTokenState{
				CurrentHash: environmentHash, EnvironmentHash: environmentHash, UpdatedAt: now.UTC(), Source: "environment",
			}
			changed = true
			continue
		}
		if state.EnvironmentHash != environmentHash {
			state.CurrentHash = environmentHash
			state.EnvironmentHash = environmentHash
			state.PreviousHash = ""
			state.PreviousValidUntil = nil
			state.UpdatedAt = now.UTC()
			state.Source = "environment"
			changed = true
		}
	}
	if !changed {
		return nil
	}
	return s.saveLocked()
}

func (s *Store) AuthenticateAccessToken(kind, token string, now time.Time) bool {
	if token == "" {
		return false
	}
	actual := hashToken(token)
	s.mu.Lock()
	defer s.mu.Unlock()
	state := s.state.AccessTokens[kind]
	if state == nil {
		return false
	}
	if constantHashEqual(actual, state.CurrentHash) {
		return true
	}
	return state.PreviousHash != "" && state.PreviousValidUntil != nil && now.Before(*state.PreviousValidUntil) && constantHashEqual(actual, state.PreviousHash)
}

func (s *Store) RotateAccessToken(kind, token string, grace time.Duration, now time.Time) (AccessTokenView, error) {
	if !validAccessTokenKind(kind) {
		return AccessTokenView{}, errors.New("invalid token kind")
	}
	token = strings.TrimSpace(token)
	if err := validateAccessToken(token); err != nil {
		return AccessTokenView{}, err
	}
	if grace < 0 || grace > 24*time.Hour {
		return AccessTokenView{}, errors.New("grace period must be between 0 and 86400 seconds")
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	state := s.state.AccessTokens[kind]
	if state == nil || state.CurrentHash == "" {
		return AccessTokenView{}, errors.New("access tokens are not configured")
	}
	newHash := hashToken(token)
	if constantHashEqual(newHash, state.CurrentHash) {
		return AccessTokenView{}, errors.New("new token must differ from the active token")
	}
	if grace > 0 {
		until := now.UTC().Add(grace)
		state.PreviousHash = state.CurrentHash
		state.PreviousValidUntil = &until
	} else {
		state.PreviousHash = ""
		state.PreviousValidUntil = nil
	}
	state.CurrentHash = newHash
	state.UpdatedAt = now.UTC()
	state.Source = "center"
	if err := s.saveLocked(); err != nil {
		return AccessTokenView{}, err
	}
	return accessTokenView(kind, state, now), nil
}

func (s *Store) ListAccessTokens(now time.Time) []AccessTokenView {
	s.mu.Lock()
	defer s.mu.Unlock()
	result := make([]AccessTokenView, 0, 3)
	for _, kind := range accessTokenKinds() {
		if state := s.state.AccessTokens[kind]; state != nil {
			result = append(result, accessTokenView(kind, state, now))
		}
	}
	return result
}

func accessTokenView(kind string, state *AccessTokenState, now time.Time) AccessTokenView {
	view := AccessTokenView{Kind: kind, UpdatedAt: state.UpdatedAt, Source: state.Source}
	if state.PreviousValidUntil != nil && now.Before(*state.PreviousValidUntil) {
		until := *state.PreviousValidUntil
		view.PreviousValidUntil = &until
	}
	return view
}

func accessTokenKinds() []string {
	return []string{AccessTokenMCP, AccessTokenAdmin, AccessTokenEnrollment}
}

func validAccessTokenKind(kind string) bool {
	return kind == AccessTokenMCP || kind == AccessTokenAdmin || kind == AccessTokenEnrollment
}

func validateAccessToken(token string) error {
	if len(token) < 32 || len(token) > 4096 || strings.ContainsAny(token, "\r\n") {
		return errors.New("token must be one line and between 32 and 4096 characters")
	}
	return nil
}

func validateConfiguredAccessToken(token string) error {
	if token == "" || len(token) > 4096 || strings.ContainsAny(token, "\r\n") {
		return errors.New("token must be a non-empty line with at most 4096 characters")
	}
	return nil
}

func constantHashEqual(actual, expected string) bool {
	return len(actual) == len(expected) && subtle.ConstantTimeCompare([]byte(actual), []byte(expected)) == 1
}

func (s *Store) CreateEnrollmentToken(name string, ttl time.Duration, now time.Time) (EnrollmentTokenView, string, error) {
	name = strings.TrimSpace(name)
	if !validEnrollmentName(name) {
		return EnrollmentTokenView{}, "", errors.New("machine name must contain only letters, digits, dots, underscores, or hyphens and be at most 128 characters")
	}
	if ttl < 5*time.Minute || ttl > 30*24*time.Hour {
		return EnrollmentTokenView{}, "", errors.New("token lifetime must be between 300 and 2592000 seconds")
	}
	id, err := randomID("enrollment")
	if err != nil {
		return EnrollmentTokenView{}, "", err
	}
	random, err := randomToken(32)
	if err != nil {
		return EnrollmentTokenView{}, "", err
	}
	token := "rcmcp_enroll_" + random
	created := now.UTC()
	expires := created.Add(ttl)
	record := &EnrollmentToken{ID: id, Name: name, TokenHash: hashToken(token), MaxUses: 1, CreatedAt: created, ExpiresAt: &expires}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.state.Enrollments[id] = record
	if err := s.saveLocked(); err != nil {
		delete(s.state.Enrollments, id)
		return EnrollmentTokenView{}, "", err
	}
	return enrollmentTokenView(record, created), token, nil
}

func (s *Store) ListEnrollmentTokens(now time.Time, limit int) []EnrollmentTokenView {
	if limit <= 0 || limit > 200 {
		limit = 100
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	records := make([]*EnrollmentToken, 0, len(s.state.Enrollments))
	for _, record := range s.state.Enrollments {
		records = append(records, record)
	}
	sort.Slice(records, func(i, j int) bool { return records[i].CreatedAt.After(records[j].CreatedAt) })
	if len(records) > limit {
		records = records[:limit]
	}
	result := make([]EnrollmentTokenView, 0, len(records))
	for _, record := range records {
		result = append(result, enrollmentTokenView(record, now.UTC()))
	}
	return result
}

func (s *Store) RevokeEnrollmentToken(id string, now time.Time) (EnrollmentTokenView, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	record := s.state.Enrollments[strings.TrimSpace(id)]
	if record == nil {
		return EnrollmentTokenView{}, errors.New("enrollment token not found")
	}
	if record.RevokedAt == nil {
		revoked := now.UTC()
		record.RevokedAt = &revoked
		if err := s.saveLocked(); err != nil {
			return EnrollmentTokenView{}, err
		}
	}
	return enrollmentTokenView(record, now.UTC()), nil
}

func (s *Store) RegisterWithScopedEnrollmentToken(req protocol.RegisterRequest, token string, now time.Time) (protocol.RegisterResponse, bool, error) {
	actual := hashToken(token)
	s.mu.Lock()
	defer s.mu.Unlock()
	var matched *EnrollmentToken
	for _, record := range s.state.Enrollments {
		if constantHashEqual(actual, record.TokenHash) {
			matched = record
			break
		}
	}
	if matched == nil {
		return protocol.RegisterResponse{}, false, nil
	}
	effectiveName := registrationName(req)
	expired := matched.ExpiresAt != nil && !now.Before(*matched.ExpiresAt)
	used := matched.MaxUses > 0 && matched.Uses >= matched.MaxUses
	if matched.RevokedAt != nil || expired || used || !strings.EqualFold(effectiveName, matched.Name) {
		return protocol.RegisterResponse{}, true, errors.New("invalid enrollment token")
	}
	response, err := s.registerLocked(req, now.UTC())
	if err != nil {
		return protocol.RegisterResponse{}, true, err
	}
	consumedAt := now.UTC()
	matched.Uses++
	matched.LastUsedAt = &consumedAt
	if err := s.saveLocked(); err != nil {
		return protocol.RegisterResponse{}, true, err
	}
	s.notifyLocked()
	return response, true, nil
}

func enrollmentTokenView(record *EnrollmentToken, now time.Time) EnrollmentTokenView {
	status := "active"
	if record.RevokedAt != nil {
		status = "revoked"
	} else if record.ExpiresAt != nil && !now.Before(*record.ExpiresAt) {
		status = "expired"
	} else if record.MaxUses > 0 && record.Uses >= record.MaxUses {
		status = "used"
	}
	return EnrollmentTokenView{
		ID: record.ID, Name: record.Name, Status: status, MaxUses: record.MaxUses, Uses: record.Uses,
		CreatedAt: record.CreatedAt, ExpiresAt: record.ExpiresAt, LastUsedAt: record.LastUsedAt, RevokedAt: record.RevokedAt,
	}
}

func registrationName(req protocol.RegisterRequest) string {
	name := strings.TrimSpace(req.Name)
	if name == "" {
		name = strings.TrimSpace(req.Hostname)
	}
	return name
}

func validEnrollmentName(name string) bool {
	if name == "" || len(name) > 128 {
		return false
	}
	for index, character := range name {
		valid := character >= 'a' && character <= 'z' || character >= 'A' && character <= 'Z' || character >= '0' && character <= '9' || character == '.' || character == '_' || character == '-'
		if !valid || index == 0 && (character == '.' || character == '_' || character == '-') {
			return false
		}
	}
	return true
}

func (s *Store) Register(req protocol.RegisterRequest) (protocol.RegisterResponse, error) {
	name := registrationName(req)
	if name == "" {
		return protocol.RegisterResponse{}, errors.New("machine name is required")
	}
	now := time.Now().UTC()
	s.mu.Lock()
	defer s.mu.Unlock()
	response, err := s.registerLocked(req, now)
	if err != nil {
		return protocol.RegisterResponse{}, err
	}
	if err := s.saveLocked(); err != nil {
		return protocol.RegisterResponse{}, err
	}
	s.notifyLocked()
	return response, nil
}

func (s *Store) registerLocked(req protocol.RegisterRequest, now time.Time) (protocol.RegisterResponse, error) {
	name := registrationName(req)
	if name == "" {
		return protocol.RegisterResponse{}, errors.New("machine name is required")
	}
	token, err := randomToken(32)
	if err != nil {
		return protocol.RegisterResponse{}, err
	}
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

func (s *Store) CreateUpgradeCampaign(req CreateUpgradeCampaignRequest) (UpgradeCampaign, error) {
	version := strings.TrimSpace(req.Version)
	if version == "" {
		return UpgradeCampaign{}, errors.New("upgrade version is required")
	}
	if len(req.MachineIDs) == 0 {
		return UpgradeCampaign{}, errors.New("at least one machine is required")
	}
	id, err := randomID("upgrade")
	if err != nil {
		return UpgradeCampaign{}, err
	}
	now := time.Now().UTC()
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, campaign := range s.state.Upgrades {
		if campaign.Status == UpgradeRunning || campaign.Status == UpgradePaused {
			return UpgradeCampaign{}, fmt.Errorf("upgrade campaign %s is still %s", campaign.ID, campaign.Status)
		}
	}
	seen := map[string]bool{}
	targets := make([]UpgradeTarget, 0, len(req.MachineIDs))
	for _, machineID := range req.MachineIDs {
		machineID = strings.TrimSpace(machineID)
		machine := s.state.Machines[machineID]
		if machine == nil {
			return UpgradeCampaign{}, fmt.Errorf("machine %s not found", machineID)
		}
		if seen[machineID] {
			continue
		}
		seen[machineID] = true
		key := machine.OS + "/" + machine.Arch
		artifact, ok := req.Artifacts[key]
		if !ok || strings.TrimSpace(artifact.URL) == "" || len(strings.TrimSpace(artifact.SHA256)) != 64 {
			return UpgradeCampaign{}, fmt.Errorf("upgrade artifact for %s is unavailable", key)
		}
		status := UpgradePending
		finishedAt := (*time.Time)(nil)
		if machine.Version == version {
			status = UpgradeSucceeded
			finishedAt = &now
		}
		targets = append(targets, UpgradeTarget{MachineID: machineID, Status: status, UpdatedAt: now, FinishedAt: finishedAt})
	}
	if len(targets) == 0 {
		return UpgradeCampaign{}, errors.New("upgrade target list is empty")
	}
	canary := req.CanaryCount
	if canary <= 0 {
		canary = 1
	}
	if canary > len(targets) {
		canary = len(targets)
	}
	batch := req.BatchSize
	if batch <= 0 {
		batch = 3
	}
	campaign := &UpgradeCampaign{
		ID: id, Version: version, Status: UpgradeRunning, CanaryCount: canary, BatchSize: batch,
		ActiveLimit: canary, Artifacts: cloneArtifacts(req.Artifacts), Targets: targets,
		CreatedAt: now, UpdatedAt: now,
	}
	reconcileUpgradeLocked(campaign, s.state.Machines, now)
	s.state.Upgrades[id] = campaign
	if err := s.saveLocked(); err != nil {
		delete(s.state.Upgrades, id)
		return UpgradeCampaign{}, err
	}
	s.notifyLocked()
	return cloneUpgradeCampaign(campaign), nil
}

func (s *Store) ListUpgradeCampaigns(limit int) []UpgradeCampaign {
	if limit <= 0 || limit > 100 {
		limit = 20
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	result := make([]UpgradeCampaign, 0, len(s.state.Upgrades))
	for _, campaign := range s.state.Upgrades {
		result = append(result, cloneUpgradeCampaign(campaign))
	}
	sort.Slice(result, func(i, j int) bool { return result[i].CreatedAt.After(result[j].CreatedAt) })
	if len(result) > limit {
		result = result[:limit]
	}
	return result
}

func (s *Store) ControlUpgradeCampaign(id, action string) (UpgradeCampaign, error) {
	now := time.Now().UTC()
	s.mu.Lock()
	defer s.mu.Unlock()
	campaign := s.state.Upgrades[id]
	if campaign == nil {
		return UpgradeCampaign{}, fmt.Errorf("upgrade campaign %s not found", id)
	}
	switch action {
	case "resume":
		if campaign.Status == UpgradeCompleted || campaign.Status == UpgradeCanceled {
			return UpgradeCampaign{}, fmt.Errorf("upgrade campaign %s is already terminal", id)
		}
		for index := range campaign.Targets {
			target := &campaign.Targets[index]
			if target.Status == UpgradeFailed {
				target.Status = UpgradePending
				target.Error = ""
				target.LeaseUntil = nil
				target.FinishedAt = nil
				target.UpdatedAt = now
			}
		}
		campaign.Status = UpgradeRunning
		campaign.FinishedAt = nil
		reconcileUpgradeLocked(campaign, s.state.Machines, now)
	case "cancel":
		if campaign.Status != UpgradeCompleted {
			campaign.Status = UpgradeCanceled
			campaign.FinishedAt = &now
		}
	default:
		return UpgradeCampaign{}, fmt.Errorf("unsupported upgrade action %q", action)
	}
	campaign.UpdatedAt = now
	if err := s.saveLocked(); err != nil {
		return UpgradeCampaign{}, err
	}
	s.notifyLocked()
	return cloneUpgradeCampaign(campaign), nil
}

func (s *Store) UpdateUpgradeStatus(machineID string, req protocol.UpgradeStatusRequest) (UpgradeCampaign, error) {
	now := time.Now().UTC()
	s.mu.Lock()
	defer s.mu.Unlock()
	campaign := s.state.Upgrades[strings.TrimSpace(req.CampaignID)]
	if campaign == nil {
		return UpgradeCampaign{}, fmt.Errorf("upgrade campaign %s not found", req.CampaignID)
	}
	var target *UpgradeTarget
	for index := range campaign.Targets {
		if campaign.Targets[index].MachineID == machineID {
			target = &campaign.Targets[index]
			break
		}
	}
	if target == nil {
		return UpgradeCampaign{}, fmt.Errorf("machine %s is not part of campaign %s", machineID, campaign.ID)
	}
	switch req.Status {
	case UpgradeDownloading, UpgradeInstalling:
		lease := now.Add(15 * time.Minute)
		target.Status = req.Status
		target.LeaseUntil = &lease
		target.Error = ""
	case UpgradeFailed:
		target.Status = UpgradeFailed
		target.Error = strings.TrimSpace(req.Error)
		target.LeaseUntil = nil
		target.FinishedAt = &now
		campaign.Status = UpgradePaused
	case UpgradeSucceeded:
		target.Status = UpgradeSucceeded
		target.Error = ""
		target.LeaseUntil = nil
		target.FinishedAt = &now
	default:
		return UpgradeCampaign{}, fmt.Errorf("invalid upgrade status %q", req.Status)
	}
	target.UpdatedAt = now
	campaign.UpdatedAt = now
	reconcileUpgradeLocked(campaign, s.state.Machines, now)
	if err := s.saveLocked(); err != nil {
		return UpgradeCampaign{}, err
	}
	s.notifyLocked()
	return cloneUpgradeCampaign(campaign), nil
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
	if len(req.RunningTaskIDs) == 0 && len(response.CancelTaskIDs) == 0 {
		response.Upgrade = s.upgradePlanLocked(machineID, now)
	}
	if response.Upgrade == nil && req.AvailableSlots > 0 {
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

func (s *Store) upgradePlanLocked(machineID string, now time.Time) *protocol.UpgradePlan {
	for _, campaign := range s.state.Upgrades {
		if campaign.Status != UpgradeRunning {
			continue
		}
		reconcileUpgradeLocked(campaign, s.state.Machines, now)
		for index := range campaign.Targets {
			target := &campaign.Targets[index]
			if target.MachineID != machineID || index >= campaign.ActiveLimit || target.Status == UpgradeSucceeded {
				continue
			}
			if target.LeaseUntil != nil && now.Before(*target.LeaseUntil) && target.Status != UpgradePending {
				if target.Status != UpgradeOffered || now.Sub(target.UpdatedAt) < 30*time.Second {
					return nil
				}
			}
			machine := s.state.Machines[machineID]
			if machine == nil || machine.Version == campaign.Version {
				return nil
			}
			artifact, ok := campaign.Artifacts[machine.OS+"/"+machine.Arch]
			if !ok {
				target.Status = UpgradeFailed
				target.Error = "artifact is unavailable for " + machine.OS + "/" + machine.Arch
				target.FinishedAt = &now
				campaign.Status = UpgradePaused
				return nil
			}
			lease := now.Add(30 * time.Second)
			target.Status = UpgradeOffered
			target.Attempts++
			target.UpdatedAt = now
			target.LeaseUntil = &lease
			campaign.UpdatedAt = now
			return &protocol.UpgradePlan{CampaignID: campaign.ID, Version: campaign.Version, URL: artifact.URL, SHA256: artifact.SHA256}
		}
	}
	return nil
}

func reconcileUpgradeLocked(campaign *UpgradeCampaign, machines map[string]*Machine, now time.Time) {
	if campaign.Status == UpgradeCanceled {
		return
	}
	allCompleted := true
	for index := range campaign.Targets {
		target := &campaign.Targets[index]
		if machine := machines[target.MachineID]; machine != nil && machine.Version == campaign.Version && target.Status != UpgradeSucceeded {
			target.Status = UpgradeSucceeded
			target.Error = ""
			target.LeaseUntil = nil
			target.UpdatedAt = now
			target.FinishedAt = &now
		}
		if target.Status == UpgradeFailed {
			campaign.Status = UpgradePaused
		}
		if target.Status != UpgradeSucceeded {
			allCompleted = false
		}
	}
	if allCompleted {
		campaign.Status = UpgradeCompleted
		campaign.ActiveLimit = len(campaign.Targets)
		campaign.FinishedAt = &now
		campaign.UpdatedAt = now
		return
	}
	if campaign.Status != UpgradeRunning {
		return
	}
	for campaign.ActiveLimit < len(campaign.Targets) {
		waveCompleted := true
		for index := 0; index < campaign.ActiveLimit; index++ {
			if campaign.Targets[index].Status != UpgradeSucceeded {
				waveCompleted = false
				break
			}
		}
		if !waveCompleted {
			break
		}
		campaign.ActiveLimit = min(campaign.ActiveLimit+campaign.BatchSize, len(campaign.Targets))
		campaign.UpdatedAt = now
	}
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

func cloneArtifacts(source map[string]protocol.UpgradeArtifact) map[string]protocol.UpgradeArtifact {
	if len(source) == 0 {
		return nil
	}
	result := make(map[string]protocol.UpgradeArtifact, len(source))
	for key, value := range source {
		result[key] = value
	}
	return result
}

func cloneUpgradeCampaign(campaign *UpgradeCampaign) UpgradeCampaign {
	copy := *campaign
	copy.Artifacts = cloneArtifacts(campaign.Artifacts)
	copy.Targets = append([]UpgradeTarget(nil), campaign.Targets...)
	return copy
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
