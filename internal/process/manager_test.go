package process

import (
	"context"
	"fmt"
	"io"
	"os"
	"os/exec"
	"runtime"
	"strings"
	"testing"
	"time"
)

func TestHelperProcess(t *testing.T) {
	if os.Getenv("REMOTE_MCP_PROCESS_HELPER") != "1" {
		return
	}
	fmt.Print("helper-output")
	if os.Getenv("REMOTE_MCP_PROCESS_STDIN") == "1" {
		data, _ := io.ReadAll(os.Stdin)
		fmt.Print("stdin:" + string(data))
		os.Exit(0)
	}
	if os.Getenv("REMOTE_MCP_PROCESS_SLEEP") == "1" {
		time.Sleep(30 * time.Second)
	}
	os.Exit(0)
}

func TestManagerOutputAndKill(t *testing.T) {
	manager := NewManager(t.TempDir())
	t.Cleanup(manager.Close)
	command := fmt.Sprintf("\"%s\" -test.run=TestHelperProcess --", os.Args[0])
	if runtime.GOOS == "windows" {
		if _, err := exec.LookPath("pwsh.exe"); err == nil {
			command = fmt.Sprintf("& '%s' '-test.run=TestHelperProcess' '--'", strings.ReplaceAll(os.Args[0], "'", "''"))
		}
	}
	entry, err := manager.Start(context.Background(), command, t.TempDir(), map[string]string{
		"REMOTE_MCP_PROCESS_HELPER": "1",
	})
	if err != nil {
		t.Fatal(err)
	}
	entry.Wait()
	info := entry.Info()
	data, _, _, err := entry.Output(0, 1024)
	if err != nil {
		t.Fatal(err)
	}
	if info.Running || info.ExitCode == nil || *info.ExitCode != 0 {
		t.Fatalf("unexpected process info: %+v, output: %s", info, data)
	}
	if !strings.Contains(string(data), "helper-output") {
		t.Fatalf("output = %q", data)
	}

	interactive, err := manager.Start(context.Background(), command, t.TempDir(), map[string]string{
		"REMOTE_MCP_PROCESS_HELPER": "1",
		"REMOTE_MCP_PROCESS_STDIN":  "1",
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := interactive.Write("hello\n"); err != nil {
		t.Fatal(err)
	}
	if err := interactive.CloseInput(); err != nil {
		t.Fatal(err)
	}
	interactive.Wait()
	inputOutput, _, _, err := interactive.Output(0, 1024)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(inputOutput), "stdin:hello") {
		t.Fatalf("interactive output = %q", inputOutput)
	}

	sleeping, err := manager.Start(context.Background(), command, t.TempDir(), map[string]string{
		"REMOTE_MCP_PROCESS_HELPER": "1",
		"REMOTE_MCP_PROCESS_SLEEP":  "1",
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := sleeping.Kill(); err != nil {
		t.Fatal(err)
	}
	done := make(chan struct{})
	go func() {
		sleeping.Wait()
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("killed process did not exit")
	}
	outputPath := entry.output.path
	manager.Close()
	if _, err := os.Stat(outputPath); !os.IsNotExist(err) {
		t.Fatalf("process output was not removed: %v", err)
	}
}
