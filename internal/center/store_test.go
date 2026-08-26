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
	poll, err := store.Poll(registered.MachineID, protocol.PollRequest{AvailableSlots: 1}, protocol.AgentMetadata{
		Name: "oracle-a", Hostname: "oracle-a-new", OS: "linux", Arch: "arm64", Version: "v2", DefaultCWD: "/",
	})
	if err != nil {
		t.Fatal(err)
	}
	if poll.Task == nil || poll.Task.ID != task.ID {
		t.Fatalf("polled task = %+v, want %s", poll.Task, task.ID)
	}
	machine, ok := store.GetMachine(registered.MachineID, time.Now().UTC())
	if !ok || machine.Version != "v2" || machine.Hostname != "oracle-a-new" || machine.DefaultCWD != "/" {
		t.Fatalf("heartbeat metadata was not refreshed: %+v", machine)
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

func TestUpgradeCampaignAdvancesCanaryAndPausesOnFailure(t *testing.T) {
	store, err := OpenStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	first, err := store.Register(protocol.RegisterRequest{Name: "first", OS: "linux", Arch: "amd64", Version: "v1.0.0"})
	if err != nil {
		t.Fatal(err)
	}
	second, err := store.Register(protocol.RegisterRequest{Name: "second", OS: "windows", Arch: "amd64", Version: "v1.0.0"})
	if err != nil {
		t.Fatal(err)
	}
	digest := "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	campaign, err := store.CreateUpgradeCampaign(CreateUpgradeCampaignRequest{
		Version: "v2.0.0", CanaryCount: 1, BatchSize: 1, MachineIDs: []string{first.MachineID, second.MachineID},
		Artifacts: map[string]protocol.UpgradeArtifact{
			"linux/amd64":   {OS: "linux", Arch: "amd64", URL: "https://example.test/linux", SHA256: digest},
			"windows/amd64": {OS: "windows", Arch: "amd64", URL: "https://example.test/windows", SHA256: digest},
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	firstPoll, err := store.Poll(first.MachineID, protocol.PollRequest{AvailableSlots: 1})
	if err != nil || firstPoll.Upgrade == nil || firstPoll.Upgrade.CampaignID != campaign.ID {
		t.Fatalf("first canary plan=%+v err=%v", firstPoll.Upgrade, err)
	}
	secondPoll, err := store.Poll(second.MachineID, protocol.PollRequest{AvailableSlots: 1})
	if err != nil || secondPoll.Upgrade != nil {
		t.Fatalf("second machine was offered before canary completed: %+v err=%v", secondPoll.Upgrade, err)
	}
	_, err = store.Poll(first.MachineID, protocol.PollRequest{}, protocol.AgentMetadata{Version: "v2.0.0"})
	if err != nil {
		t.Fatal(err)
	}
	secondPoll, err = store.Poll(second.MachineID, protocol.PollRequest{AvailableSlots: 1})
	if err != nil || secondPoll.Upgrade == nil {
		t.Fatalf("second wave was not offered: %+v err=%v", secondPoll.Upgrade, err)
	}
	paused, err := store.UpdateUpgradeStatus(second.MachineID, protocol.UpgradeStatusRequest{
		CampaignID: campaign.ID, Status: UpgradeFailed, Error: "download failed",
	})
	if err != nil || paused.Status != UpgradePaused {
		t.Fatalf("campaign was not paused: %+v err=%v", paused, err)
	}
	resumed, err := store.ControlUpgradeCampaign(campaign.ID, "resume")
	if err != nil || resumed.Status != UpgradeRunning || resumed.Targets[1].Status != UpgradePending {
		t.Fatalf("campaign was not resumed: %+v err=%v", resumed, err)
	}
}

func TestUpgradeOfferUsesShortRetryLease(t *testing.T) {
	store, err := OpenStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	machine, err := store.Register(protocol.RegisterRequest{Name: "retry", OS: "linux", Arch: "amd64", Version: "v1.0.0"})
	if err != nil {
		t.Fatal(err)
	}
	digest := "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	campaign, err := store.CreateUpgradeCampaign(CreateUpgradeCampaignRequest{
		Version: "v2.0.0", MachineIDs: []string{machine.MachineID},
		Artifacts: map[string]protocol.UpgradeArtifact{
			"linux/amd64": {OS: "linux", Arch: "amd64", URL: "https://example.test/agent", SHA256: digest},
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.Poll(machine.MachineID, protocol.PollRequest{AvailableSlots: 1}); err != nil {
		t.Fatal(err)
	}
	listed := store.ListUpgradeCampaigns(1)[0]
	target := listed.Targets[0]
	if target.Status != UpgradeOffered || target.LeaseUntil == nil {
		t.Fatalf("target was not offered: %+v", target)
	}
	if lease := target.LeaseUntil.Sub(target.UpdatedAt); lease > 31*time.Second {
		t.Fatalf("offer retry lease = %s, want at most 31s", lease)
	}
	store.mu.Lock()
	storedTarget := &store.state.Upgrades[campaign.ID].Targets[0]
	oldUpdate := time.Now().UTC().Add(-time.Minute)
	legacyLease := time.Now().UTC().Add(4 * time.Minute)
	storedTarget.UpdatedAt = oldUpdate
	storedTarget.LeaseUntil = &legacyLease
	store.mu.Unlock()
	retry, err := store.Poll(machine.MachineID, protocol.PollRequest{AvailableSlots: 1})
	if err != nil || retry.Upgrade == nil {
		t.Fatalf("legacy offer was not retried promptly: plan=%+v err=%v", retry.Upgrade, err)
	}
	listed = store.ListUpgradeCampaigns(1)[0]
	if listed.Targets[0].Attempts != 2 {
		t.Fatalf("offer attempts = %d, want 2", listed.Targets[0].Attempts)
	}
	if listed.ID != campaign.ID {
		t.Fatalf("listed campaign = %s, want %s", listed.ID, campaign.ID)
	}
}
