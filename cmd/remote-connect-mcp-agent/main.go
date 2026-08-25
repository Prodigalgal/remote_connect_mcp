package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"syscall"

	"github.com/Prodigalgal/remote_connect_mcp/internal/agent"
)

var version = "dev"

func main() {
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
	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))
	client, err := agent.New(agent.Config{
		CenterURL: config.centerURL, EnrollmentToken: config.enrollmentToken, Name: config.name,
		DefaultCWD: config.defaultCWD, StateDir: config.stateDir, MaxConcurrency: config.maxConcurrency,
		Version: version, Logger: logger,
	})
	if err != nil {
		return err
	}
	defer client.Close()
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	logger.Info("agent started", "name", config.name, "center", config.centerURL, "os", runtime.GOOS, "arch", runtime.GOARCH, "version", version)
	return client.Run(ctx)
}

type config struct {
	centerURL, enrollmentToken, name, defaultCWD, stateDir string
	maxConcurrency                                         int
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
	result := config{
		centerURL:       strings.TrimRight(strings.TrimSpace(os.Getenv("REMOTE_CONNECT_MCP_AGENT_CENTER_URL")), "/"),
		enrollmentToken: strings.TrimSpace(os.Getenv("REMOTE_CONNECT_MCP_AGENT_ENROLLMENT_TOKEN")),
		name:            env("REMOTE_CONNECT_MCP_AGENT_NAME", hostname),
		defaultCWD:      env("REMOTE_CONNECT_MCP_AGENT_DEFAULT_CWD", cwd),
		stateDir:        stateDir, maxConcurrency: maxConcurrency,
	}
	if result.centerURL == "" || result.enrollmentToken == "" {
		return config{}, errors.New("REMOTE_CONNECT_MCP_AGENT_CENTER_URL and REMOTE_CONNECT_MCP_AGENT_ENROLLMENT_TOKEN are required")
	}
	if strings.ContainsAny(result.enrollmentToken, "\r\n") {
		return config{}, errors.New("agent enrollment token must be one line")
	}
	return result, nil
}

func env(name, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(name)); value != "" {
		return value
	}
	return fallback
}
