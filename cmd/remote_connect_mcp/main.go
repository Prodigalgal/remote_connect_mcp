package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"time"

	"github.com/modelcontextprotocol/go-sdk/mcp"

	"github.com/Prodigalgal/remote_connect_mcp/internal/auth"
	cloudflareapi "github.com/Prodigalgal/remote_connect_mcp/internal/cloudflare"
	"github.com/Prodigalgal/remote_connect_mcp/internal/config"
	processes "github.com/Prodigalgal/remote_connect_mcp/internal/process"
	"github.com/Prodigalgal/remote_connect_mcp/internal/tools"
	"github.com/Prodigalgal/remote_connect_mcp/internal/tunnel"
)

var version = "dev"

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, "fatal:", err)
		os.Exit(1)
	}
}

func run() error {
	cfg, err := config.Load()
	if err != nil {
		return err
	}
	logger := slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))
	processManager := processes.NewManager(cfg.StateDir)
	defer processManager.Close()

	server := mcp.NewServer(&mcp.Implementation{Name: "remote_connect_mcp", Version: version}, &mcp.ServerOptions{
		Instructions: "The default cwd is only a convenience, not a sandbox or access boundary. Authenticated tools can access any local path and run any command. Prefer search/read in small pages and follow cursors. Use apply_patch for precise edits and exec_command for builds or tests.",
		PageSize:     20,
		KeepAlive:    30 * time.Second,
	})
	toolService := &tools.Service{
		DefaultCWD: cfg.DefaultCWD, Version: version, Processes: processManager, Logger: logger,
	}
	toolService.Register(server)

	mcpHandler := mcp.NewStreamableHTTPHandler(func(*http.Request) *mcp.Server { return server }, &mcp.StreamableHTTPOptions{
		// ChatGPT Web uses the 2026-07-28 stateless MCP flow after discovery.
		// Stateless is required for that protocol version on Streamable HTTP.
		Stateless:                   true,
		SessionTimeout:             time.Hour,
		DisableLocalhostProtection: true,
	})
	mux := http.NewServeMux()
	authenticatedMCP := auth.Bearer(cfg.Token, mcpHandler)
	// ChatGPT first probes the origin root during connector discovery, even
	// when its configured URL ends in /mcp. Keep /mcp as the canonical URL,
	// and make / an authenticated compatibility alias for that probe.
	mux.Handle("/mcp", authenticatedMCP)
	mux.Handle("/", authenticatedMCP)
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]string{"status": "ok", "version": version})
	})
	handler := requestAudit(logger, cfg.MaxRequestBody, mux)
	httpServer := &http.Server{
		Handler:           handler,
		ReadHeaderTimeout: 15 * time.Second,
		IdleTimeout:       2 * time.Minute,
	}
	listener, err := net.Listen("tcp", cfg.Address())
	if err != nil {
		return fmt.Errorf("listen on %s: %w", cfg.Address(), err)
	}
	ctx, cancel := signal.NotifyContext(context.Background(), shutdownSignals()...)
	defer cancel()

	if cfg.Cloudflare.AutoProvision {
		logger.Info("cloudflare provisioning started", "hostname", cfg.Cloudflare.Hostname, "tunnel", cfg.Cloudflare.TunnelName)
		provisioned, err := cloudflareapi.Provision(ctx, cloudflareapi.Config{
			APIToken: cfg.Cloudflare.APIToken, AccountID: cfg.Cloudflare.AccountID,
			ZoneID: cfg.Cloudflare.ZoneID, ZoneName: cfg.Cloudflare.ZoneName,
			Hostname: cfg.Cloudflare.Hostname, TunnelName: cfg.Cloudflare.TunnelName,
			OriginURL: cfg.OriginURL(),
		})
		if err != nil {
			_ = listener.Close()
			return fmt.Errorf("Cloudflare auto-provisioning failed: %w", err)
		}
		cfg.TunnelToken = provisioned.TunnelToken
		cfg.PublicURL = "https://" + provisioned.Hostname
		logger.Info("cloudflare provisioning complete",
			"hostname", provisioned.Hostname,
			"account_id", provisioned.AccountID,
			"zone_id", provisioned.ZoneID,
			"tunnel_id", provisioned.TunnelID,
			"tunnel_created", provisioned.TunnelCreated,
			"dns_action", provisioned.DNSAction,
		)
	}

	tunnelManager := tunnel.New(tunnel.Config{
		Executable: cfg.Cloudflared, StateDir: cfg.StateDir, Token: cfg.TunnelToken,
		ConfigFile: cfg.TunnelConfig, Quick: cfg.QuickTunnel, LocalURL: cfg.OriginURL(),
		PublicURL: cfg.PublicURL,
		Sensitive: []string{cfg.Token, cfg.Cloudflare.APIToken},
		Logger:    logger,
	})
	if err := tunnelManager.Start(ctx); err != nil {
		_ = listener.Close()
		return err
	}
	defer tunnelManager.Stop()

	serveDone := make(chan error, 1)
	go func() { serveDone <- httpServer.Serve(listener) }()
	printBanner(cfg, tunnelManager, ctx)

	var tunnelDone <-chan error
	if tunnelManager.Enabled() {
		tunnelDone = tunnelManager.Done()
	}
	select {
	case <-ctx.Done():
		logger.Info("shutdown requested")
	case err := <-serveDone:
		if err != nil && !errors.Is(err, http.ErrServerClosed) {
			return err
		}
	case err := <-tunnelDone:
		if err != nil {
			return fmt.Errorf("cloudflared stopped: %w", err)
		}
		return errors.New("cloudflared stopped")
	}

	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer shutdownCancel()
	return httpServer.Shutdown(shutdownCtx)
}

func printBanner(cfg config.Config, manager *tunnel.Manager, ctx context.Context) {
	publicURL := ""
	if manager.Enabled() {
		publicURL = manager.WaitForURL(ctx, 12*time.Second)
	}
	if publicURL == "" && cfg.PublicURL != "" {
		publicURL = endpointURL(cfg.PublicURL)
	}
	fmt.Println()
	fmt.Println("remote_connect_mcp", version)
	fmt.Println("Config    :", cfg.EnvFile)
	fmt.Println("Default CWD:", cfg.DefaultCWD)
	fmt.Println("Local URL :", cfg.LocalURL())
	if publicURL != "" {
		fmt.Println("Public URL:", publicURL)
	} else if manager.Enabled() {
		fmt.Println("Public URL: tunnel is starting; watch cloudflared logs")
	} else {
		fmt.Println("Public URL: disabled (set REMOTE_CONNECT_MCP_TUNNEL_TOKEN or REMOTE_CONNECT_MCP_QUICK_TUNNEL=1)")
	}
	copied := copyTokenToClipboard(cfg.Token)
	if copied {
		fmt.Println("Bearer    :", cfg.Token, "(copied to clipboard)")
	} else {
		fmt.Println("Bearer    :", cfg.Token)
	}
	fmt.Println("Tools     : server_info list_files search_text read_file apply_patch exec_command process view_image")
	fmt.Println("Stop      : Ctrl+C or close this window")
	fmt.Println()
}

func endpointURL(base string) string {
	base = strings.TrimRight(base, "/")
	if strings.HasSuffix(base, "/mcp") {
		return base
	}
	return base + "/mcp"
}

type auditWriter struct {
	http.ResponseWriter
	status int
}

func (w *auditWriter) WriteHeader(status int) {
	w.status = status
	w.ResponseWriter.WriteHeader(status)
}

func (w *auditWriter) Write(data []byte) (int, error) {
	if w.status == 0 {
		w.status = http.StatusOK
	}
	return w.ResponseWriter.Write(data)
}

func (w *auditWriter) Flush() {
	if flusher, ok := w.ResponseWriter.(http.Flusher); ok {
		flusher.Flush()
	}
}

func requestAudit(logger *slog.Logger, maxBody int64, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		methodName := ""
		toolName := ""
		if r.Body != nil && r.Method == http.MethodPost {
			r.Body = http.MaxBytesReader(w, r.Body, maxBody)
			data, err := io.ReadAll(r.Body)
			if err != nil {
				http.Error(w, "request body too large", http.StatusRequestEntityTooLarge)
				logger.Info("mcp request", "http_method", r.Method, "path", r.URL.Path, "status", http.StatusRequestEntityTooLarge)
				return
			}
			r.Body = io.NopCloser(bytes.NewReader(data))
			var envelope struct {
				Method string `json:"method"`
				Params struct {
					Name string `json:"name"`
				} `json:"params"`
			}
			if json.Unmarshal(data, &envelope) == nil {
				methodName, toolName = envelope.Method, envelope.Params.Name
			}
		}
		recorder := &auditWriter{ResponseWriter: w}
		next.ServeHTTP(recorder, r)
		status := recorder.status
		if status == 0 {
			status = http.StatusOK
		}
		attributes := []any{"http_method", r.Method, "path", r.URL.Path, "status", status, "duration_ms", time.Since(start).Milliseconds()}
		if methodName != "" {
			attributes = append(attributes, "mcp_method", methodName)
		}
		if toolName != "" {
			attributes = append(attributes, "tool", toolName)
		}
		logger.Info("mcp request", attributes...)
	})
}
