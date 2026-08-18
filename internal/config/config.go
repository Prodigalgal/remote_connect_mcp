package config

import (
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
)

const DefaultPort = 8765

type Config struct {
	Workspace      string
	EnvFile        string
	Host           string
	Port           int
	Token          string
	PublicURL      string
	TunnelToken    string
	TunnelConfig   string
	Cloudflared    string
	QuickTunnel    bool
	StateDir       string
	MaxRequestBody int64
	Cloudflare     CloudflareProvision
}

type CloudflareProvision struct {
	AutoProvision bool
	APIToken      string
	AccountID     string
	ZoneID        string
	ZoneName      string
	Hostname      string
	TunnelName    string
}

func Load() (Config, error) {
	cwd, err := os.Getwd()
	if err != nil {
		return Config{}, fmt.Errorf("get current directory: %w", err)
	}
	envFile, err := loadEnvFile(cwd)
	if err != nil {
		return Config{}, err
	}
	workspace := env("REMOTE_MCP_WORKSPACE", cwd)
	workspace, err = filepath.Abs(workspace)
	if err != nil {
		return Config{}, fmt.Errorf("resolve workspace: %w", err)
	}
	info, err := os.Stat(workspace)
	if err != nil {
		return Config{}, fmt.Errorf("workspace: %w", err)
	}
	if !info.IsDir() {
		return Config{}, fmt.Errorf("workspace is not a directory: %s", workspace)
	}

	port, err := envInt("REMOTE_MCP_PORT", DefaultPort)
	if err != nil || port < 1 || port > 65535 {
		return Config{}, fmt.Errorf("invalid REMOTE_MCP_PORT")
	}
	maxBody, err := envInt64("REMOTE_MCP_MAX_REQUEST_BYTES", 16*1024*1024)
	if err != nil || maxBody < 1024 {
		return Config{}, fmt.Errorf("invalid REMOTE_MCP_MAX_REQUEST_BYTES")
	}

	stateDir, err := stateDirectory()
	if err != nil {
		return Config{}, err
	}
	if err := os.MkdirAll(stateDir, 0o700); err != nil {
		return Config{}, fmt.Errorf("create state directory: %w", err)
	}

	token := strings.TrimSpace(os.Getenv("REMOTE_MCP_TOKEN"))
	if token == "" {
		return Config{}, errors.New("REMOTE_MCP_TOKEN is required; set a fixed bearer token before starting")
	}
	if strings.ContainsAny(token, "\r\n") {
		return Config{}, errors.New("REMOTE_MCP_TOKEN must be one line")
	}

	cloudflare := CloudflareProvision{
		AutoProvision: envBool("REMOTE_MCP_CF_AUTOPROVISION"),
		APIToken:      strings.TrimSpace(os.Getenv("CLOUDFLARE_API_TOKEN")),
		AccountID:     strings.TrimSpace(os.Getenv("CLOUDFLARE_ACCOUNT_ID")),
		ZoneID:        strings.TrimSpace(os.Getenv("CLOUDFLARE_ZONE_ID")),
		ZoneName:      strings.TrimSpace(os.Getenv("CLOUDFLARE_ZONE_NAME")),
		Hostname:      strings.TrimSpace(os.Getenv("REMOTE_MCP_PUBLIC_HOSTNAME")),
		TunnelName:    strings.TrimSpace(os.Getenv("REMOTE_MCP_TUNNEL_NAME")),
	}
	if cloudflare.AutoProvision {
		if cloudflare.APIToken == "" {
			return Config{}, errors.New("CLOUDFLARE_API_TOKEN is required when REMOTE_MCP_CF_AUTOPROVISION=1")
		}
		if cloudflare.Hostname == "" {
			return Config{}, errors.New("REMOTE_MCP_PUBLIC_HOSTNAME is required when REMOTE_MCP_CF_AUTOPROVISION=1")
		}
		if strings.TrimSpace(os.Getenv("REMOTE_MCP_TUNNEL_CONFIG")) != "" {
			return Config{}, errors.New("REMOTE_MCP_TUNNEL_CONFIG cannot be combined with Cloudflare auto-provisioning")
		}
	}

	publicURL := strings.TrimRight(strings.TrimSpace(os.Getenv("REMOTE_MCP_PUBLIC_URL")), "/")
	quickTunnel := envBool("REMOTE_MCP_QUICK_TUNNEL")
	if cloudflare.AutoProvision {
		publicURL = "https://" + strings.TrimRight(cloudflare.Hostname, ".")
		quickTunnel = false
	}

	return Config{
		Workspace:      workspace,
		EnvFile:        envFile,
		Host:           env("REMOTE_MCP_HOST", "127.0.0.1"),
		Port:           port,
		Token:          token,
		PublicURL:      publicURL,
		TunnelToken:    strings.TrimSpace(os.Getenv("REMOTE_MCP_TUNNEL_TOKEN")),
		TunnelConfig:   strings.TrimSpace(os.Getenv("REMOTE_MCP_TUNNEL_CONFIG")),
		Cloudflared:    strings.TrimSpace(os.Getenv("REMOTE_MCP_CLOUDFLARED")),
		QuickTunnel:    quickTunnel,
		StateDir:       stateDir,
		MaxRequestBody: maxBody,
		Cloudflare:     cloudflare,
	}, nil
}

func (c Config) Address() string  { return net.JoinHostPort(c.Host, strconv.Itoa(c.Port)) }
func (c Config) LocalURL() string { return "http://" + c.Address() + "/mcp" }
func (c Config) OriginURL() string {
	return "http://" + c.Address()
}

func env(name, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(name)); value != "" {
		return value
	}
	return fallback
}

func envInt(name string, fallback int) (int, error) {
	value := strings.TrimSpace(os.Getenv(name))
	if value == "" {
		return fallback, nil
	}
	return strconv.Atoi(value)
}

func envInt64(name string, fallback int64) (int64, error) {
	value := strings.TrimSpace(os.Getenv(name))
	if value == "" {
		return fallback, nil
	}
	return strconv.ParseInt(value, 10, 64)
}

func envBool(name string) bool {
	switch strings.ToLower(strings.TrimSpace(os.Getenv(name))) {
	case "1", "true", "yes", "on":
		return true
	default:
		return false
	}
}

func stateDirectory() (string, error) {
	if value := strings.TrimSpace(os.Getenv("REMOTE_MCP_STATE_DIR")); value != "" {
		return filepath.Abs(value)
	}
	if runtime.GOOS == "windows" {
		base := os.Getenv("LOCALAPPDATA")
		if base == "" {
			base = os.TempDir()
		}
		return filepath.Join(base, "remote-mcp"), nil
	}
	if base := os.Getenv("XDG_STATE_HOME"); base != "" {
		return filepath.Join(base, "remote-mcp"), nil
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return "", fmt.Errorf("resolve home directory: %w", err)
	}
	return filepath.Join(home, ".local", "state", "remote-mcp"), nil
}
