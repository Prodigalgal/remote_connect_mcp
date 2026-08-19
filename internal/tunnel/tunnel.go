package tunnel

import (
	"bufio"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"time"
)

const cloudflaredVersion = "2026.8.2"

type Config struct {
	Executable string
	StateDir   string
	Token      string
	ConfigFile string
	Quick      bool
	LocalURL   string
	PublicURL  string
	Sensitive  []string
	Logger     *slog.Logger
	HTTPClient *http.Client
}

type Manager struct {
	config Config
	cmd    *exec.Cmd
	job    platformJob
	done   chan error
	exited chan struct{}
	mu     sync.RWMutex
	url    string
}

func New(config Config) *Manager {
	if config.Logger == nil {
		config.Logger = slog.Default()
	}
	return &Manager{config: config, done: make(chan error, 1), exited: make(chan struct{}), url: config.PublicURL}
}

func (m *Manager) Enabled() bool {
	return m.config.Token != "" || m.config.ConfigFile != "" || m.config.Quick
}

func (m *Manager) Start(ctx context.Context) error {
	if !m.Enabled() {
		return nil
	}
	executable, err := resolveExecutable(ctx, m.config)
	if err != nil {
		return err
	}
	args := []string{"tunnel", "--no-autoupdate"}
	switch {
	case m.config.Token != "":
		args = append(args, "run", "--token", m.config.Token)
	case m.config.ConfigFile != "":
		args = append(args, "--config", m.config.ConfigFile, "run")
	case m.config.Quick:
		args = append(args, "--url", m.config.LocalURL)
	}

	cmd := exec.CommandContext(ctx, executable, args...)
	cmd.Env = cloudflaredEnvironment()
	configureCommand(cmd)
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return fmt.Errorf("cloudflared stdout: %w", err)
	}
	stderr, err := cmd.StderrPipe()
	if err != nil {
		return fmt.Errorf("cloudflared stderr: %w", err)
	}
	if err := cmd.Start(); err != nil {
		return fmt.Errorf("start cloudflared: %w", err)
	}
	job, err := attachJob(cmd)
	if err != nil {
		_ = cmd.Process.Kill()
		return fmt.Errorf("attach cloudflared lifecycle: %w", err)
	}
	m.cmd = cmd
	m.job = job
	go m.scan(stdout)
	go m.scan(stderr)
	go func() {
		err := cmd.Wait()
		closeJob(m.job)
		m.done <- err
		close(m.exited)
	}()
	m.config.Logger.Info("cloudflare tunnel started", "mode", m.mode())
	return nil
}

func (m *Manager) URL() string {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.url
}

func (m *Manager) WaitForURL(ctx context.Context, timeout time.Duration) string {
	if url := m.URL(); url != "" {
		return endpointURL(url)
	}
	ticker := time.NewTicker(100 * time.Millisecond)
	defer ticker.Stop()
	timer := time.NewTimer(timeout)
	defer timer.Stop()
	for {
		select {
		case <-ctx.Done():
			return ""
		case <-timer.C:
			return ""
		case <-ticker.C:
			if url := m.URL(); url != "" {
				return endpointURL(url)
			}
		}
	}
}

func endpointURL(base string) string {
	base = strings.TrimRight(base, "/")
	if strings.HasSuffix(base, "/mcp") {
		return base
	}
	return base + "/mcp"
}

func (m *Manager) Stop() error {
	if m.cmd == nil || m.cmd.Process == nil {
		return nil
	}
	err := stopProcess(m.cmd, m.job)
	select {
	case <-m.exited:
	case <-time.After(5 * time.Second):
		_ = m.cmd.Process.Kill()
	}
	return err
}

func (m *Manager) Done() <-chan error { return m.done }

func (m *Manager) scan(reader io.Reader) {
	scanner := bufio.NewScanner(reader)
	scanner.Buffer(make([]byte, 4096), 1024*1024)
	for scanner.Scan() {
		line := sanitizeLine(scanner.Text(), append([]string{m.config.Token}, m.config.Sensitive...)...)
		if m.config.Quick {
			if url := quickURL(line); url != "" {
				m.mu.Lock()
				m.url = url
				m.mu.Unlock()
			}
		}
		m.config.Logger.Info("cloudflared", "message", line)
	}
}

func sanitizeLine(line string, values ...string) string {
	for _, value := range values {
		if value != "" {
			line = strings.ReplaceAll(line, value, "[redacted]")
		}
	}
	return line
}

func cloudflaredEnvironment() []string {
	blocked := map[string]bool{
		"REMOTE_CONNECT_MCP_TOKEN":        true,
		"REMOTE_CONNECT_MCP_TUNNEL_TOKEN": true,
		"CLOUDFLARE_API_TOKEN":    true,
		"CLOUDFLARE_API_KEY":      true,
		"CLOUDFLARE_EMAIL":        true,
	}
	environment := make([]string, 0, len(os.Environ()))
	for _, entry := range os.Environ() {
		key, _, found := strings.Cut(entry, "=")
		if found && blocked[strings.ToUpper(key)] {
			continue
		}
		environment = append(environment, entry)
	}
	return environment
}

func (m *Manager) mode() string {
	switch {
	case m.config.Token != "":
		return "token"
	case m.config.ConfigFile != "":
		return "config"
	default:
		return "quick"
	}
}

func quickURL(line string) string {
	start := strings.Index(line, "https://")
	if start < 0 {
		return ""
	}
	rest := line[start:]
	end := strings.IndexAny(rest, " \t\r\n|")
	if end >= 0 {
		rest = rest[:end]
	}
	rest = strings.TrimRight(rest, "/")
	if strings.HasSuffix(rest, ".trycloudflare.com") {
		return rest
	}
	return ""
}

func resolveExecutable(ctx context.Context, config Config) (string, error) {
	if config.Executable != "" {
		path, err := filepath.Abs(config.Executable)
		if err != nil {
			return "", err
		}
		if _, err := os.Stat(path); err != nil {
			return "", fmt.Errorf("cloudflared: %w", err)
		}
		return path, nil
	}
	if path, err := exec.LookPath("cloudflared"); err == nil {
		return path, nil
	}
	return download(ctx, config)
}

type artifact struct {
	name string
	sha  string
}

func currentArtifact() (artifact, error) {
	key := runtime.GOOS + "/" + runtime.GOARCH
	artifacts := map[string]artifact{
		"windows/amd64": {"cloudflared-windows-amd64.exe", "c29eee2b121f5436a642eed69fd9767da7e7b8c510fa50aaa130337f931357b5"},
		"windows/arm64": {"cloudflared-windows-amd64.exe", "c29eee2b121f5436a642eed69fd9767da7e7b8c510fa50aaa130337f931357b5"},
		"linux/amd64":   {"cloudflared-linux-amd64", "fcfb02b575a52ca1af2e3267af4e1517bcdeb30ac48c834c69abaed3c0576ad2"},
		"linux/arm64":   {"cloudflared-linux-arm64", "7747d94570fb390cf47dcb4f9555c193c6355cda9793f0d878d9049e5d6a7790"},
	}
	artifact, ok := artifacts[key]
	if !ok {
		return artifact, fmt.Errorf("cloudflared %s is not published for %s", cloudflaredVersion, key)
	}
	return artifact, nil
}

func download(ctx context.Context, config Config) (string, error) {
	artifact, err := currentArtifact()
	if err != nil {
		return "", err
	}
	dir := filepath.Join(config.StateDir, "bin")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", err
	}
	destination := filepath.Join(dir, artifact.name)
	if verifyFile(destination, artifact.sha) == nil {
		return destination, nil
	}
	url := "https://github.com/cloudflare/cloudflared/releases/download/" + cloudflaredVersion + "/" + artifact.name
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return "", err
	}
	client := config.HTTPClient
	if client == nil {
		client = &http.Client{Timeout: 10 * time.Minute}
	}
	response, err := client.Do(req)
	if err != nil {
		return "", fmt.Errorf("download cloudflared: %w", err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return "", fmt.Errorf("download cloudflared: HTTP %s", response.Status)
	}
	temporary := destination + ".tmp"
	file, err := os.OpenFile(temporary, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o700)
	if err != nil {
		return "", err
	}
	_, copyErr := io.Copy(file, response.Body)
	closeErr := file.Close()
	if copyErr != nil {
		_ = os.Remove(temporary)
		return "", copyErr
	}
	if closeErr != nil {
		_ = os.Remove(temporary)
		return "", closeErr
	}
	if err := verifyFile(temporary, artifact.sha); err != nil {
		_ = os.Remove(temporary)
		return "", err
	}
	_ = os.Remove(destination)
	if err := os.Rename(temporary, destination); err != nil {
		return "", err
	}
	return destination, nil
}

func verifyFile(path, expected string) error {
	file, err := os.Open(path)
	if err != nil {
		return err
	}
	defer file.Close()
	hash := sha256.New()
	if _, err := io.Copy(hash, file); err != nil {
		return err
	}
	actual := hex.EncodeToString(hash.Sum(nil))
	if !strings.EqualFold(actual, expected) {
		return errors.New("cloudflared checksum mismatch")
	}
	return nil
}
