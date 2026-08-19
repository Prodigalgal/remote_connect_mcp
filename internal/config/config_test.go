package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestTokenMustBeSet(t *testing.T) {
	t.Setenv("REMOTE_CONNECT_MCP_DEFAULT_CWD", t.TempDir())
	stateDir := t.TempDir()
	t.Setenv("REMOTE_CONNECT_MCP_STATE_DIR", stateDir)
	t.Setenv("REMOTE_CONNECT_MCP_TOKEN", "")
	t.Setenv("REMOTE_CONNECT_MCP_PORT", "18765")
	if err := os.WriteFile(filepath.Join(stateDir, "token"), []byte("stale-generated-token"), 0o600); err != nil {
		t.Fatal(err)
	}
	_, err := Load()
	if err == nil || !strings.Contains(err.Error(), "REMOTE_CONNECT_MCP_TOKEN is required") {
		t.Fatalf("error = %v", err)
	}
}

func TestManualToken(t *testing.T) {
	t.Setenv("REMOTE_CONNECT_MCP_DEFAULT_CWD", t.TempDir())
	t.Setenv("REMOTE_CONNECT_MCP_STATE_DIR", t.TempDir())
	t.Setenv("REMOTE_CONNECT_MCP_TOKEN", "manually-set-token")
	config, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if config.Token != "manually-set-token" {
		t.Fatalf("token = %q", config.Token)
	}
}

func TestCloudflareAutoProvisionConfig(t *testing.T) {
	t.Setenv("REMOTE_CONNECT_MCP_DEFAULT_CWD", t.TempDir())
	t.Setenv("REMOTE_CONNECT_MCP_STATE_DIR", t.TempDir())
	t.Setenv("REMOTE_CONNECT_MCP_TOKEN", "mcp-token")
	t.Setenv("REMOTE_CONNECT_MCP_CF_AUTOPROVISION", "1")
	t.Setenv("CLOUDFLARE_API_TOKEN", "cloudflare-token")
	t.Setenv("CLOUDFLARE_ACCOUNT_ID", "")
	t.Setenv("CLOUDFLARE_ZONE_ID", "")
	t.Setenv("CLOUDFLARE_ZONE_NAME", "")
	t.Setenv("REMOTE_CONNECT_MCP_PUBLIC_HOSTNAME", "mcp.example.com")
	t.Setenv("REMOTE_CONNECT_MCP_TUNNEL_NAME", "")
	t.Setenv("REMOTE_CONNECT_MCP_TUNNEL_CONFIG", "")
	t.Setenv("REMOTE_CONNECT_MCP_QUICK_TUNNEL", "1")

	config, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if !config.Cloudflare.AutoProvision || config.Cloudflare.APIToken != "cloudflare-token" {
		t.Fatalf("auto-provision config not loaded: %+v", config.Cloudflare)
	}
	if config.Cloudflare.AccountID != "" || config.Cloudflare.ZoneID != "" {
		t.Fatalf("IDs should remain optional: %+v", config.Cloudflare)
	}
	if config.PublicURL != "https://mcp.example.com" || config.QuickTunnel {
		t.Fatalf("public URL/quick mode = %q/%v", config.PublicURL, config.QuickTunnel)
	}
}
