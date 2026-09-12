package center

import (
	"testing"
	"time"

	"github.com/Prodigalgal/remote_connect_mcp/internal/protocol"
)

func TestDesktopTaskRoutesOnlyToDesktopCapability(t *testing.T) {
	store, err := OpenStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	plain, err := store.Register(protocol.RegisterRequest{Name: "plain", OS: "windows", Arch: "amd64", Capabilities: []string{"command"}})
	if err != nil {
		t.Fatal(err)
	}
	desktopAgent, err := store.Register(protocol.RegisterRequest{Name: "desktop", OS: "windows", Arch: "amd64", Capabilities: []string{"command", "desktop"}})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.CreateTask(protocol.CreateTaskRequest{MachineID: plain.MachineID, Kind: protocol.TaskKindDesktop, Desktop: &protocol.DesktopAction{Operation: "screenshot"}}); err == nil {
		t.Fatal("desktop task was accepted for a machine without desktop capability")
	}
	task, err := store.CreateTask(protocol.CreateTaskRequest{MachineID: desktopAgent.MachineID, Kind: protocol.TaskKindDesktop, RequiredCapability: protocol.CapabilityDesktop, Desktop: &protocol.DesktopAction{Operation: "screenshot"}, TimeoutSeconds: 30})
	if err != nil {
		t.Fatal(err)
	}
	response, err := store.Poll(desktopAgent.MachineID, protocol.PollRequest{AvailableSlots: 1})
	if err != nil {
		t.Fatal(err)
	}
	if response.Task != nil {
		t.Fatal("desktop task was dispatched without an advertised poll capability")
	}
	response, err = store.Poll(desktopAgent.MachineID, protocol.PollRequest{AvailableSlots: 1, AvailableCapabilities: []string{protocol.CapabilityCommand, protocol.CapabilityDesktop}})
	if err != nil {
		t.Fatal(err)
	}
	if response.Task == nil || response.Task.ID != task.ID || response.Task.Kind != protocol.TaskKindDesktop || response.Task.Desktop == nil {
		t.Fatalf("desktop task response = %+v", response.Task)
	}
}

func TestTaskArtifactRoundTripIsBoundedAndChecksummed(t *testing.T) {
	store, err := OpenStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	registered, err := store.Register(protocol.RegisterRequest{Name: "artifact-agent", OS: "windows", Arch: "amd64", Capabilities: []string{"desktop"}})
	if err != nil {
		t.Fatal(err)
	}
	task, err := store.CreateTask(protocol.CreateTaskRequest{MachineID: registered.MachineID, Kind: protocol.TaskKindDesktop, Desktop: &protocol.DesktopAction{Operation: "screenshot"}, TimeoutSeconds: 30})
	if err != nil {
		t.Fatal(err)
	}
	png := []byte("\x89PNG\r\n\x1a\nsmall-test")
	updated, err := store.SaveArtifact(registered.MachineID, task.ID, "image/png", png)
	if err != nil {
		t.Fatal(err)
	}
	if updated.ArtifactBytes != int64(len(png)) || updated.ArtifactSHA256 == "" {
		t.Fatalf("artifact metadata = %+v", updated)
	}
	data, mimeType, hash, err := store.ReadArtifact(task.ID)
	if err != nil || string(data) != string(png) || mimeType != "image/png" || hash != updated.ArtifactSHA256 {
		t.Fatalf("artifact read data=%q mime=%q hash=%q err=%v", data, mimeType, hash, err)
	}
}

func TestMultipleAgentsCanShareHostIDWithoutSharingIdentity(t *testing.T) {
	store, err := OpenStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	first, err := store.Register(protocol.RegisterRequest{Name: "host-command", HostID: "host-01", Hostname: "same-host", OS: "linux", Arch: "amd64", Capabilities: []string{"command"}})
	if err != nil {
		t.Fatal(err)
	}
	second, err := store.Register(protocol.RegisterRequest{Name: "host-desktop", HostID: "host-01", Hostname: "same-host", OS: "windows", Arch: "amd64", Capabilities: []string{"desktop"}})
	if err != nil {
		t.Fatal(err)
	}
	if first.MachineID == second.MachineID || first.Token == second.Token {
		t.Fatal("agents sharing a host were given the same identity")
	}
	machines := store.ListMachines(time.Now().UTC())
	if len(machines) != 2 || machines[0].HostID != "host-01" || machines[1].HostID != "host-01" {
		t.Fatalf("host grouping = %+v", machines)
	}
}
