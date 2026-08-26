package updater

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestDownloadOnceVerifiesArtifact(t *testing.T) {
	payload := []byte("remote-connect-mcp-agent test artifact")
	sum := sha256.Sum256(payload)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write(payload)
	}))
	defer server.Close()

	destination := filepath.Join(t.TempDir(), "agent.new")
	if err := downloadOnce(context.Background(), server.URL, hex.EncodeToString(sum[:]), destination); err != nil {
		t.Fatalf("download verified artifact: %v", err)
	}
	got, err := os.ReadFile(destination)
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != string(payload) {
		t.Fatalf("downloaded payload = %q", got)
	}
}

func TestDownloadOnceRejectsChecksumMismatch(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte("unexpected artifact"))
	}))
	defer server.Close()

	destination := filepath.Join(t.TempDir(), "agent.new")
	err := downloadOnce(context.Background(), server.URL, strings.Repeat("0", 64), destination)
	if err == nil || !strings.Contains(err.Error(), "SHA-256 mismatch") {
		t.Fatalf("expected checksum mismatch, got %v", err)
	}
	if _, statErr := os.Stat(destination); !os.IsNotExist(statErr) {
		t.Fatalf("destination should not exist after checksum failure: %v", statErr)
	}
}

func TestUpgradeResultRoundTrip(t *testing.T) {
	stateDir := t.TempDir()
	want := Result{CampaignID: "upgrade-1", Status: "failed", Error: "rollback test", UpdatedAt: time.Now().UTC()}
	if err := writeResult(stateDir, want); err != nil {
		t.Fatal(err)
	}
	got, err := ConsumeResult(stateDir)
	if err != nil {
		t.Fatal(err)
	}
	if got == nil || got.CampaignID != want.CampaignID || got.Status != want.Status || got.Error != want.Error {
		t.Fatalf("result = %#v, want %#v", got, want)
	}
	if err := RemoveResult(stateDir); err != nil {
		t.Fatal(err)
	}
	got, err = ConsumeResult(stateDir)
	if err != nil || got != nil {
		t.Fatalf("result after removal = %#v, %v", got, err)
	}
}
