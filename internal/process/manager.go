package process

import (
	"context"
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
	mu      sync.RWMutex
	nextID  uint64
	entries map[string]*Entry
	dir     string
}

type Entry struct {
	ID        string
	Command   string
	Cwd       string
	StartedAt time.Time
	cmd       *exec.Cmd
	job       platformJob
	stdinMu   sync.Mutex
	stdin     io.WriteCloser
	output    lockedOutput
	done      chan struct{}
	exitCode  int
	err       error
}

type Info struct {
	ID        string    `json:"id"`
	Command   string    `json:"command"`
	Cwd       string    `json:"cwd"`
	StartedAt time.Time `json:"started_at"`
	Running   bool      `json:"running"`
	ExitCode  *int      `json:"exit_code,omitempty"`
	Error     string    `json:"error,omitempty"`
	Bytes     int       `json:"bytes"`
}

func NewManager(stateDirs ...string) *Manager {
	base := os.TempDir()
	if len(stateDirs) > 0 && stateDirs[0] != "" {
		base = stateDirs[0]
	}
	dir := filepath.Join(base, "process")
	_ = cleanupOldOutputs(dir, 24*time.Hour)
	return &Manager{entries: make(map[string]*Entry), dir: dir}
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
	for _, entry := range entries {
		info, err := entry.Info()
		if err == nil && !entry.IsDir() && info.ModTime().Before(cutoff) {
			_ = os.Remove(filepath.Join(dir, entry.Name()))
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
	running := true
	select {
	case <-e.done:
		running = false
	default:
	}
	info := Info{ID: e.ID, Command: e.Command, Cwd: e.Cwd, StartedAt: e.StartedAt, Running: running, Bytes: e.output.size}
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
		_ = entry.Kill()
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
	mu   sync.Mutex
	file *os.File
	path string
	size int
}

func (o *lockedOutput) Write(p []byte) (int, error) {
	o.mu.Lock()
	defer o.mu.Unlock()
	if o.file == nil {
		return 0, os.ErrClosed
	}
	n, err := o.file.Write(p)
	o.size += n
	return n, err
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
