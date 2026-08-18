package config

import (
	"os"
	"path/filepath"
	"testing"
)

func TestEnvFileLoadsAndEnvironmentWins(t *testing.T) {
	for _, key := range []string{"REMOTE_MCP_PORT", "REMOTE_MCP_HOST"} {
		key := key
		original, existed := os.LookupEnv(key)
		_ = os.Unsetenv(key)
		t.Cleanup(func() {
			if existed {
				_ = os.Setenv(key, original)
			} else {
				_ = os.Unsetenv(key)
			}
		})
	}
	path := filepath.Join(t.TempDir(), "remote-mcp.env")
	content := "# comment\nREMOTE_MCP_TOKEN=file-token\nREMOTE_MCP_PORT=\"19001\"\nREMOTE_MCP_HOST='127.0.0.2'\n"
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("REMOTE_MCP_ENV_FILE", path)
	t.Setenv("REMOTE_MCP_TOKEN", "environment-token")
	t.Setenv("REMOTE_MCP_WORKSPACE", t.TempDir())
	t.Setenv("REMOTE_MCP_STATE_DIR", t.TempDir())

	config, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if config.Token != "environment-token" || config.Port != 19001 || config.Host != "127.0.0.2" {
		t.Fatalf("unexpected config: %+v", config)
	}
	if config.EnvFile != path {
		t.Fatalf("env file = %q", config.EnvFile)
	}
}

func TestExplicitMissingEnvFileFails(t *testing.T) {
	path := filepath.Join(t.TempDir(), "missing.env")
	t.Setenv("REMOTE_MCP_ENV_FILE", path)
	_, err := Load()
	if err == nil {
		t.Fatal("missing explicit env file unexpectedly succeeded")
	}
}
