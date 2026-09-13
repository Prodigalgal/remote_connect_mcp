package updater

import (
	"archive/zip"
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"time"

	"github.com/Prodigalgal/remote_connect_mcp/internal/protocol"
)

const maxArtifactBytes = 100 * 1024 * 1024

type Result struct {
	CampaignID string    `json:"campaign_id"`
	Status     string    `json:"status"`
	Error      string    `json:"error,omitempty"`
	UpdatedAt  time.Time `json:"updated_at"`
}

type helperConfig struct {
	CampaignID string `json:"campaign_id"`
	Version    string `json:"version"`
	Staged     string `json:"staged"`
	Target     string `json:"target"`
	StateDir   string `json:"state_dir"`
	Service    string `json:"service"`
	Archive    bool   `json:"archive,omitempty"`
}

func PrepareAndLaunch(ctx context.Context, plan protocol.UpgradePlan, stateDir, serviceName string, logger *slog.Logger, prepared func() error) error {
	if strings.TrimSpace(plan.CampaignID) == "" || strings.TrimSpace(plan.Version) == "" {
		return errors.New("upgrade plan is incomplete")
	}
	parsed, err := url.Parse(plan.URL)
	if err != nil || parsed.Scheme != "https" || parsed.Host == "" {
		return errors.New("upgrade artifact URL must use HTTPS")
	}
	expectedSHA := strings.ToLower(strings.TrimSpace(plan.SHA256))
	if len(expectedSHA) != 64 {
		return errors.New("upgrade SHA-256 is invalid")
	}
	if _, err := hex.DecodeString(expectedSHA); err != nil {
		return errors.New("upgrade SHA-256 is not hexadecimal")
	}
	updateDir := filepath.Join(stateDir, "upgrades")
	if err := os.MkdirAll(updateDir, 0o700); err != nil {
		return fmt.Errorf("create upgrade directory: %w", err)
	}
	suffix := ""
	if runtime.GOOS == "windows" {
		suffix = ".exe"
	}
	key := safeName(plan.CampaignID)
	staged := filepath.Join(updateDir, "agent-"+key+".new"+suffix)
	isArchive := strings.HasSuffix(strings.ToLower(parsed.Path), ".zip")
	downloadPath := staged
	if isArchive {
		downloadPath += ".zip"
	}
	if err := downloadVerified(ctx, plan.URL, expectedSHA, downloadPath, logger); err != nil {
		return err
	}
	if isArchive {
		staged = filepath.Join(updateDir, "bundle-"+key)
		if err := extractBundle(downloadPath, staged); err != nil {
			_ = os.Remove(downloadPath)
			return err
		}
		_ = os.Remove(downloadPath)
	}
	current, err := os.Executable()
	if err != nil {
		return fmt.Errorf("resolve current agent executable: %w", err)
	}
	current, err = filepath.Abs(current)
	if err != nil {
		return err
	}
	helper := filepath.Join(updateDir, "helper-"+key+suffix)
	if err := copyFile(current, helper, 0o700); err != nil {
		return fmt.Errorf("prepare upgrade helper: %w", err)
	}
	config := helperConfig{
		CampaignID: plan.CampaignID, Version: plan.Version, Staged: staged, Target: current,
		StateDir: stateDir, Service: serviceName, Archive: isArchive,
	}
	data, err := json.Marshal(config)
	if err != nil {
		return err
	}
	encoded := base64.RawURLEncoding.EncodeToString(data)
	if prepared != nil {
		if err := prepared(); err != nil {
			return fmt.Errorf("report prepared upgrade: %w", err)
		}
	}
	if err := launchHelper(helper, encoded, key); err != nil {
		return fmt.Errorf("launch upgrade helper: %w", err)
	}
	return nil
}

func RunHelper(arguments []string) error {
	flags := flag.NewFlagSet("apply-update", flag.ContinueOnError)
	encoded := flags.String("config", "", "base64 encoded update config")
	if err := flags.Parse(arguments); err != nil {
		return err
	}
	data, err := base64.RawURLEncoding.DecodeString(*encoded)
	if err != nil {
		return errors.New("invalid update helper config")
	}
	var config helperConfig
	if err := json.Unmarshal(data, &config); err != nil {
		return errors.New("invalid update helper JSON")
	}
	return applyUpdate(config)
}

func applyUpdate(config helperConfig) error {
	if config.Archive {
		return applyArchiveUpdate(config)
	}
	result := Result{CampaignID: config.CampaignID, Status: "failed", UpdatedAt: time.Now().UTC()}
	writeFailure := func(err error) error {
		result.Error = err.Error()
		_ = writeResult(config.StateDir, result)
		return err
	}
	if err := stopService(config.Service, 45*time.Second); err != nil {
		return writeFailure(fmt.Errorf("stop agent service: %w", err))
	}
	targetDir := filepath.Dir(config.Target)
	next := filepath.Join(targetDir, ".remote-connect-mcp-agent.next")
	backup := config.Target + ".previous"
	_ = os.Remove(next)
	if err := copyFile(config.Staged, next, 0o755); err != nil {
		failure := writeFailure(fmt.Errorf("copy staged agent: %w", err))
		_ = startService(config.Service, 45*time.Second)
		return failure
	}
	_ = os.Remove(backup)
	if err := os.Rename(config.Target, backup); err != nil {
		_ = os.Remove(next)
		failure := writeFailure(fmt.Errorf("backup current agent: %w", err))
		_ = startService(config.Service, 45*time.Second)
		return failure
	}
	if err := os.Rename(next, config.Target); err != nil {
		_ = os.Rename(backup, config.Target)
		failure := writeFailure(fmt.Errorf("activate staged agent: %w", err))
		_ = startService(config.Service, 45*time.Second)
		return failure
	}
	_ = os.Chmod(config.Target, 0o755)
	if err := startService(config.Service, 45*time.Second); err != nil {
		_ = stopService(config.Service, 20*time.Second)
		_ = os.Remove(config.Target)
		_ = os.Rename(backup, config.Target)
		failure := writeFailure(fmt.Errorf("new agent failed health check and was rolled back: %w", err))
		_ = startService(config.Service, 45*time.Second)
		return failure
	}
	result.Status = "completed"
	result.Error = ""
	result.UpdatedAt = time.Now().UTC()
	_ = writeResult(config.StateDir, result)
	_ = os.Remove(config.Staged)
	if runtime.GOOS != "windows" {
		_ = os.Remove(os.Args[0])
	}
	return nil
}

// applyArchiveUpdate replaces a native Java Agent bundle while keeping the
// legacy Go service's executable path.  The Java release uses rcm-agent as its
// canonical filename; mapping that file to the current executable path lets
// an already-installed Go service transition without changing its unit file.
func applyArchiveUpdate(config helperConfig) error {
	result := Result{CampaignID: config.CampaignID, Status: "failed", UpdatedAt: time.Now().UTC()}
	writeFailure := func(err error) error {
		result.Error = err.Error()
		_ = writeResult(config.StateDir, result)
		return err
	}
	if err := stopService(config.Service, 45*time.Second); err != nil {
		return writeFailure(fmt.Errorf("stop agent service: %w", err))
	}
	target, err := filepath.Abs(config.Target)
	if err != nil {
		failure := writeFailure(fmt.Errorf("resolve target path: %w", err))
		_ = startService(config.Service, 45*time.Second)
		return failure
	}
	if !isRegularFile(target) {
		failure := writeFailure(fmt.Errorf("current agent binary is not a regular file: %s", target))
		_ = startService(config.Service, 45*time.Second)
		return failure
	}
	bundle, err := filepath.Abs(config.Staged)
	if err != nil {
		failure := writeFailure(fmt.Errorf("resolve staged bundle: %w", err))
		_ = startService(config.Service, 45*time.Second)
		return failure
	}
	files, executable, err := bundleFiles(bundle)
	if err != nil {
		failure := writeFailure(err)
		_ = startService(config.Service, 45*time.Second)
		return failure
	}
	targetDir := filepath.Dir(target)
	key := safeName(config.CampaignID)
	workRoot := filepath.Dir(bundle)
	nextDir := filepath.Join(workRoot, "next-"+key)
	backupDir := filepath.Join(workRoot, "backup-"+key)
	_ = os.RemoveAll(nextDir)
	_ = os.RemoveAll(backupDir)
	if err := os.MkdirAll(nextDir, 0o700); err != nil {
		failure := writeFailure(fmt.Errorf("create staged replacement: %w", err))
		_ = startService(config.Service, 45*time.Second)
		return failure
	}
	if err := os.MkdirAll(backupDir, 0o700); err != nil {
		_ = os.RemoveAll(nextDir)
		failure := writeFailure(fmt.Errorf("create rollback directory: %w", err))
		_ = startService(config.Service, 45*time.Second)
		return failure
	}
	defer os.RemoveAll(nextDir)
	destinations := make([]string, 0, len(files))
	for _, name := range files {
		destination := name
		if strings.EqualFold(name, executable) {
			destination = filepath.Base(target)
		}
		if filepath.Base(destination) != destination || destination == "." || destination == ".." {
			_ = os.RemoveAll(backupDir)
			failure := writeFailure(fmt.Errorf("Agent archive contains an unsafe destination: %s", destination))
			_ = startService(config.Service, 45*time.Second)
			return failure
		}
		source := filepath.Join(bundle, name)
		staged := filepath.Join(nextDir, destination)
		if err := copyFile(source, staged, 0o700); err != nil {
			_ = os.RemoveAll(backupDir)
			failure := writeFailure(fmt.Errorf("stage Agent bundle file %s: %w", name, err))
			_ = startService(config.Service, 45*time.Second)
			return failure
		}
		destinations = append(destinations, destination)
	}
	versionPath := filepath.Join(config.StateDir, "agent-version")
	previousVersion, hadPreviousVersion := readOptionalFile(versionPath)
	restoreVersion := func() {
		if hadPreviousVersion {
			_ = os.WriteFile(versionPath, previousVersion, 0o600)
		} else {
			_ = os.Remove(versionPath)
		}
	}
	backedUp := make([]string, 0, len(destinations))
	installed := make([]string, 0, len(destinations))
	rollback := func() {
		for _, name := range installed {
			_ = os.Remove(filepath.Join(targetDir, name))
		}
		for _, name := range backedUp {
			backup := filepath.Join(backupDir, name)
			current := filepath.Join(targetDir, name)
			_ = os.Remove(current)
			_ = os.Rename(backup, current)
		}
		restoreVersion()
	}
	for _, name := range destinations {
		current := filepath.Join(targetDir, name)
		info, statErr := os.Lstat(current)
		if os.IsNotExist(statErr) {
			continue
		}
		if statErr != nil || !info.Mode().IsRegular() {
			rollback()
			_ = os.RemoveAll(backupDir)
			failure := writeFailure(fmt.Errorf("existing Agent bundle file is not a regular file: %s", current))
			_ = startService(config.Service, 45*time.Second)
			return failure
		}
		if err := os.Rename(current, filepath.Join(backupDir, name)); err != nil {
			rollback()
			_ = os.RemoveAll(backupDir)
			failure := writeFailure(fmt.Errorf("backup Agent bundle file %s: %w", name, err))
			_ = startService(config.Service, 45*time.Second)
			return failure
		}
		backedUp = append(backedUp, name)
	}
	for _, name := range destinations {
		if err := os.Rename(filepath.Join(nextDir, name), filepath.Join(targetDir, name)); err != nil {
			rollback()
			_ = os.RemoveAll(backupDir)
			failure := writeFailure(fmt.Errorf("activate Agent bundle file %s: %w", name, err))
			_ = startService(config.Service, 45*time.Second)
			return failure
		}
		installed = append(installed, name)
	}
	if err := writeAgentVersion(config.StateDir, config.Version); err != nil {
		rollback()
		_ = os.RemoveAll(backupDir)
		failure := writeFailure(fmt.Errorf("write Agent version marker: %w", err))
		_ = startService(config.Service, 45*time.Second)
		return failure
	}
	if err := startService(config.Service, 45*time.Second); err != nil {
		_ = stopService(config.Service, 20*time.Second)
		rollback()
		_ = os.RemoveAll(backupDir)
		failure := writeFailure(fmt.Errorf("new Agent failed health check and was rolled back: %w", err))
		_ = startService(config.Service, 45*time.Second)
		return failure
	}
	// Keep one previous file per replaced path for an operator-led rollback;
	// the active process has already passed its service health check.
	for _, name := range backedUp {
		previous := filepath.Join(targetDir, name+".previous")
		_ = os.Remove(previous)
		_ = os.Rename(filepath.Join(backupDir, name), previous)
	}
	_ = os.RemoveAll(backupDir)
	_ = os.RemoveAll(bundle)
	result.Status = "completed"
	result.Error = ""
	result.UpdatedAt = time.Now().UTC()
	_ = writeResult(config.StateDir, result)
	if runtime.GOOS != "windows" {
		_ = os.Remove(os.Args[0])
	}
	return nil
}

func extractBundle(archivePath, destination string) error {
	reader, err := zip.OpenReader(archivePath)
	if err != nil {
		return fmt.Errorf("open Agent archive: %w", err)
	}
	defer reader.Close()
	_ = os.RemoveAll(destination)
	if err := os.MkdirAll(destination, 0o700); err != nil {
		return fmt.Errorf("create Agent archive staging directory: %w", err)
	}
	seen := map[string]struct{}{}
	var total int64
	for _, entry := range reader.File {
		name := filepath.ToSlash(entry.Name)
		if entry.FileInfo().IsDir() || name == "" {
			continue
		}
		if !safeBundleName(name) || entry.Mode()&os.ModeSymlink != 0 {
			_ = os.RemoveAll(destination)
			return fmt.Errorf("Agent archive contains an unsafe file name: %s", entry.Name)
		}
		lower := strings.ToLower(name)
		if _, ok := seen[lower]; ok {
			_ = os.RemoveAll(destination)
			return fmt.Errorf("Agent archive contains duplicate file: %s", name)
		}
		seen[lower] = struct{}{}
		if !bundleRuntimeFile(name) {
			_ = os.RemoveAll(destination)
			return fmt.Errorf("Agent archive contains an unexpected file: %s", name)
		}
		if entry.UncompressedSize64 > maxBundleExtractedBytes || total+int64(entry.UncompressedSize64) > maxBundleExtractedBytes {
			_ = os.RemoveAll(destination)
			return errors.New("Agent archive exceeds the uncompressed size limit")
		}
		input, openErr := entry.Open()
		if openErr != nil {
			_ = os.RemoveAll(destination)
			return fmt.Errorf("read Agent archive entry %s: %w", name, openErr)
		}
		output, createErr := os.OpenFile(filepath.Join(destination, name), os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o700)
		if createErr != nil {
			_ = input.Close()
			_ = os.RemoveAll(destination)
			return fmt.Errorf("create Agent archive entry %s: %w", name, createErr)
		}
		written, copyErr := io.CopyN(output, input, maxBundleExtractedBytes-total+1)
		closeErr := output.Close()
		_ = input.Close()
		if copyErr != nil && copyErr != io.EOF {
			_ = os.RemoveAll(destination)
			return fmt.Errorf("extract Agent archive entry %s: %w", name, copyErr)
		}
		if written > maxBundleExtractedBytes-total || closeErr != nil {
			_ = os.RemoveAll(destination)
			if closeErr != nil {
				return fmt.Errorf("close Agent archive entry %s: %w", name, closeErr)
			}
			return errors.New("Agent archive exceeds the uncompressed size limit")
		}
		total += written
	}
	if _, _, err := bundleFiles(destination); err != nil {
		_ = os.RemoveAll(destination)
		return err
	}
	return nil
}

const maxBundleExtractedBytes = 256 * 1024 * 1024

func bundleFiles(directory string) ([]string, string, error) {
	entries, err := os.ReadDir(directory)
	if err != nil {
		return nil, "", fmt.Errorf("read Agent bundle: %w", err)
	}
	files := make([]string, 0, len(entries))
	executable := ""
	for _, entry := range entries {
		if entry.IsDir() || !safeBundleName(entry.Name()) {
			return nil, "", fmt.Errorf("Agent bundle contains an unsafe entry: %s", entry.Name())
		}
		info, statErr := entry.Info()
		if statErr != nil || !info.Mode().IsRegular() || !bundleRuntimeFile(entry.Name()) {
			return nil, "", fmt.Errorf("Agent bundle contains an invalid entry: %s", entry.Name())
		}
		files = append(files, entry.Name())
		if isBundleExecutable(entry.Name()) {
			if executable != "" {
				return nil, "", errors.New("Agent bundle contains multiple executables")
			}
			executable = entry.Name()
		}
	}
	if executable == "" {
		return nil, "", errors.New("Agent bundle does not contain a native Agent executable")
	}
	return files, executable, nil
}

func safeBundleName(name string) bool {
	return name != "." && name != ".." && filepath.Base(name) == name && len(name) <= 180 && !strings.ContainsAny(name, `/\\:`) && name[0] != '.'
}

func bundleRuntimeFile(name string) bool {
	lower := strings.ToLower(name)
	return isBundleExecutable(name) || strings.HasSuffix(lower, ".dll") || strings.HasSuffix(lower, ".so") || strings.Contains(lower, ".so.")
}

func isBundleExecutable(name string) bool {
	lower := strings.ToLower(name)
	if runtime.GOOS == "windows" {
		return lower == "rcm-agent.exe" || lower == "remote-connect-mcp-agent.exe"
	}
	return lower == "rcm-agent" || lower == "remote-connect-mcp-agent"
}

func isRegularFile(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.Mode().IsRegular()
}

func readOptionalFile(path string) ([]byte, bool) {
	data, err := os.ReadFile(path)
	return data, err == nil
}

func writeAgentVersion(stateDir, version string) error {
	if strings.TrimSpace(version) == "" {
		return errors.New("Agent upgrade version is missing")
	}
	if err := os.MkdirAll(stateDir, 0o700); err != nil {
		return err
	}
	temp, err := os.CreateTemp(stateDir, ".agent-version-*.tmp")
	if err != nil {
		return err
	}
	tempName := temp.Name()
	defer os.Remove(tempName)
	if err := temp.Chmod(0o600); err != nil {
		_ = temp.Close()
		return err
	}
	if _, err := temp.WriteString(strings.TrimSpace(version) + "\n"); err != nil {
		_ = temp.Close()
		return err
	}
	if err := temp.Sync(); err != nil {
		_ = temp.Close()
		return err
	}
	if err := temp.Close(); err != nil {
		return err
	}
	return os.Rename(tempName, filepath.Join(stateDir, "agent-version"))
}

func ConsumeResult(stateDir string) (*Result, error) {
	path := filepath.Join(stateDir, "upgrade-result.json")
	data, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var result Result
	if err := json.Unmarshal(data, &result); err != nil {
		return nil, err
	}
	return &result, nil
}

func RemoveResult(stateDir string) error {
	err := os.Remove(filepath.Join(stateDir, "upgrade-result.json"))
	if os.IsNotExist(err) {
		return nil
	}
	return err
}

func writeResult(stateDir string, result Result) error {
	data, err := json.Marshal(result)
	if err != nil {
		return err
	}
	temp, err := os.CreateTemp(stateDir, "upgrade-result-*.tmp")
	if err != nil {
		return err
	}
	name := temp.Name()
	defer os.Remove(name)
	if err := temp.Chmod(0o600); err != nil {
		temp.Close()
		return err
	}
	if _, err := temp.Write(data); err != nil {
		temp.Close()
		return err
	}
	if err := temp.Sync(); err != nil {
		temp.Close()
		return err
	}
	if err := temp.Close(); err != nil {
		return err
	}
	return os.Rename(name, filepath.Join(stateDir, "upgrade-result.json"))
}

func downloadVerified(ctx context.Context, artifactURL, expectedSHA, destination string, logger *slog.Logger) error {
	var lastErr error
	for attempt := 1; attempt <= 3; attempt++ {
		if attempt > 1 {
			select {
			case <-ctx.Done():
				return ctx.Err()
			case <-time.After(time.Duration(attempt*2) * time.Second):
			}
		}
		if logger != nil {
			logger.Info("downloading agent upgrade", "url", artifactURL, "attempt", attempt)
		}
		lastErr = downloadOnce(ctx, artifactURL, expectedSHA, destination)
		if lastErr == nil {
			return nil
		}
	}
	return fmt.Errorf("download agent upgrade: %w", lastErr)
}

func downloadOnce(ctx context.Context, artifactURL, expectedSHA, destination string) error {
	requestCtx, cancel := context.WithTimeout(ctx, 15*time.Minute)
	defer cancel()
	request, err := http.NewRequestWithContext(requestCtx, http.MethodGet, artifactURL, nil)
	if err != nil {
		return err
	}
	request.Header.Set("User-Agent", "remote-connect-mcp-agent-updater")
	response, err := (&http.Client{Timeout: 0}).Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return fmt.Errorf("artifact endpoint returned HTTP %d", response.StatusCode)
	}
	temp := destination + ".download"
	file, err := os.OpenFile(temp, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o600)
	if err != nil {
		return err
	}
	hash := sha256.New()
	written, copyErr := io.Copy(io.MultiWriter(file, hash), io.LimitReader(response.Body, maxArtifactBytes+1))
	closeErr := file.Close()
	if copyErr != nil {
		_ = os.Remove(temp)
		return copyErr
	}
	if closeErr != nil {
		_ = os.Remove(temp)
		return closeErr
	}
	if written > maxArtifactBytes {
		_ = os.Remove(temp)
		return errors.New("artifact exceeds 100 MiB")
	}
	actual := hex.EncodeToString(hash.Sum(nil))
	if actual != expectedSHA {
		_ = os.Remove(temp)
		return fmt.Errorf("artifact SHA-256 mismatch: got %s", actual)
	}
	_ = os.Remove(destination)
	if err := os.Rename(temp, destination); err != nil {
		_ = os.Remove(temp)
		return err
	}
	return os.Chmod(destination, 0o700)
}

func copyFile(source, destination string, mode os.FileMode) error {
	input, err := os.Open(source)
	if err != nil {
		return err
	}
	defer input.Close()
	output, err := os.OpenFile(destination, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, mode)
	if err != nil {
		return err
	}
	_, copyErr := io.Copy(output, input)
	closeErr := output.Close()
	if copyErr != nil {
		return copyErr
	}
	return closeErr
}

func safeName(value string) string {
	var result strings.Builder
	for _, character := range value {
		if character >= 'a' && character <= 'z' || character >= 'A' && character <= 'Z' || character >= '0' && character <= '9' || character == '-' {
			result.WriteRune(character)
		}
	}
	if result.Len() == 0 {
		return "upgrade"
	}
	return result.String()
}
