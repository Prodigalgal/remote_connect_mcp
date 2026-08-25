package center

import (
	"testing"
	"time"

	"github.com/Prodigalgal/remote_connect_mcp/internal/protocol"
)

func TestStoreTaskLifecycleAndPersistence(t *testing.T) {
	dir := t.TempDir()
	store, err := OpenStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	registered, err := store.Register(protocol.RegisterRequest{
		Name: "oracle-a", Hostname: "oracle-a", OS: "linux", Arch: "arm64", Version: "test", DefaultCWD: "/root",
	})
	if err != nil {
		t.Fatal(err)
	}
	if !store.AuthenticateAgent(registered.MachineID, registered.Token) {
		t.Fatal("registered agent credentials were rejected")
	}
	if store.AuthenticateAgent(registered.MachineID, "wrong-token") {
		t.Fatal("invalid agent token was accepted")
	}

	task, err := store.CreateTask(protocol.CreateTaskRequest{MachineID: registered.MachineID, Command: "echo hello"})
	if err != nil {
		t.Fatal(err)
	}
	poll, err := store.Poll(registered.MachineID, protocol.PollRequest{AvailableSlots: 1})
	if err != nil {
		t.Fatal(err)
	}
	if poll.Task == nil || poll.Task.ID != task.ID {
		t.Fatalf("polled task = %+v, want %s", poll.Task, task.ID)
	}
	started := time.Now().UTC()
	if _, err := store.UpdateTask(registered.MachineID, task.ID, protocol.TaskUpdateRequest{Status: protocol.TaskRunning, StartedAt: &started}); err != nil {
		t.Fatal(err)
	}
	next, err := store.AppendOutput(registered.MachineID, task.ID, 0, []byte("hello\n"))
	if err != nil || next != 6 {
		t.Fatalf("append output next=%d err=%v", next, err)
	}
	duplicateNext, err := store.AppendOutput(registered.MachineID, task.ID, 0, []byte("hello\n"))
	if err != nil || duplicateNext != 6 {
		t.Fatalf("duplicate append next=%d err=%v", duplicateNext, err)
	}
	exitCode := 0
	finished := time.Now().UTC()
	completed, err := store.UpdateTask(registered.MachineID, task.ID, protocol.TaskUpdateRequest{
		Status: protocol.TaskCompleted, ExitCode: &exitCode, FinishedAt: &finished,
	})
	if err != nil {
		t.Fatal(err)
	}
	if completed.Status != protocol.TaskCompleted || completed.OutputBytes != 6 {
		t.Fatalf("completed task = %+v", completed)
	}
	data, cursor, more, err := store.ReadOutput(task.ID, 0, 32)
	if err != nil || string(data) != "hello\n" || cursor != 6 || more {
		t.Fatalf("output=%q cursor=%d more=%v err=%v", data, cursor, more, err)
	}

	reopened, err := OpenStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	reloaded, ok := reopened.GetTask(task.ID)
	if !ok || reloaded.Status != protocol.TaskCompleted || reloaded.OutputBytes != 6 {
		t.Fatalf("reloaded task = %+v, ok=%v", reloaded, ok)
	}
	ignored, err := reopened.UpdateTask(registered.MachineID, task.ID, protocol.TaskUpdateRequest{Status: protocol.TaskFailed, Error: "late update"})
	if err != nil || ignored.Status != protocol.TaskCompleted {
		t.Fatalf("terminal state was overwritten: %+v err=%v", ignored, err)
	}
}

func TestCancelQueuedTaskIsTerminal(t *testing.T) {
	store, err := OpenStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	registered, err := store.Register(protocol.RegisterRequest{Name: "machine"})
	if err != nil {
		t.Fatal(err)
	}
	task, err := store.CreateTask(protocol.CreateTaskRequest{MachineID: registered.MachineID, Command: "never-run"})
	if err != nil {
		t.Fatal(err)
	}
	canceled, err := store.CancelTask(task.ID)
	if err != nil {
		t.Fatal(err)
	}
	if canceled.Status != protocol.TaskCanceled || canceled.FinishedAt == nil {
		t.Fatalf("canceled task = %+v", canceled)
	}
	poll, err := store.Poll(registered.MachineID, protocol.PollRequest{AvailableSlots: 1})
	if err != nil {
		t.Fatal(err)
	}
	if poll.Task != nil {
		t.Fatalf("canceled task was dispatched: %+v", poll.Task)
	}
}
