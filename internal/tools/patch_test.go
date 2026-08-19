package tools

import (
	"context"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"testing"
)

func TestParseAndApplyPatch(t *testing.T) {
	operations, err := parsePatch("*** Begin Patch\n*** Update File: a.txt\n@@\n one\n-two\n+three\n*** End Patch")
	if err != nil {
		t.Fatal(err)
	}
	if len(operations) != 1 || operations[0].kind != "update" {
		t.Fatalf("unexpected operations: %+v", operations)
	}
	result, err := applyHunks([]byte("one\ntwo\n"), operations[0].lines)
	if err != nil {
		t.Fatal(err)
	}
	if string(result) != "one\nthree\n" {
		t.Fatalf("result = %q", result)
	}
}

func TestCommitStates(t *testing.T) {
	root := t.TempDir()
	path := filepath.Join(root, "a.txt")
	if err := os.WriteFile(path, []byte("before\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	states := map[string]*fileState{
		path: {data: []byte("after\n"), mode: 0o644, exists: true},
	}
	originals := map[string]fileState{
		path: {data: []byte("before\n"), mode: 0o644, exists: true},
	}
	if err := commitStates(states, originals); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if string(data) != "after\n" {
		t.Fatalf("data = %q", data)
	}
}

func TestApplyPatchTransaction(t *testing.T) {
	root := t.TempDir()
	source := filepath.Join(root, "a.txt")
	if err := os.WriteFile(source, []byte("one\ntwo\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	service := &Service{
		DefaultCWD: root,
		Logger:    slog.New(slog.NewTextHandler(io.Discard, nil)),
	}
	patch := "*** Begin Patch\n" +
		"*** Update File: a.txt\n" +
		"*** Move to: moved.txt\n" +
		"@@\n one\n-two\n+three\n" +
		"*** Add File: new.txt\n+new\n" +
		"*** End Patch"
	if _, _, err := service.applyPatch(context.Background(), nil, applyPatchArgs{Patch: patch}); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(source); !os.IsNotExist(err) {
		t.Fatalf("source still exists: %v", err)
	}
	moved, err := os.ReadFile(filepath.Join(root, "moved.txt"))
	if err != nil {
		t.Fatal(err)
	}
	if string(moved) != "one\nthree\n" {
		t.Fatalf("moved data = %q", moved)
	}

	failing := "*** Begin Patch\n" +
		"*** Add File: stray.txt\n+stray\n" +
		"*** Update File: moved.txt\n@@\n missing\n+replacement\n" +
		"*** End Patch"
	if _, _, err := service.applyPatch(context.Background(), nil, applyPatchArgs{Patch: failing}); err == nil {
		t.Fatal("failing patch unexpectedly succeeded")
	}
	if _, err := os.Stat(filepath.Join(root, "stray.txt")); !os.IsNotExist(err) {
		t.Fatalf("stray file was committed: %v", err)
	}
	after, err := os.ReadFile(filepath.Join(root, "moved.txt"))
	if err != nil {
		t.Fatal(err)
	}
	if string(after) != string(moved) {
		t.Fatalf("moved file changed after failed patch: %q", after)
	}
}
