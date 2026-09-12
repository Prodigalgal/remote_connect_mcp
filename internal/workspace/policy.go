// Package workspace contains the small, shared path policy used by Center
// and Agent.  The policy deliberately governs the task working directory;
// it does not try to parse or rewrite shell commands.
package workspace

import (
	"errors"
	"fmt"
	"os"
	"path"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
)

const (
	// ModeUnrestricted preserves the original RCM behaviour.  The Agent still
	// uses DefaultCWD when cwd is omitted, but no workspace boundary is
	// enforced.
	ModeUnrestricted = "unrestricted"
	// ModeWorkspace makes WorkspaceRoot an enforced working-directory
	// boundary for every task dispatched to the Agent.
	ModeWorkspace = "workspace"
)

// NormalizeMode validates the persisted/configured scope mode.  Empty is
// treated as unrestricted for backwards compatibility with old state files
// and old Agent configurations.
func NormalizeMode(value string) (string, error) {
	value = strings.ToLower(strings.TrimSpace(value))
	switch value {
	case "", ModeUnrestricted:
		return ModeUnrestricted, nil
	case ModeWorkspace:
		return ModeWorkspace, nil
	default:
		return "", fmt.Errorf("scope mode must be %q or %q", ModeUnrestricted, ModeWorkspace)
	}
}

// PrepareLocal normalizes an Agent's local policy and verifies that the
// configured directories are usable on the target machine.  In workspace
// mode an omitted root intentionally falls back to DefaultCWD so a single
// directory can be enabled with only SCOPE_MODE=workspace.
func PrepareLocal(mode, root, defaultCWD string) (string, string, string, error) {
	normalizedMode, err := NormalizeMode(mode)
	if err != nil {
		return "", "", "", err
	}
	defaultCWD = strings.TrimSpace(defaultCWD)
	if defaultCWD == "" {
		defaultCWD, err = os.Getwd()
		if err != nil {
			return "", "", "", fmt.Errorf("resolve default cwd: %w", err)
		}
	}
	defaultCWD, err = usableDirectory(defaultCWD)
	if err != nil {
		return "", "", "", fmt.Errorf("default cwd: %w", err)
	}
	if normalizedMode == ModeUnrestricted {
		return normalizedMode, "", defaultCWD, nil
	}

	root = strings.TrimSpace(root)
	if root == "" {
		root = defaultCWD
	}
	root, err = existingDirectory(root)
	if err != nil {
		return "", "", "", fmt.Errorf("workspace root: %w", err)
	}
	defaultCWD, err = existingDirectory(defaultCWD)
	if err != nil {
		return "", "", "", fmt.Errorf("default cwd: %w", err)
	}
	if !Within(root, defaultCWD) {
		return "", "", "", fmt.Errorf("default cwd %q is outside workspace root %q", defaultCWD, root)
	}
	return normalizedMode, root, defaultCWD, nil
}

// ResolveLocal resolves and verifies a task cwd on the Agent host.  Existing
// symlinks/junctions are evaluated before the boundary comparison.  A cwd
// must be an existing directory because exec.Cmd cannot start in a missing
// directory.
func ResolveLocal(mode, root, defaultCWD, requested string) (string, error) {
	normalizedMode, err := NormalizeMode(mode)
	if err != nil {
		return "", err
	}
	requested = strings.TrimSpace(requested)
	// Treat both slash styles as separators before touching the filesystem.
	// Tasks can be created by a Center running on another OS, and accepting a
	// foreign-style `..\\child` as a literal Unix filename would return a
	// misleading "path does not exist" error before the workspace boundary is
	// checked. Normalizing here keeps traversal/symlink policy consistent across
	// Agent platforms without changing the configured host-native root.
	requested = strings.ReplaceAll(requested, `\`, "/")
	candidate := requested
	if candidate == "" {
		candidate = defaultCWD
	} else if !filepath.IsAbs(candidate) {
		if filepath.VolumeName(candidate) != "" {
			return "", fmt.Errorf("working directory %q uses an unsupported drive-relative path", requested)
		}
		candidate = filepath.Join(defaultCWD, candidate)
	}
	if normalizedMode == ModeWorkspace {
		candidate, err = existingDirectory(candidate)
	} else {
		candidate, err = usableDirectory(candidate)
	}
	if err != nil {
		return "", fmt.Errorf("working directory %q: %w", requested, err)
	}
	if normalizedMode != ModeWorkspace {
		return candidate, nil
	}
	root, err = existingDirectory(root)
	if err != nil {
		return "", fmt.Errorf("workspace root: %w", err)
	}
	if !Within(root, candidate) {
		return "", fmt.Errorf("working directory %q is outside workspace root %q", candidate, root)
	}
	return candidate, nil
}

func existingDirectory(value string) (string, error) {
	absolute, err := usableDirectory(value)
	if err != nil {
		return "", err
	}
	resolved, err := filepath.EvalSymlinks(absolute)
	if err != nil {
		return "", err
	}
	return filepath.Clean(resolved), nil
}

func usableDirectory(value string) (string, error) {
	absolute, err := filepath.Abs(strings.TrimSpace(value))
	if err != nil {
		return "", err
	}
	info, err := os.Stat(absolute)
	if err != nil {
		return "", err
	}
	if !info.IsDir() {
		return "", errors.New("path is not a directory")
	}
	return filepath.Clean(absolute), nil
}

// Within returns true if candidate is root itself or a descendant.  Both
// values are expected to be local, existing, cleaned paths.
func Within(root, candidate string) bool {
	root = filepath.Clean(root)
	candidate = filepath.Clean(candidate)
	relative, err := filepath.Rel(root, candidate)
	if err != nil || filepath.IsAbs(relative) {
		return false
	}
	return relative == "." || (relative != ".." && !strings.HasPrefix(relative, ".."+string(filepath.Separator)))
}

// ValidateRemote checks a path using the target Agent's OS semantics.  Center
// may run on Linux while validating a Windows Agent, so this function is
// intentionally lexical and does not use the Center filesystem.
func ValidateRemote(mode, osName, root, defaultCWD, requested string) error {
	normalizedMode, err := NormalizeMode(mode)
	if err != nil {
		return err
	}
	if normalizedMode != ModeWorkspace {
		return nil
	}
	root = normalizeRemote(root, osName)
	base := normalizeRemote(defaultCWD, osName)
	if root == "" || !remoteAbsolute(root, osName) {
		return errors.New("workspace root must be an absolute path")
	}
	if base == "" || !remoteAbsolute(base, osName) || !remoteWithin(root, base) {
		return errors.New("default cwd must be an absolute path inside workspace root")
	}
	candidate := normalizeRemote(requested, osName)
	if candidate == "" {
		candidate = base
	} else if !remoteAbsolute(candidate, osName) {
		if strings.EqualFold(osName, "windows") && len(candidate) >= 2 && candidate[1] == ':' {
			return errors.New("working directory uses an unsupported drive-relative path")
		}
		candidate = path.Join(base, candidate)
	}
	if !remoteWithin(root, candidate) {
		return fmt.Errorf("working directory %q is outside workspace root %q", requested, root)
	}
	return nil
}

func normalizeRemote(value, osName string) string {
	value = strings.TrimSpace(value)
	if strings.EqualFold(osName, "windows") {
		value = strings.ReplaceAll(value, `\`, "/")
		value = strings.ToLower(value)
	}
	if value == "" {
		return ""
	}
	value = path.Clean(value)
	if strings.EqualFold(osName, "windows") && len(value) == 2 && value[1] == ':' {
		value += "/"
	}
	return value
}

func remoteAbsolute(value, osName string) bool {
	if strings.EqualFold(osName, "windows") {
		if strings.HasPrefix(value, "/") { // UNC or rooted path
			return true
		}
		if len(value) == 2 && value[1] == ':' {
			return true
		}
		return len(value) >= 3 && value[1] == ':' && value[2] == '/'
	}
	return strings.HasPrefix(value, "/")
}

func remoteWithin(root, candidate string) bool {
	root = path.Clean(root)
	candidate = path.Clean(candidate)
	if root == "/" {
		return strings.HasPrefix(candidate, "/")
	}
	return candidate == root || strings.HasPrefix(candidate, root+"/")
}

// LocalGOOS is useful to callers that need to include a platform in
// diagnostics without importing runtime themselves.
func LocalGOOS() string { return runtime.GOOS }

// NormalizeCapabilities keeps the Agent capability handshake compact and
// deterministic. Capabilities describe optional runtime surfaces (for
// example a future user-session desktop helper); they are informational and
// never grant authority by themselves.
func NormalizeCapabilities(values []string) []string {
	seen := make(map[string]struct{}, len(values))
	result := make([]string, 0, len(values))
	for _, value := range values {
		value = strings.ToLower(strings.TrimSpace(value))
		if value == "" || len(value) > 64 {
			continue
		}
		valid := true
		for _, character := range value {
			if character >= 'a' && character <= 'z' || character >= '0' && character <= '9' || strings.ContainsRune("._:-", character) {
				continue
			}
			valid = false
			break
		}
		if valid {
			if _, exists := seen[value]; !exists {
				seen[value] = struct{}{}
				result = append(result, value)
			}
		}
		if len(result) >= 32 {
			break
		}
	}
	sort.Strings(result)
	return result
}
