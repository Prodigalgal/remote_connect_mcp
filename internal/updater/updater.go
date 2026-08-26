package updater

import (
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
	Staged     string `json:"staged"`
	Target     string `json:"target"`
	StateDir   string `json:"state_dir"`
	Service    string `json:"service"`
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
	if err := downloadVerified(ctx, plan.URL, expectedSHA, staged, logger); err != nil {
		return err
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
		CampaignID: plan.CampaignID, Staged: staged, Target: current, StateDir: stateDir, Service: serviceName,
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
