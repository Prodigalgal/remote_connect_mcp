package updater

import (
	"archive/zip"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"
)

func writeAgentBundleZip(t *testing.T, files map[string][]byte) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "agent.zip")
	file, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	archive := zip.NewWriter(file)
	for name, data := range files {
		entry, createErr := archive.Create(name)
		if createErr != nil {
			_ = file.Close()
			t.Fatal(createErr)
		}
		if _, writeErr := entry.Write(data); writeErr != nil {
			_ = file.Close()
			t.Fatal(writeErr)
		}
	}
	if err := archive.Close(); err != nil {
		_ = file.Close()
		t.Fatal(err)
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}
	return path
}

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

func TestExtractBundleAcceptsNativeExecutableAndRuntimeLibraries(t *testing.T) {
	executable := "rcm-agent"
	if runtime.GOOS == "windows" {
		executable = "rcm-agent.exe"
	}
	archive := writeAgentBundleZip(t, map[string][]byte{
		executable:    []byte("native agent"),
		"libjvm.so":   []byte("runtime"),
		"libfont.dll": []byte("runtime dll"),
	})
	destination := filepath.Join(t.TempDir(), "bundle")
	if err := extractBundle(archive, destination); err != nil {
		t.Fatalf("extract native bundle: %v", err)
	}
	if got, err := os.ReadFile(filepath.Join(destination, executable)); err != nil || !bytes.Equal(got, []byte("native agent")) {
		t.Fatalf("extracted executable = %q, err=%v", got, err)
	}
	if _, _, err := bundleFiles(destination); err != nil {
		t.Fatalf("validate extracted bundle: %v", err)
	}
}

func TestExtractBundleRejectsTraversalAndUnexpectedFiles(t *testing.T) {
	archive := writeAgentBundleZip(t, map[string][]byte{
		"../escape": []byte("must not escape"),
	})
	destination := filepath.Join(t.TempDir(), "bundle")
	if err := extractBundle(archive, destination); err == nil || !strings.Contains(err.Error(), "unsafe file name") {
		t.Fatalf("expected unsafe archive rejection, got %v", err)
	}
	if _, err := os.Stat(filepath.Join(filepath.Dir(destination), "escape")); !os.IsNotExist(err) {
		t.Fatalf("archive escaped staging directory: %v", err)
	}
}
