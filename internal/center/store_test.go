package center

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/Prodigalgal/remote_connect_mcp/internal/protocol"
)

func TestAccessTokenRotationPersistenceGraceAndEnvironmentRecovery(t *testing.T) {
	dir := t.TempDir()
	store, err := OpenStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	initial := map[string]string{
		AccessTokenMCP: strings.Repeat("m", 40), AccessTokenAdmin: strings.Repeat("a", 40), AccessTokenEnrollment: strings.Repeat("e", 40),
	}
	now := time.Date(2026, 8, 26, 12, 0, 0, 0, time.UTC)
	if err := store.ConfigureAccessTokens(initial, now); err != nil {
		t.Fatal(err)
	}
	if !store.AuthenticateAccessToken(AccessTokenAdmin, initial[AccessTokenAdmin], now) {
		t.Fatal("environment admin token was rejected")
	}
	rotated := strings.Repeat("n", 40)
	view, err := store.RotateAccessToken(AccessTokenAdmin, rotated, time.Minute, now.Add(time.Second))
	if err != nil || view.Source != "center" || view.PreviousValidUntil == nil {
		t.Fatalf("rotation view=%+v err=%v", view, err)
	}
	if !store.AuthenticateAccessToken(AccessTokenAdmin, initial[AccessTokenAdmin], now.Add(30*time.Second)) || !store.AuthenticateAccessToken(AccessTokenAdmin, rotated, now.Add(30*time.Second)) {
		t.Fatal("active and grace-period admin tokens should both be accepted")
	}
	if store.AuthenticateAccessToken(AccessTokenAdmin, initial[AccessTokenAdmin], now.Add(2*time.Minute)) {
		t.Fatal("expired previous admin token was accepted")
	}
	if err := store.ConfigureAccessTokens(initial, now.Add(3*time.Minute)); err != nil {
		t.Fatal(err)
	}
	if !store.AuthenticateAccessToken(AccessTokenAdmin, rotated, now.Add(3*time.Minute)) {
		t.Fatal("unchanged environment unexpectedly replaced the Center rotation")
	}

	reopened, err := OpenStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	if err := reopened.ConfigureAccessTokens(initial, now.Add(4*time.Minute)); err != nil {
		t.Fatal(err)
	}
	if !reopened.AuthenticateAccessToken(AccessTokenAdmin, rotated, now.Add(4*time.Minute)) {
		t.Fatal("Center rotation did not survive restart")
	}
	recovery := mapsClone(initial)
	recovery[AccessTokenAdmin] = strings.Repeat("r", 40)
	if err := reopened.ConfigureAccessTokens(recovery, now.Add(5*time.Minute)); err != nil {
		t.Fatal(err)
	}
	if !reopened.AuthenticateAccessToken(AccessTokenAdmin, recovery[AccessTokenAdmin], now.Add(5*time.Minute)) || reopened.AuthenticateAccessToken(AccessTokenAdmin, rotated, now.Add(5*time.Minute)) {
		t.Fatal("changed environment did not take over as the recovery token")
	}
	views := reopened.ListAccessTokens(now.Add(5 * time.Minute))
	if len(views) != 3 || views[1].Kind != AccessTokenAdmin || views[1].Source != "environment" {
		t.Fatalf("token views = %+v", views)
	}
}

func mapsClone(source map[string]string) map[string]string {
	result := make(map[string]string, len(source))
	for key, value := range source {
		result[key] = value
	}
	return result
}

func TestScopedEnrollmentTokenLifecycle(t *testing.T) {
	store, err := OpenStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	now := time.Date(2026, 8, 26, 13, 0, 0, 0, time.UTC)
	view, token, err := store.CreateEnrollmentToken("machine-new", time.Hour, now)
	if err != nil {
		t.Fatal(err)
	}
	if view.Status != "active" || !strings.HasPrefix(token, "rcmcp_enroll_") || strings.Contains(view.ID, token) {
		t.Fatalf("created enrollment view=%+v token prefix=%t", view, strings.HasPrefix(token, "rcmcp_enroll_"))
	}
	if _, matched, err := store.RegisterWithScopedEnrollmentToken(protocol.RegisterRequest{Name: "wrong-machine"}, token, now.Add(time.Minute)); !matched || err == nil {
		t.Fatalf("wrong machine matched=%t err=%v", matched, err)
	}
	registered, matched, err := store.RegisterWithScopedEnrollmentToken(protocol.RegisterRequest{Name: "machine-new", OS: "linux", Arch: "amd64"}, token, now.Add(2*time.Minute))
	if !matched || err != nil || registered.MachineID == "" || registered.Token == "" {
		t.Fatalf("registration=%+v matched=%t err=%v", registered, matched, err)
	}
	if !store.AuthenticateAgent(registered.MachineID, registered.Token) {
		t.Fatal("issued machine identity was rejected")
	}
	if _, matched, err := store.RegisterWithScopedEnrollmentToken(protocol.RegisterRequest{Name: "machine-new"}, token, now.Add(3*time.Minute)); !matched || err == nil {
		t.Fatalf("used token matched=%t err=%v", matched, err)
	}
	listed := store.ListEnrollmentTokens(now.Add(3*time.Minute), 10)
	if len(listed) != 1 || listed[0].Status != "used" || listed[0].Uses != 1 {
		t.Fatalf("listed enrollment tokens = %+v", listed)
	}

	revocable, _, err := store.CreateEnrollmentToken("machine-revoked", time.Hour, now)
	if err != nil {
		t.Fatal(err)
	}
	revoked, err := store.RevokeEnrollmentToken(revocable.ID, now.Add(time.Minute))
	if err != nil || revoked.Status != "revoked" {
		t.Fatalf("revoked=%+v err=%v", revoked, err)
	}
	expired, _, err := store.CreateEnrollmentToken("machine-expired", 5*time.Minute, now)
	if err != nil {
		t.Fatal(err)
	}
	listed = store.ListEnrollmentTokens(now.Add(6*time.Minute), 10)
	var expiredStatus string
	for _, candidate := range listed {
		if candidate.ID == expired.ID {
			expiredStatus = candidate.Status
		}
	}
	if expiredStatus != "expired" {
		t.Fatalf("expired token status = %q", expiredStatus)
	}
}

func TestScopedEnrollmentTokenIsConsumedAtomically(t *testing.T) {
	store, err := OpenStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now().UTC()
	_, token, err := store.CreateEnrollmentToken("machine-race", time.Hour, now)
	if err != nil {
		t.Fatal(err)
	}
	type result struct {
		matched bool
		err     error
	}
	results := make(chan result, 2)
	var wait sync.WaitGroup
	for range 2 {
		wait.Add(1)
		go func() {
			defer wait.Done()
			_, matched, err := store.RegisterWithScopedEnrollmentToken(protocol.RegisterRequest{Name: "machine-race"}, token, now.Add(time.Second))
			results <- result{matched: matched, err: err}
		}()
	}
	wait.Wait()
	close(results)
	succeeded := 0
	matched := 0
	for candidate := range results {
		if candidate.matched {
			matched++
		}
		if candidate.err == nil {
			succeeded++
		}
	}
	if matched != 2 || succeeded != 1 {
		t.Fatalf("matched=%d succeeded=%d, want 2 and 1", matched, succeeded)
	}
}

func TestLegacyPersistentEnrollmentTokensAreRevokedOnOpen(t *testing.T) {
	dir := t.TempDir()
	legacyToken := "legacy-enrollment-token"
	legacy := persistedState{
		Machines: map[string]*Machine{}, Tasks: map[string]*Task{}, Upgrades: map[string]*UpgradeCampaign{},
		AccessTokens: map[string]*AccessTokenState{}, Enrollments: map[string]*EnrollmentToken{
			"legacy": {ID: "legacy", Name: "machine-legacy", TokenHash: hashToken(legacyToken), LegacyPersistent: true, MaxUses: 0},
		},
	}
	data, err := json.Marshal(legacy)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "state.json"), data, 0o600); err != nil {
		t.Fatal(err)
	}
	store, err := OpenStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	if _, matched, err := store.RegisterWithScopedEnrollmentToken(protocol.RegisterRequest{Name: "machine-legacy"}, legacyToken, time.Now().UTC()); !matched || err == nil {
		t.Fatalf("legacy token matched=%t err=%v", matched, err)
	}
	listed := store.ListEnrollmentTokens(time.Now().UTC(), 10)
	if len(listed) != 1 || listed[0].Status != "revoked" || listed[0].MaxUses != 1 {
		t.Fatalf("migrated list = %+v", listed)
	}
	reopened, err := OpenStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	if reopened.state.Enrollments["legacy"].LegacyPersistent {
		t.Fatal("legacy persistent marker was not removed")
	}
}

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

func TestCreateTaskIdempotency(t *testing.T) {
	store, err := OpenStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	registered, err := store.Register(protocol.RegisterRequest{Name: "idempotency-test"})
	if err != nil {
		t.Fatal(err)
	}
	request := protocol.CreateTaskRequest{
		MachineID: registered.MachineID, Command: "echo once", CWD: "/tmp",
		Env: map[string]string{"MODE": "test"}, IdempotencyKey: "chat-turn-1234",
	}
	first, err := store.CreateTask(request)
	if err != nil {
		t.Fatal(err)
	}
	second, err := store.CreateTask(request)
	if err != nil {
		t.Fatal(err)
	}
	if second.ID != first.ID {
		t.Fatalf("idempotent retry created %s, want original %s", second.ID, first.ID)
	}
	if tasks := store.ListTasks(10); len(tasks) != 1 {
		t.Fatalf("idempotent retry left %d tasks, want 1", len(tasks))
	}

	request.Command = "echo different"
	if _, err := store.CreateTask(request); err == nil || !strings.Contains(err.Error(), "different task request") {
		t.Fatalf("reused key with different command error = %v", err)
	}

	request.IdempotencyKey = ""
	firstWithoutKey, err := store.CreateTask(request)
	if err != nil {
		t.Fatal(err)
	}
	secondWithoutKey, err := store.CreateTask(request)
	if err != nil {
		t.Fatal(err)
	}
	if firstWithoutKey.ID == secondWithoutKey.ID {
		t.Fatal("requests without idempotency keys were unexpectedly deduplicated")
	}
}

func TestCreateTaskIdempotencySurvivesStoreRestart(t *testing.T) {
	dir := t.TempDir()
	store, err := OpenStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	registered, err := store.Register(protocol.RegisterRequest{Name: "restart-test"})
	if err != nil {
		t.Fatal(err)
	}
	request := protocol.CreateTaskRequest{
		MachineID: registered.MachineID, Command: "echo durable", IdempotencyKey: "durable-command-1",
	}
	first, err := store.CreateTask(request)
	if err != nil {
		t.Fatal(err)
	}
	reopened, err := OpenStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	second, err := reopened.CreateTask(request)
	if err != nil {
		t.Fatal(err)
	}
	if second.ID != first.ID {
		t.Fatalf("retry after restart created %s, want original %s", second.ID, first.ID)
	}
}

func TestPollCoalescesHeartbeatPersistenceButPersistsTransitions(t *testing.T) {
	dir := t.TempDir()
	store, err := OpenStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	registered, err := store.Register(protocol.RegisterRequest{Name: "heartbeat-test", OS: "linux", Arch: "amd64"})
	if err != nil {
		t.Fatal(err)
	}
	statePath := filepath.Join(dir, "state.json")
	before, err := os.ReadFile(statePath)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.Poll(registered.MachineID, protocol.PollRequest{AvailableSlots: 1}, protocol.AgentMetadata{
		Name: "heartbeat-test", OS: "linux", Arch: "amd64",
	}); err != nil {
		t.Fatal(err)
	}
	after, err := os.ReadFile(statePath)
	if err != nil {
		t.Fatal(err)
	}
	if string(after) != string(before) {
		t.Fatal("heartbeat-only poll unexpectedly rewrote persisted state")
	}

	store.mu.Lock()
	store.lastPersistedAt = time.Now().UTC().Add(-31 * time.Second)
	store.mu.Unlock()
	if _, err := store.Poll(registered.MachineID, protocol.PollRequest{AvailableSlots: 1}); err != nil {
		t.Fatal(err)
	}
	coalesced, err := os.ReadFile(statePath)
	if err != nil {
		t.Fatal(err)
	}
	if string(coalesced) == string(before) {
		t.Fatal("coalesced heartbeat was not persisted after the interval")
	}

	task, err := store.CreateTask(protocol.CreateTaskRequest{MachineID: registered.MachineID, Command: "echo transition"})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.Poll(registered.MachineID, protocol.PollRequest{AvailableSlots: 1}); err != nil {
		t.Fatal(err)
	}
	reopened, err := OpenStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	persisted, ok := reopened.GetTask(task.ID)
	if !ok || persisted.Status != protocol.TaskDispatching {
		t.Fatalf("dispatched task transition was not persisted: %+v, ok=%v", persisted, ok)
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
