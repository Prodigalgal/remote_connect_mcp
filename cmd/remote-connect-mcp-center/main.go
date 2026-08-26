package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/Prodigalgal/remote_connect_mcp/internal/center"
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
	store, err := center.OpenStore(config.stateDir)
	if err != nil {
		return err
	}
	handler, err := center.NewHTTPHandler(store, center.HTTPConfig{
		Version: version, MCPToken: config.mcpToken, AdminToken: config.adminToken,
		EnrollmentToken: config.enrollmentToken, ConsoleHostname: config.consoleHostname,
		ReleaseBaseURL: config.releaseBaseURL, AgentPublicURL: config.agentPublicURL, Logger: logger,
	})
	if err != nil {
		return err
	}
	config.mcpToken = ""
	config.adminToken = ""
	config.enrollmentToken = ""
	server := &http.Server{
		Addr: config.address, Handler: handler, ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout: 75 * time.Second, WriteTimeout: 0,
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	done := make(chan error, 1)
	go func() {
		logger.Info("center started", "address", config.address, "version", version)
		done <- server.ListenAndServe()
	}()
	select {
	case <-ctx.Done():
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		return server.Shutdown(shutdownCtx)
	case err := <-done:
		if errors.Is(err, http.ErrServerClosed) {
			return nil
		}
		return err
	}
}

type config struct {
	address, stateDir, mcpToken, adminToken, enrollmentToken, consoleHostname, releaseBaseURL, agentPublicURL string
}

func loadConfig() (config, error) {
	host := env("REMOTE_CONNECT_MCP_CENTER_HOST", "0.0.0.0")
	port, err := strconv.Atoi(env("REMOTE_CONNECT_MCP_CENTER_PORT", "8080"))
	if err != nil || port < 1 || port > 65535 {
		return config{}, errors.New("invalid REMOTE_CONNECT_MCP_CENTER_PORT")
	}
	result := config{
		address:         host + ":" + strconv.Itoa(port),
		stateDir:        env("REMOTE_CONNECT_MCP_CENTER_STATE_DIR", "/var/lib/remote-connect-mcp-center"),
		mcpToken:        strings.TrimSpace(os.Getenv("REMOTE_CONNECT_MCP_CENTER_MCP_TOKEN")),
		adminToken:      strings.TrimSpace(os.Getenv("REMOTE_CONNECT_MCP_CENTER_ADMIN_TOKEN")),
		enrollmentToken: strings.TrimSpace(os.Getenv("REMOTE_CONNECT_MCP_CENTER_ENROLLMENT_TOKEN")),
		consoleHostname: strings.TrimSpace(os.Getenv("REMOTE_CONNECT_MCP_CENTER_CONSOLE_HOSTNAME")),
		releaseBaseURL:  env("REMOTE_CONNECT_MCP_CENTER_RELEASE_BASE_URL", "https://github.com/Prodigalgal/remote_connect_mcp/releases/download"),
		agentPublicURL:  env("REMOTE_CONNECT_MCP_CENTER_PUBLIC_AGENT_URL", "https://agent.example.invalid"),
	}
	for name, value := range map[string]string{
		"REMOTE_CONNECT_MCP_CENTER_MCP_TOKEN":        result.mcpToken,
		"REMOTE_CONNECT_MCP_CENTER_ADMIN_TOKEN":      result.adminToken,
		"REMOTE_CONNECT_MCP_CENTER_ENROLLMENT_TOKEN": result.enrollmentToken,
	} {
		if value == "" || strings.ContainsAny(value, "\r\n") {
			return config{}, fmt.Errorf("%s is required and must be one line", name)
		}
	}
	return result, nil
}

func env(name, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(name)); value != "" {
		return value
	}
	return fallback
}
