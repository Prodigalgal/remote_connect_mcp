package process

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strconv"
	"sync"
	"time"
)

type Manager struct {
	mu             sync.RWMutex
	nextID         uint64
	entries        map[string]*Entry
	dir            string
	maxOutputBytes int64
}

type Entry struct {
	ID         string
	TaskID     string
	Command    string
	Cwd        string
	StartedAt  time.Time
	cmd        *exec.Cmd
	pid        int
	job        platformJob
	durable    bool
	recordPath string
	stdinMu    sync.Mutex
	stdin      io.WriteCloser
	output     lockedOutput
	done       chan struct{}
	exitCode   int
	err        error
}

type durableRecord struct {
	ID             string    `json:"id"`
	TaskID         string    `json:"task_id"`
	Command        string    `json:"command"`
	Cwd            string    `json:"cwd"`
	StartedAt      time.Time `json:"started_at"`
	PID            int       `json:"pid"`
	OutputPath     string    `json:"output_path"`
	MaxOutputBytes int64     `json:"max_output_bytes,omitempty"`
	ExitCode       *int      `json:"exit_code,omitempty"`
	Error          string    `json:"error,omitempty"`
}

type Info struct {
	ID              string    `json:"id"`
	Command         string    `json:"command"`
	Cwd             string    `json:"cwd"`
	StartedAt       time.Time `json:"started_at"`
	Running         bool      `json:"running"`
	Durable         bool      `json:"durable,omitempty"`
	ExitCode        *int      `json:"exit_code,omitempty"`
	Error           string    `json:"error,omitempty"`
	Bytes           int       `json:"bytes"`
	OutputTruncated bool      `json:"output_truncated,omitempty"`
}

func NewManager(stateDirs ...string) *Manager {
	return newManager(0, stateDirs...)
}

// NewManagerWithLimit creates a process manager that stops retaining output
// after maxOutputBytes.  The child process continues to run; only captured
// output is bounded, preventing a noisy command from exhausting Agent disk.
func NewManagerWithLimit(stateDir string, maxOutputBytes int64) *Manager {
	return newManager(maxOutputBytes, stateDir)
}

func newManager(maxOutputBytes int64, stateDirs ...string) *Manager {
	base := os.TempDir()
	if len(stateDirs) > 0 && stateDirs[0] != "" {
		base = stateDirs[0]
	}
	dir := filepath.Join(base, "process")
	_ = cleanupOldOutputs(dir, 24*time.Hour)
	manager := &Manager{entries: make(map[string]*Entry), dir: dir, maxOutputBytes: maxOutputBytes}
	manager.loadDurableEntries()
	return manager
}

func cleanupOldOutputs(dir string, maxAge time.Duration) error {
	entries, err := os.ReadDir(dir)
	if os.IsNotExist(err) {
		return nil
	}
	if err != nil {
		return err
	}
	cutoff := time.Now().Add(-maxAge)
	protected := make(map[string]struct{})
	for _, entry := range entries {
		if entry.IsDir() || filepath.Ext(entry.Name()) != ".json" {
			continue
		}
		data, readErr := os.ReadFile(filepath.Join(dir, entry.Name()))
		if readErr != nil {
			continue
		}
		var record durableRecord
		if json.Unmarshal(data, &record) == nil && record.OutputPath != "" {
			if outputPath, absErr := filepath.Abs(record.OutputPath); absErr == nil {
				protected[filepath.Clean(outputPath)] = struct{}{}
			}
		}
	}
	for _, entry := range entries {
		info, err := entry.Info()
		logPath := filepath.Join(dir, entry.Name())
		absoluteLogPath, _ := filepath.Abs(logPath)
		_, isProtected := protected[filepath.Clean(absoluteLogPath)]
		if err == nil && !entry.IsDir() && filepath.Ext(entry.Name()) == ".log" && !isProtected && info.ModTime().Before(cutoff) {
			_ = os.Remove(logPath)
		}
	}
	return nil
}

func (m *Manager) Start(ctx context.Context, command, cwd string, env map[string]string) (*Entry, error) {
	if command == "" {
		return nil, errors.New("command is required")
	}
	cmd := shellCommand(command)
	cmd.Dir = cwd
	cmd.Env = append([]string{}, os.Environ()...)
	for key, value := range env {
		cmd.Env = append(cmd.Env, key+"="+value)
	}
	configureProcess(cmd)

	entry := &Entry{Command: command, Cwd: cwd, StartedAt: time.Now(), cmd: cmd, done: make(chan struct{}), exitCode: -1}
	if err := os.MkdirAll(m.dir, 0o700); err != nil {
		return nil, fmt.Errorf("create process output directory: %w", err)
	}
	outputFile, err := os.CreateTemp(m.dir, "process-*.log")
	if err != nil {
		return nil, fmt.Errorf("create process output: %w", err)
	}
	entry.output.file = outputFile
	entry.output.path = outputFile.Name()
	entry.output.maxBytes = m.maxOutputBytes
	cmd.Stdout = &entry.output
	cmd.Stderr = &entry.output
	entry.stdin, err = cmd.StdinPipe()
	if err != nil {
		entry.output.remove()
		return nil, err
	}
	if err := cmd.Start(); err != nil {
		entry.output.remove()
		return nil, err
	}
	entry.pid = cmd.Process.Pid
	job, err := attachJob(cmd)
	if err != nil {
		_ = cmd.Process.Kill()
		_ = cmd.Wait()
		entry.output.remove()
		return nil, err
	}
	entry.job = job
	go func() {
		select {
		case <-ctx.Done():
			_ = killProcessTree(cmd, entry.job)
		case <-entry.done:
		}
	}()

	m.mu.Lock()
	m.nextID++
	entry.ID = strconv.FormatUint(m.nextID, 10)
	m.entries[entry.ID] = entry
	m.mu.Unlock()

	go func() {
		err := cmd.Wait()
		entry.output.mu.Lock()
		entry.err = err
		if cmd.ProcessState != nil {
			entry.exitCode = cmd.ProcessState.ExitCode()
		}
		entry.output.mu.Unlock()
		closeJob(entry.job)
		close(entry.done)
	}()
	return entry, nil
}

// StartDurable launches a non-interactive task whose process and output file
// are deliberately independent of the Agent process lifetime.  The task can
// therefore be discovered and monitored after an Agent restart.  Commands
// that need stdin remain on Start and keep the original attached semantics.
func (m *Manager) StartDurable(taskID, command, cwd string, env map[string]string) (*Entry, error) {
	if command == "" {
		return nil, errors.New("command is required")
	}
	if taskID == "" {
		return nil, errors.New("task id is required")
	}
	if err := os.MkdirAll(m.dir, 0o700); err != nil {
		return nil, fmt.Errorf("create process output directory: %w", err)
	}
	m.mu.Lock()
	m.nextID++
	id := strconv.FormatUint(m.nextID, 10)
	m.mu.Unlock()

	outputPath := filepath.Join(m.dir, "process-"+id+".log")
	outputFile, err := os.OpenFile(outputPath, os.O_CREATE|os.O_TRUNC|os.O_RDWR|os.O_APPEND, 0o600)
	if err != nil {
		return nil, fmt.Errorf("create durable process output: %w", err)
	}
	cmd := shellCommand(command)
	cmd.Dir = cwd
	cmd.Env = append([]string{}, os.Environ()...)
	for key, value := range env {
		cmd.Env = append(cmd.Env, key+"="+value)
	}
	configureDurableProcess(cmd)
	cmd.Stdout = outputFile
	cmd.Stderr = outputFile
	if err := cmd.Start(); err != nil {
		_ = outputFile.Close()
		_ = os.Remove(outputPath)
		return nil, err
	}
	entry := &Entry{
		ID: id, TaskID: taskID, Command: command, Cwd: cwd, StartedAt: time.Now(),
		cmd: cmd, pid: cmd.Process.Pid, durable: true, recordPath: filepath.Join(m.dir, "process-"+id+".json"),
		done: make(chan struct{}), exitCode: -1,
		output: lockedOutput{file: outputFile, path: outputPath, maxBytes: m.maxOutputBytes, direct: true},
	}
	if err := saveDurableRecord(entry); err != nil {
		_ = killPID(entry.pid)
		_ = outputFile.Close()
		_ = os.Remove(outputPath)
		return nil, err
	}
	m.mu.Lock()
	m.entries[id] = entry
	m.mu.Unlock()
	go monitorDurableOutput(entry)
	go func() {
		err := cmd.Wait()
		entry.output.mu.Lock()
		entry.err = err
		if cmd.ProcessState != nil {
			entry.exitCode = cmd.ProcessState.ExitCode()
		}
		entry.output.mu.Unlock()
		_ = saveDurableCompletion(entry)
		close(entry.done)
	}()
	return entry, nil
}

// DurableTask returns a durable process previously started for taskID.  It is
// used by Agent startup to reconnect task output/status reporting.
func (m *Manager) DurableTask(taskID string) (*Entry, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	for _, entry := range m.entries {
		if entry.durable && entry.TaskID == taskID {
			return entry, true
		}
	}
	return nil, false
}

// ForgetTask removes a completed durable process from the local recovery
// inventory. It is safe to call only after the Center accepted the final task
// state; before that point the record is the recovery guarantee.
func (m *Manager) ForgetTask(taskID string) {
	m.mu.Lock()
	var found *Entry
	for id, entry := range m.entries {
		if entry.durable && entry.TaskID == taskID {
			found = entry
			delete(m.entries, id)
			break
		}
	}
	m.mu.Unlock()
	if found == nil {
		return
	}
	found.output.remove()
	if found.recordPath != "" {
		_ = os.Remove(found.recordPath)
	}
}

func (m *Manager) loadDurableEntries() {
	entries, err := os.ReadDir(m.dir)
	if err != nil {
		return
	}
	for _, item := range entries {
		if item.IsDir() || filepath.Ext(item.Name()) != ".json" {
			continue
		}
		data, err := os.ReadFile(filepath.Join(m.dir, item.Name()))
		if err != nil {
			continue
		}
		var record durableRecord
		if err := json.Unmarshal(data, &record); err != nil || record.ID == "" || record.TaskID == "" || record.PID <= 0 || record.OutputPath == "" {
			continue
		}
		output, err := os.OpenFile(record.OutputPath, os.O_CREATE|os.O_RDWR|os.O_APPEND, 0o600)
		if err != nil {
			continue
		}
		maxOutputBytes := record.MaxOutputBytes
		if m.maxOutputBytes > 0 && (maxOutputBytes <= 0 || maxOutputBytes > m.maxOutputBytes) {
			maxOutputBytes = m.maxOutputBytes
		}
		entry := &Entry{
			ID: record.ID, TaskID: record.TaskID, Command: record.Command, Cwd: record.Cwd, StartedAt: record.StartedAt,
			pid: record.PID, durable: true, recordPath: filepath.Join(m.dir, item.Name()),
			done: make(chan struct{}), exitCode: -1,
			output: lockedOutput{file: output, path: record.OutputPath, maxBytes: maxOutputBytes, direct: true},
		}
		if record.IDNum() > m.nextID {
			m.nextID = record.IDNum()
		}
		m.entries[entry.ID] = entry
		if record.ExitCode != nil {
			entry.exitCode = *record.ExitCode
			if record.Error != "" {
				entry.err = errors.New(record.Error)
			}
			close(entry.done)
			continue
		}
		if processAlive(record.PID) {
			go monitorRecoveredEntry(entry)
			continue
		}
		entry.err = errors.New("durable process exited while Agent was offline")
		close(entry.done)
	}
}

func (r durableRecord) IDNum() uint64 {
	value, err := strconv.ParseUint(r.ID, 10, 64)
	if err != nil {
		return 0
	}
	return value
}

func saveDurableRecord(entry *Entry) error {
	record := durableRecord{
		ID: entry.ID, TaskID: entry.TaskID, Command: entry.Command, Cwd: entry.Cwd,
		StartedAt: entry.StartedAt, PID: entry.pid, OutputPath: entry.output.path, MaxOutputBytes: entry.output.maxBytes,
	}
	return writeDurableRecord(entry.recordPath, record)
}

func saveDurableCompletion(entry *Entry) error {
	entry.output.mu.Lock()
	defer entry.output.mu.Unlock()
	code := entry.exitCode
	record := durableRecord{
		ID: entry.ID, TaskID: entry.TaskID, Command: entry.Command, Cwd: entry.Cwd,
		StartedAt: entry.StartedAt, PID: entry.pid, OutputPath: entry.output.path, MaxOutputBytes: entry.output.maxBytes,
		ExitCode: &code,
	}
	if entry.err != nil {
		record.Error = entry.err.Error()
	}
	return writeDurableRecord(entry.recordPath, record)
}

func writeDurableRecord(recordPath string, record durableRecord) error {
	data, err := json.Marshal(record)
	if err != nil {
		return err
	}
	temp, err := os.CreateTemp(filepath.Dir(recordPath), "process-record-*.tmp")
	if err != nil {
		return err
	}
	tempName := temp.Name()
	defer os.Remove(tempName)
	if err := temp.Chmod(0o600); err != nil {
		_ = temp.Close()
		return err
	}
	if _, err := temp.Write(data); err != nil {
		_ = temp.Close()
		return err
	}
	if err := temp.Sync(); err != nil {
		_ = temp.Close()
		return err
	}
	if err := temp.Close(); err != nil {
		return err
	}
	if err := os.Rename(tempName, recordPath); err == nil {
		return nil
	} else {
		// Windows does not replace an existing destination with Rename.  The
		// normal path remains atomic on Unix; this fallback keeps completion
		// records updateable when a prior Agent instance left the record behind.
		_ = os.Remove(recordPath)
		if replaceErr := os.Rename(tempName, recordPath); replaceErr != nil {
			return err
		}
	}
	return nil
}

func monitorRecoveredEntry(entry *Entry) {
	ticker := time.NewTicker(500 * time.Millisecond)
	defer ticker.Stop()
	for range ticker.C {
		entry.output.mu.Lock()
		entry.output.refreshSizeLocked()
		entry.output.mu.Unlock()
		if !processAlive(entry.pid) {
			code, errorText, completed := awaitDurableCompletion(entry.recordPath, 2*time.Second)
			entry.output.mu.Lock()
			if completed {
				entry.exitCode = code
				if errorText != "" {
					entry.err = errors.New(errorText)
				}
			} else if code, alive := processExitCode(entry.pid); !alive && code >= 0 {
				entry.exitCode = code
			} else {
				entry.err = errors.New("durable process exited while Agent was offline")
			}
			entry.output.mu.Unlock()
			close(entry.done)
			return
		}
	}
}

// awaitDurableCompletion gives the original Agent (when it is still shutting
// down) a short window to persist the child's real exit code.  Without this
// grace period, a restarted Agent can observe the process as a zombie before
// cmd.Wait writes the completion record and incorrectly report a failure.
// A crashed parent still falls through to the explicit offline error after the
// bounded timeout, so recovery never blocks indefinitely.
func awaitDurableCompletion(recordPath string, timeout time.Duration) (int, string, bool) {
	deadline := time.Now().Add(timeout)
	for {
		if code, errorText, ok := readDurableCompletion(recordPath); ok {
			return code, errorText, true
		}
		if !time.Now().Before(deadline) {
			return -1, "", false
		}
		time.Sleep(25 * time.Millisecond)
	}
}

func monitorDurableOutput(entry *Entry) {
	ticker := time.NewTicker(500 * time.Millisecond)
	defer ticker.Stop()
	for {
		select {
		case <-entry.done:
			entry.output.mu.Lock()
			entry.output.refreshSizeLocked()
			entry.output.mu.Unlock()
			return
		case <-ticker.C:
			entry.output.mu.Lock()
			entry.output.refreshSizeLocked()
			entry.output.mu.Unlock()
		}
	}
}

func readDurableCompletion(recordPath string) (int, string, bool) {
	data, err := os.ReadFile(recordPath)
	if err != nil {
		return -1, "", false
	}
	var record durableRecord
	if err := json.Unmarshal(data, &record); err != nil || record.ExitCode == nil {
		return -1, "", false
	}
	return *record.ExitCode, record.Error, true
}

func (m *Manager) Get(id string) (*Entry, error) {
	m.mu.RLock()
	entry := m.entries[id]
	m.mu.RUnlock()
	if entry == nil {
		return nil, fmt.Errorf("process %s not found", id)
	}
	return entry, nil
}

func (m *Manager) List() []Info {
	m.mu.RLock()
	entries := make([]*Entry, 0, len(m.entries))
	for _, entry := range m.entries {
		entries = append(entries, entry)
	}
	m.mu.RUnlock()
	sort.Slice(entries, func(i, j int) bool { return entries[i].StartedAt.Before(entries[j].StartedAt) })
	infos := make([]Info, 0, len(entries))
	for _, entry := range entries {
		infos = append(infos, entry.Info())
	}
	return infos
}

func (e *Entry) Info() Info {
	e.output.mu.Lock()
	defer e.output.mu.Unlock()
	e.output.refreshSizeLocked()
	running := true
	select {
	case <-e.done:
		running = false
	default:
	}
	info := Info{ID: e.ID, Command: e.Command, Cwd: e.Cwd, StartedAt: e.StartedAt, Running: running, Durable: e.durable, Bytes: e.output.size, OutputTruncated: e.output.truncated}
	if !running {
		code := e.exitCode
		info.ExitCode = &code
		if e.err != nil {
			info.Error = e.err.Error()
		}
	}
	return info
}

func (e *Entry) Output(offset, limit int) ([]byte, int, bool, error) {
	e.output.mu.Lock()
	defer e.output.mu.Unlock()
	e.output.refreshSizeLocked()
	if e.output.file == nil {
		return nil, 0, false, os.ErrClosed
	}
	if offset < 0 || offset > e.output.size {
		return nil, e.output.size, false, errors.New("invalid offset")
	}
	if limit <= 0 {
		limit = 32 * 1024
	}
	end := min(offset+limit, e.output.size)
	result := make([]byte, end-offset)
	if len(result) > 0 {
		n, err := e.output.file.ReadAt(result, int64(offset))
		if err != nil && !errors.Is(err, io.EOF) {
			return nil, offset, false, err
		}
		result = result[:n]
		end = offset + n
	}
	return result, end, end < e.output.size, nil
}

func (e *Entry) Write(text string) error {
	e.stdinMu.Lock()
	defer e.stdinMu.Unlock()
	if e.stdin == nil {
		return errors.New("stdin unavailable")
	}
	_, err := io.WriteString(e.stdin, text)
	return err
}

func (e *Entry) CloseInput() error {
	e.stdinMu.Lock()
	defer e.stdinMu.Unlock()
	if e.stdin == nil {
		return nil
	}
	err := e.stdin.Close()
	e.stdin = nil
	return err
}

func (e *Entry) Wait() { <-e.done }

func (e *Entry) Kill() error {
	select {
	case <-e.done:
		return nil
	default:
	}
	if e.cmd == nil {
		return killPID(e.pid)
	}
	return killProcessTree(e.cmd, e.job)
}

func (m *Manager) KillAll() {
	m.mu.RLock()
	entries := make([]*Entry, 0, len(m.entries))
	for _, entry := range m.entries {
		entries = append(entries, entry)
	}
	m.mu.RUnlock()
	for _, entry := range entries {
		if !entry.durable {
			_ = entry.Kill()
		}
	}
}

func (m *Manager) Close() {
	m.KillAll()
	m.mu.RLock()
	entries := make([]*Entry, 0, len(m.entries))
	for _, entry := range m.entries {
		entries = append(entries, entry)
	}
	m.mu.RUnlock()
	for _, entry := range entries {
		if entry.durable {
			// Durable children intentionally outlive this Agent instance. Close
			// only the Agent's descriptor and retain record/output for recovery.
			entry.output.closeKeep()
			continue
		}
		finished := false
		select {
		case <-entry.done:
			finished = true
		case <-time.After(5 * time.Second):
			_ = entry.cmd.Process.Kill()
			select {
			case <-entry.done:
				finished = true
			case <-time.After(time.Second):
			}
		}
		if finished {
			entry.output.remove()
		}
	}
}

type lockedOutput struct {
	mu        sync.Mutex
	file      *os.File
	path      string
	size      int
	maxBytes  int64
	truncated bool
	direct    bool
}

func (o *lockedOutput) Write(p []byte) (int, error) {
	o.mu.Lock()
	defer o.mu.Unlock()
	if o.file == nil {
		return 0, os.ErrClosed
	}
	if o.maxBytes > 0 && int64(o.size) >= o.maxBytes {
		o.truncated = true
		return len(p), nil
	}
	write := p
	if o.maxBytes > 0 {
		remaining := o.maxBytes - int64(o.size)
		if int64(len(write)) > remaining {
			write = write[:remaining]
			o.truncated = true
		}
	}
	n, err := o.file.Write(write)
	o.size += n
	if o.truncated && err == nil {
		// Report the original buffer as consumed so os/exec does not turn an
		// intentional capture limit into a failed command.
		return len(p), nil
	}
	return n, err
}

func (o *lockedOutput) refreshSizeLocked() {
	if !o.direct || o.file == nil {
		return
	}
	if info, err := o.file.Stat(); err == nil {
		if o.maxBytes > 0 && info.Size() > o.maxBytes {
			if err := o.file.Truncate(o.maxBytes); err == nil {
				o.truncated = true
			}
			info, _ = o.file.Stat()
		}
		if info != nil {
			o.size = int(info.Size())
		}
	}
}

func (o *lockedOutput) closeKeep() {
	o.mu.Lock()
	defer o.mu.Unlock()
	if o.file != nil {
		_ = o.file.Close()
		o.file = nil
	}
}

func (o *lockedOutput) remove() {
	o.mu.Lock()
	defer o.mu.Unlock()
	if o.file != nil {
		_ = o.file.Close()
		o.file = nil
	}
	if o.path != "" {
		_ = os.Remove(o.path)
		o.path = ""
	}
}
