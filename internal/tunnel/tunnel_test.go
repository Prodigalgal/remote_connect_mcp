package tunnel

import (
	"strings"
	"testing"
)

func TestQuickURL(t *testing.T) {
	line := "INF Requesting new quick Tunnel on https://plain-bird.trycloudflare.com | ready"
	if got := quickURL(line); got != "https://plain-bird.trycloudflare.com" {
		t.Fatalf("quickURL = %q", got)
	}
	if got := endpointURL("https://example.com/mcp/"); got != "https://example.com/mcp" {
		t.Fatalf("endpointURL = %q", got)
	}
}

func TestEmptyTokenDoesNotRewriteLogs(t *testing.T) {
	if line := sanitizeLine("normal cloudflared log", ""); line != "normal cloudflared log" {
		t.Fatalf("line = %q", line)
	}
	if line := sanitizeLine("secret and api appear", "secret", "api"); line != "[redacted] and [redacted] appear" {
		t.Fatalf("redacted line = %q", line)
	}
}

func TestCloudflaredEnvironmentRemovesSecrets(t *testing.T) {
	t.Setenv("REMOTE_CONNECT_MCP_TOKEN", "mcp-secret")
	t.Setenv("CLOUDFLARE_API_TOKEN", "cf-secret")
	t.Setenv("REMOTE_CONNECT_MCP_SAFE_VALUE", "visible")
	joined := strings.Join(cloudflaredEnvironment(), "\n")
	if strings.Contains(joined, "mcp-secret") || strings.Contains(joined, "cf-secret") {
		t.Fatalf("secret leaked into cloudflared environment")
	}
	if !strings.Contains(joined, "REMOTE_CONNECT_MCP_SAFE_VALUE=visible") {
		t.Fatalf("non-secret environment variable was removed")
	}
}
