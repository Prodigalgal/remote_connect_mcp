package tools

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/modelcontextprotocol/go-sdk/mcp"
)

type applyPatchArgs struct {
	Patch string `json:"patch" jsonschema:"Patch using *** Begin Patch, Add/Update/Delete File, @@, and *** End Patch markers"`
}

type patchOperation struct {
	kind   string
	path   string
	moveTo string
	lines  []string
}

type fileState struct {
	data    []byte
	mode    os.FileMode
	exists  bool
	deleted bool
}

type patchChange struct {
	Action string `json:"action"`
	Path   string `json:"path"`
	MoveTo string `json:"move_to,omitempty"`
}

func (s *Service) applyPatch(ctx context.Context, _ *mcp.CallToolRequest, args applyPatchArgs) (*mcp.CallToolResult, any, error) {
	start := time.Now()
	operations, err := parsePatch(args.Patch)
	if err != nil {
		return fail("apply_patch", s, start, err)
	}
	states := make(map[string]*fileState)
	originals := make(map[string]fileState)
	var changes []patchChange

	load := func(path string) (*fileState, error) {
		if state := states[path]; state != nil {
			return state, nil
		}
		data, readErr := os.ReadFile(path)
		if readErr != nil {
			if os.IsNotExist(readErr) {
				state := &fileState{mode: 0o644}
				states[path] = state
				originals[path] = *state
				return state, nil
			}
			return nil, readErr
		}
		info, statErr := os.Stat(path)
		if statErr != nil {
			return nil, statErr
		}
		state := &fileState{data: data, mode: info.Mode().Perm(), exists: true}
		states[path] = state
		originals[path] = fileState{data: append([]byte(nil), data...), mode: state.mode, exists: true}
		return state, nil
	}

	for _, operation := range operations {
		if err := ctx.Err(); err != nil {
			return fail("apply_patch", s, start, err)
		}
		path, err := s.resolvePath(operation.path)
		if err != nil {
			return fail("apply_patch", s, start, err)
		}
		state, err := load(path)
		if err != nil {
			return fail("apply_patch", s, start, fmt.Errorf("%s: %w", operation.path, err))
		}
		switch operation.kind {
		case "add":
			if state.exists && !state.deleted {
				return fail("apply_patch", s, start, fmt.Errorf("add file already exists: %s", path))
			}
			content, err := addedContent(operation.lines)
			if err != nil {
				return fail("apply_patch", s, start, fmt.Errorf("%s: %w", operation.path, err))
			}
			state.data, state.exists, state.deleted = content, true, false
			changes = append(changes, patchChange{Action: "add", Path: path})
		case "delete":
			if !state.exists || state.deleted {
				return fail("apply_patch", s, start, fmt.Errorf("delete file not found: %s", path))
			}
			state.deleted = true
			changes = append(changes, patchChange{Action: "delete", Path: path})
		case "update":
			if !state.exists || state.deleted {
				return fail("apply_patch", s, start, fmt.Errorf("update file not found: %s", path))
			}
			updated, err := applyHunks(state.data, operation.lines)
			if err != nil {
				return fail("apply_patch", s, start, fmt.Errorf("%s: %w", operation.path, err))
			}
			state.data = updated
			change := patchChange{Action: "update", Path: path}
			if operation.moveTo != "" {
				destination, err := s.resolvePath(operation.moveTo)
				if err != nil {
					return fail("apply_patch", s, start, err)
				}
				if destination == path {
					return fail("apply_patch", s, start, fmt.Errorf("move destination equals source: %s", path))
				}
				target, err := load(destination)
				if err != nil {
					return fail("apply_patch", s, start, err)
				}
				if target.exists && !target.deleted {
					return fail("apply_patch", s, start, fmt.Errorf("move destination exists: %s", destination))
				}
				target.data = append([]byte(nil), state.data...)
				target.mode, target.exists, target.deleted = state.mode, true, false
				state.deleted = true
				change.Action = "move"
				change.MoveTo = destination
			}
			changes = append(changes, change)
		}
	}

	if err := commitStates(states, originals); err != nil {
		return fail("apply_patch", s, start, err)
	}
	s.log("apply_patch", start, nil)
	return jsonResult(map[string]any{"changed": changes, "count": len(changes)})
}

func parsePatch(text string) ([]patchOperation, error) {
	text = strings.ReplaceAll(text, "\r\n", "\n")
	lines := strings.Split(text, "\n")
	if len(lines) < 2 || strings.TrimSpace(lines[0]) != "*** Begin Patch" {
		return nil, errors.New("patch must start with *** Begin Patch")
	}
	var operations []patchOperation
	for index := 1; index < len(lines); {
		line := lines[index]
		if line == "*** End Patch" {
			if len(operations) == 0 {
				return nil, errors.New("patch has no operations")
			}
			return operations, nil
		}
		var operation patchOperation
		switch {
		case strings.HasPrefix(line, "*** Add File: "):
			operation.kind = "add"
			operation.path = strings.TrimSpace(strings.TrimPrefix(line, "*** Add File: "))
		case strings.HasPrefix(line, "*** Update File: "):
			operation.kind = "update"
			operation.path = strings.TrimSpace(strings.TrimPrefix(line, "*** Update File: "))
		case strings.HasPrefix(line, "*** Delete File: "):
			operation.kind = "delete"
			operation.path = strings.TrimSpace(strings.TrimPrefix(line, "*** Delete File: "))
		default:
			return nil, fmt.Errorf("unexpected patch line %d: %s", index+1, line)
		}
		if operation.path == "" {
			return nil, fmt.Errorf("empty path at line %d", index+1)
		}
		index++
		if operation.kind == "update" && index < len(lines) && strings.HasPrefix(lines[index], "*** Move to: ") {
			operation.moveTo = strings.TrimSpace(strings.TrimPrefix(lines[index], "*** Move to: "))
			if operation.moveTo == "" {
				return nil, fmt.Errorf("empty move destination at line %d", index+1)
			}
			index++
		}
		for index < len(lines) && !strings.HasPrefix(lines[index], "*** Add File: ") &&
			!strings.HasPrefix(lines[index], "*** Update File: ") &&
			!strings.HasPrefix(lines[index], "*** Delete File: ") &&
			lines[index] != "*** End Patch" {
			operation.lines = append(operation.lines, lines[index])
			index++
		}
		if operation.kind == "delete" && len(operation.lines) > 0 {
			return nil, fmt.Errorf("delete operation for %s has content", operation.path)
		}
		operations = append(operations, operation)
	}
	return nil, errors.New("patch must end with *** End Patch")
}

func addedContent(lines []string) ([]byte, error) {
	if len(lines) == 0 {
		return []byte{}, nil
	}
	result := make([]string, 0, len(lines))
	for index, line := range lines {
		if !strings.HasPrefix(line, "+") {
			return nil, fmt.Errorf("add line %d must start with +", index+1)
		}
		result = append(result, strings.TrimPrefix(line, "+"))
	}
	return []byte(strings.Join(result, "\n") + "\n"), nil
}

type patchHunk struct {
	old []string
	new []string
}

func applyHunks(original []byte, lines []string) ([]byte, error) {
	if len(lines) == 0 {
		return nil, errors.New("update has no hunks")
	}
	newline := "\n"
	if strings.Contains(string(original), "\r\n") {
		newline = "\r\n"
	}
	normalized := strings.ReplaceAll(string(original), "\r\n", "\n")
	current := strings.Split(normalized, "\n")
	var hunks []patchHunk
	var active *patchHunk
	for index, line := range lines {
		if strings.HasPrefix(line, "@@") {
			hunks = append(hunks, patchHunk{})
			active = &hunks[len(hunks)-1]
			continue
		}
		if active == nil {
			return nil, fmt.Errorf("expected @@ before line %d", index+1)
		}
		if line == "\\ No newline at end of file" {
			continue
		}
		if line == "" {
			return nil, fmt.Errorf("hunk line %d has no prefix", index+1)
		}
		switch line[0] {
		case ' ':
			active.old = append(active.old, line[1:])
			active.new = append(active.new, line[1:])
		case '-':
			active.old = append(active.old, line[1:])
		case '+':
			active.new = append(active.new, line[1:])
		default:
			return nil, fmt.Errorf("invalid hunk prefix at line %d", index+1)
		}
	}
	searchFrom := 0
	for index, hunk := range hunks {
		position := findLines(current, hunk.old, searchFrom)
		if position < 0 {
			return nil, fmt.Errorf("hunk %d context not found", index+1)
		}
		replacement := append([]string{}, hunk.new...)
		current = append(current[:position], append(replacement, current[position+len(hunk.old):]...)...)
		searchFrom = position + len(replacement)
	}
	result := strings.Join(current, "\n")
	if newline == "\r\n" {
		result = strings.ReplaceAll(result, "\n", "\r\n")
	}
	return []byte(result), nil
}

func findLines(haystack, needle []string, start int) int {
	if len(needle) == 0 {
		return min(start, len(haystack))
	}
	for index := max(start, 0); index+len(needle) <= len(haystack); index++ {
		match := true
		for offset := range needle {
			if haystack[index+offset] != needle[offset] {
				match = false
				break
			}
		}
		if match {
			return index
		}
	}
	return -1
}

func commitStates(states map[string]*fileState, originals map[string]fileState) error {
	var committed []string
	rollback := func() {
		for index := len(committed) - 1; index >= 0; index-- {
			path := committed[index]
			original := originals[path]
			if !original.exists {
				_ = os.Remove(path)
				continue
			}
			_ = writeAtomic(path, original.data, original.mode)
		}
	}
	for path, state := range states {
		original := originals[path]
		currentExists := state.exists && !state.deleted
		unchanged := currentExists == original.exists &&
			(!currentExists || (bytes.Equal(state.data, original.data) && state.mode.Perm() == original.mode.Perm()))
		if unchanged {
			continue
		}
		committed = append(committed, path)
		if state.deleted || !state.exists {
			if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
				rollback()
				return fmt.Errorf("delete %s: %w", path, err)
			}
		} else {
			if err := writeAtomic(path, state.data, state.mode); err != nil {
				rollback()
				return fmt.Errorf("write %s: %w", path, err)
			}
		}
	}
	return nil
}

func writeAtomic(path string, data []byte, mode os.FileMode) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	file, err := os.CreateTemp(filepath.Dir(path), ".remote-mcp-*")
	if err != nil {
		return err
	}
	temporary := file.Name()
	defer os.Remove(temporary)
	if _, err := file.Write(data); err != nil {
		_ = file.Close()
		return err
	}
	if err := file.Chmod(mode.Perm()); err != nil {
		_ = file.Close()
		return err
	}
	if err := file.Close(); err != nil {
		return err
	}
	if err := os.Rename(temporary, path); err == nil {
		return nil
	}
	if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
		return err
	}
	return os.Rename(temporary, path)
}
