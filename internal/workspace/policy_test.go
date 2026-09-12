package workspace

import (
	"os"
	"path/filepath"
	"testing"
)

func TestPrepareAndResolveWorkspace(t *testing.T) {
	root := t.TempDir()
	child := filepath.Join(root, "src")
	if err := os.Mkdir(child, 0o755); err != nil {
		t.Fatal(err)
	}
	mode, normalizedRoot, defaultCWD, err := PrepareLocal(ModeWorkspace, root, child)
	if err != nil {
		t.Fatal(err)
	}
	if mode != ModeWorkspace || normalizedRoot == "" || defaultCWD == "" {
		t.Fatalf("normalized policy = %q, %q, %q", mode, normalizedRoot, defaultCWD)
	}
	resolved, err := ResolveLocal(mode, normalizedRoot, defaultCWD, "..")
	if err != nil {
		t.Fatal(err)
	}
	if !Within(normalizedRoot, resolved) {
		t.Fatalf("resolved path escaped root: %q", resolved)
	}
	if _, err := ResolveLocal(mode, normalizedRoot, defaultCWD, filepath.Dir(root)); err == nil {
		t.Fatal("absolute path outside workspace was accepted")
	}
}

func TestPrepareWorkspaceRejectsOutsideDefault(t *testing.T) {
	root := t.TempDir()
	outside := t.TempDir()
	if _, _, _, err := PrepareLocal(ModeWorkspace, root, outside); err == nil {
		t.Fatal("default cwd outside root was accepted")
	}
}

func TestValidateRemoteWorkspacePaths(t *testing.T) {
	tests := []struct {
		name, osName, root, base, requested string
		wantErr                             bool
	}{
		{name: "linux relative child", osName: "linux", root: "/srv/project", base: "/srv/project", requested: "src"},
		{name: "linux traversal", osName: "linux", root: "/srv/project", base: "/srv/project/src", requested: "../../etc", wantErr: true},
		{name: "windows child", osName: "windows", root: `C:\Work\Project`, base: `C:\Work\Project`, requested: `src`},
		{name: "windows drive root", osName: "windows", root: `C:\`, base: `C:\`, requested: `Windows`, wantErr: false},
		{name: "windows traversal", osName: "windows", root: `C:\Work\Project`, base: `C:\Work\Project\src`, requested: `..\..\Windows`, wantErr: true},
		{name: "windows other drive", osName: "windows", root: `C:\Work\Project`, base: `C:\Work\Project`, requested: `D:\tmp`, wantErr: true},
		{name: "windows drive relative", osName: "windows", root: `C:\Work\Project`, base: `C:\Work\Project`, requested: `C:tmp`, wantErr: true},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			err := ValidateRemote(ModeWorkspace, test.osName, test.root, test.base, test.requested)
			if (err != nil) != test.wantErr {
				t.Fatalf("ValidateRemote error=%v, wantErr=%t", err, test.wantErr)
			}
		})
	}
}

func TestUnrestrictedPreservesAbsolutePath(t *testing.T) {
	outside := t.TempDir()
	resolved, err := ResolveLocal(ModeUnrestricted, "", t.TempDir(), outside)
	if err != nil {
		t.Fatal(err)
	}
	if !Within(outside, resolved) {
		t.Fatalf("unrestricted path was unexpectedly changed: %q", resolved)
	}
}

func TestNormalizeCapabilitiesIsDeterministicAndBounded(t *testing.T) {
	values := NormalizeCapabilities([]string{"Desktop", "command", "desktop", "bad value", "", "workspace-policy"})
	want := []string{"command", "desktop", "workspace-policy"}
	if len(values) != len(want) {
		t.Fatalf("capabilities = %#v", values)
	}
	for index := range want {
		if values[index] != want[index] {
			t.Fatalf("capabilities = %#v, want %#v", values, want)
		}
	}
}
