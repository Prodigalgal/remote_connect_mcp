package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"

	"github.com/Prodigalgal/remote_connect_mcp/internal/agent"
	"github.com/Prodigalgal/remote_connect_mcp/internal/updater"
	"github.com/Prodigalgal/remote_connect_mcp/internal/workspace"
)

var version = "dev"

func main() {
	if len(os.Args) > 1 && os.Args[1] == "apply-update" {
		if err := updater.RunHelper(os.Args[2:]); err != nil {
			fmt.Fprintln(os.Stderr, "update helper fatal:", err)
			os.Exit(1)
		}
		return
	}
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, "fatal:", err)
		os.Exit(1)
	}
}

func run() error {
	config, err := loadConfig()
	if err != nil {
		return err
	}
	return runPlatform(func(ctx context.Context) error {
		return runAgent(ctx, config)
	})
}

func runAgent(ctx context.Context, config config) error {
	logger, closeLog, err := newLogger(config.logFile)
	if err != nil {
		return err
	}
	defer closeLog()
	client, err := agent.New(agent.Config{
		CenterURL: config.centerURL, EnrollmentToken: config.enrollmentToken, Name: config.name, HostID: config.hostID,
		DefaultCWD: config.defaultCWD, ScopeMode: config.scopeMode, WorkspaceRoot: config.workspaceRoot,
		Capabilities: config.capabilities, DesktopEnabled: config.desktopEnabled,
		StateDir: config.stateDir, MaxConcurrency: config.maxConcurrency, MaxOutputBytes: config.maxOutputBytes,
		Version: version, Logger: logger,
	})
	if err != nil {
		return err
	}
	defer client.Close()
	logger.Info("agent started", "name", config.name, "host_id", config.hostID, "center", config.centerURL, "os", runtime.GOOS, "arch", runtime.GOARCH, "version", version, "scope_mode", config.scopeMode, "workspace_root", config.workspaceRoot, "desktop_enabled", config.desktopEnabled)
	return client.Run(ctx)
}

type config struct {
	centerURL, enrollmentToken, name, hostID, defaultCWD, scopeMode, workspaceRoot, stateDir, logFile string
	capabilities                                                                                      []string
	maxConcurrency                                                                                    int
	maxOutputBytes                                                                                    int64
	desktopEnabled                                                                                    bool
}

func loadConfig() (config, error) {
	hostname, _ := os.Hostname()
	cwd, _ := os.Getwd()
	stateDir := strings.TrimSpace(os.Getenv("REMOTE_CONNECT_MCP_AGENT_STATE_DIR"))
	if stateDir == "" {
		if runtime.GOOS == "windows" {
			stateDir = filepath.Join(os.Getenv("ProgramData"), "remote-connect-mcp-agent")
		} else {
			stateDir = "/var/lib/remote-connect-mcp-agent"
		}
	}
	maxConcurrency, err := strconv.Atoi(env("REMOTE_CONNECT_MCP_AGENT_MAX_CONCURRENCY", "1"))
	if err != nil {
		return config{}, errors.New("invalid REMOTE_CONNECT_MCP_AGENT_MAX_CONCURRENCY")
	}
	maxOutputBytes, err := strconv.ParseInt(env("REMOTE_CONNECT_MCP_AGENT_MAX_OUTPUT_BYTES", "67108864"), 10, 64)
	if err != nil || maxOutputBytes < 1024*1024 || maxOutputBytes > 1024*1024*1024 {
		return config{}, errors.New("REMOTE_CONNECT_MCP_AGENT_MAX_OUTPUT_BYTES must be between 1048576 and 1073741824")
	}
	desktopEnabled, err := strconv.ParseBool(env("REMOTE_CONNECT_MCP_AGENT_DESKTOP_ENABLED", "false"))
	if err != nil {
		return config{}, errors.New("invalid REMOTE_CONNECT_MCP_AGENT_DESKTOP_ENABLED")
	}
	result := config{
		centerURL:       strings.TrimRight(strings.TrimSpace(os.Getenv("REMOTE_CONNECT_MCP_AGENT_CENTER_URL")), "/"),
		enrollmentToken: strings.TrimSpace(os.Getenv("REMOTE_CONNECT_MCP_AGENT_ENROLLMENT_TOKEN")),
		name:            env("REMOTE_CONNECT_MCP_AGENT_NAME", hostname),
		hostID:          env("REMOTE_CONNECT_MCP_AGENT_HOST_ID", hostname),
		defaultCWD:      env("REMOTE_CONNECT_MCP_AGENT_DEFAULT_CWD", cwd),
		scopeMode:       env("REMOTE_CONNECT_MCP_AGENT_SCOPE_MODE", workspace.ModeUnrestricted),
		workspaceRoot:   strings.TrimSpace(os.Getenv("REMOTE_CONNECT_MCP_AGENT_WORKSPACE_ROOT")),
		capabilities:    parseCapabilities(os.Getenv("REMOTE_CONNECT_MCP_AGENT_CAPABILITIES")),
		stateDir:        stateDir,
		logFile:         strings.TrimSpace(os.Getenv("REMOTE_CONNECT_MCP_AGENT_LOG_FILE")),
		maxConcurrency:  maxConcurrency, maxOutputBytes: maxOutputBytes, desktopEnabled: desktopEnabled,
	}
	if result.centerURL == "" || result.enrollmentToken == "" {
		return config{}, errors.New("REMOTE_CONNECT_MCP_AGENT_CENTER_URL and REMOTE_CONNECT_MCP_AGENT_ENROLLMENT_TOKEN are required")
	}
	if strings.ContainsAny(result.enrollmentToken, "\r\n") {
		return config{}, errors.New("agent enrollment token must be one line")
	}
	return result, nil
}

func newLogger(logFile string) (*slog.Logger, func(), error) {
	var writer io.Writer = os.Stdout
	closeLog := func() {}
	if logFile != "" {
		path, err := filepath.Abs(logFile)
		if err != nil {
			return nil, closeLog, fmt.Errorf("resolve agent log file: %w", err)
		}
		if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
			return nil, closeLog, fmt.Errorf("create agent log directory: %w", err)
		}
		if info, err := os.Stat(path); err == nil && info.Size() > 20*1024*1024 {
			_ = os.Remove(path + ".1")
			_ = os.Rename(path, path+".1")
		}
		file, err := os.OpenFile(path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600)
		if err != nil {
			return nil, closeLog, fmt.Errorf("open agent log file: %w", err)
		}
		writer = file
		closeLog = func() { _ = file.Close() }
	}
	return slog.New(slog.NewJSONHandler(writer, &slog.HandlerOptions{Level: slog.LevelInfo})), closeLog, nil
}

func env(name, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(name)); value != "" {
		return value
	}
	return fallback
}

func parseCapabilities(value string) []string {
	if strings.TrimSpace(value) == "" {
		return nil
	}
	parts := strings.FieldsFunc(value, func(r rune) bool { return r == ',' || r == '\n' || r == '\r' || r == ';' })
	return workspace.NormalizeCapabilities(parts)
}
