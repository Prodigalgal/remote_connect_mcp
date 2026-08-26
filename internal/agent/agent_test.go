package agent

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"runtime"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/Prodigalgal/remote_connect_mcp/internal/center"
	"github.com/Prodigalgal/remote_connect_mcp/internal/protocol"
)

func TestAgentCompletesTaskAcrossCenterInterruption(t *testing.T) {
	store, err := center.OpenStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	centerHandler, err := center.NewHTTPHandler(store, center.HTTPConfig{
		Version: "test", MCPToken: "mcp", AdminToken: "admin", EnrollmentToken: "enroll",
		Logger: slog.New(slog.NewTextHandler(io.Discard, nil)),
	})
	if err != nil {
		t.Fatal(err)
	}
	var available atomic.Bool
	available.Store(true)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !available.Load() {
			http.Error(w, "temporarily unavailable", http.StatusServiceUnavailable)
			return
		}
		centerHandler.ServeHTTP(w, r)
	}))
	defer server.Close()

	client, err := New(Config{
		CenterURL: server.URL, EnrollmentToken: "enroll", Name: "test-agent", DefaultCWD: t.TempDir(),
		StateDir: t.TempDir(), MaxConcurrency: 1, Version: "test", Logger: slog.New(slog.NewTextHandler(io.Discard, nil)),
	})
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- client.Run(ctx) }()

	var machineID string
	waitFor(t, 10*time.Second, func() bool {
		machines := store.ListMachines(time.Now().UTC())
		if len(machines) == 1 && machines[0].Online {
			machineID = machines[0].ID
			return true
		}
		return false
	})
	command := "printf before; sleep 2; printf after"
	if runtime.GOOS == "windows" {
		command = "Write-Output -NoNewline before; Start-Sleep -Seconds 2; Write-Output -NoNewline after"
	}
	task, err := store.CreateTask(protocol.CreateTaskRequest{MachineID: machineID, Command: command})
	if err != nil {
		t.Fatal(err)
	}
	waitFor(t, 10*time.Second, func() bool {
		current, _ := store.GetTask(task.ID)
		return current.Status == protocol.TaskRunning
	})
	available.Store(false)
	time.Sleep(3 * time.Second)
	current, _ := store.GetTask(task.ID)
	if current.Status != protocol.TaskRunning {
		t.Fatalf("task did not remain running while center was unavailable: %+v", current)
	}
	available.Store(true)
	waitFor(t, 20*time.Second, func() bool {
		current, _ := store.GetTask(task.ID)
		return current.Status == protocol.TaskCompleted
	})
	output, _, _, err := store.ReadOutput(task.ID, 0, 1024)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(output), "before") || !strings.Contains(string(output), "after") {
		t.Fatalf("output = %q", output)
	}
	cancel()
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("agent did not stop")
	}
}

func waitFor(t *testing.T, timeout time.Duration, condition func() bool) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if condition() {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatal("condition was not met before timeout")
}
