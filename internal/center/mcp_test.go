package center

import (
	"encoding/json"
	"strings"
	"testing"
	"time"

	"github.com/Prodigalgal/remote_connect_mcp/internal/protocol"
)

func TestMCPCompactTaskDoesNotEchoEnvironment(t *testing.T) {
	longCommand := strings.Repeat("x", mcpMaxCommandEcho+32)
	task := Task{
		ID: "task-1", MachineID: "machine-1", Command: longCommand,
		CWD: strings.Repeat("c", mcpMaxCWDEcho+32), Env: map[string]string{"SECRET": "do-not-return"}, Error: strings.Repeat("e", mcpMaxErrorEcho+32),
		Status: "running", Attempt: 2, CreatedAt: time.Now().UTC(), OutputBytes: 17,
	}
	view := compactTask(task)
	if len(view.Command) != mcpMaxCommandEcho || !view.CommandTruncated {
		t.Fatalf("command compacting = len %d truncated %t", len(view.Command), view.CommandTruncated)
	}
	if len(view.Error) != mcpMaxErrorEcho {
		t.Fatalf("error compacting length = %d", len(view.Error))
	}
	if len(view.CWD) != mcpMaxCWDEcho || !view.CWDTruncated {
		t.Fatalf("cwd compacting = len %d truncated %t", len(view.CWD), view.CWDTruncated)
	}
	data, err := json.Marshal(view)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(data), "do-not-return") || strings.Contains(string(data), "SECRET") {
		t.Fatalf("compact task leaked environment: %s", data)
	}
	if !strings.Contains(string(data), `"attempt":2`) {
		t.Fatalf("compact task did not expose dispatch attempt: %s", data)
	}
	if value, truncated := compactText("中文任务输出abc", 6); value != "中文" || !truncated {
		t.Fatalf("unicode compact text = %q truncated=%t", value, truncated)
	}
}

func TestListMachinesPageIsBoundedAndOrdered(t *testing.T) {
	store, err := OpenStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"charlie", "alpha", "bravo"} {
		if _, err := store.Register(protocol.RegisterRequest{Name: name, OS: "linux", Arch: "amd64"}); err != nil {
			t.Fatal(err)
		}
	}
	page, total := store.ListMachinesPage(time.Now().UTC(), 1, 1)
	if total != 3 || len(page) != 1 || page[0].Name != "bravo" {
		t.Fatalf("page=%+v total=%d", page, total)
	}
	if page, total = store.ListMachinesPage(time.Now().UTC(), 99, 1); total != 3 || len(page) != 0 {
		t.Fatalf("out-of-range page=%+v total=%d", page, total)
	}
}
