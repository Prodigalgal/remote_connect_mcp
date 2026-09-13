//go:build !windows

package desktop

import (
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"strings"
)

func Supported() bool { return true }

func execute(ctx context.Context, action Action) (Result, error) {
	switch strings.ToLower(strings.TrimSpace(action.Operation)) {
	case OperationLaunch:
		cmd := exec.CommandContext(ctx, action.Executable, action.Args...)
		if action.CWD != "" {
			cmd.Dir = action.CWD
		}
		if err := cmd.Start(); err != nil {
			return Result{}, fmt.Errorf("launch desktop application: %w", err)
		}
		return Result{Summary: fmt.Sprintf("launched process %d", cmd.Process.Pid)}, nil
	case OperationScreenshot:
		return screenshot(ctx)
	default:
		return Result{}, errors.New("unsupported desktop operation")
	}
}

func screenshot(ctx context.Context) (Result, error) {
	file, err := os.CreateTemp("", "remote-connect-mcp-desktop-*.png")
	if err != nil {
		return Result{}, fmt.Errorf("create screenshot file: %w", err)
	}
	path := file.Name()
	if err := file.Close(); err != nil {
		_ = os.Remove(path)
		return Result{}, err
	}
	defer os.Remove(path)
	commands := [][]string{{"gnome-screenshot", "-f", path}, {"scrot", path}, {"import", "-window", "root", path}, {"grim", path}}
	var lastErr error
	for _, args := range commands {
		if _, err := exec.LookPath(args[0]); err != nil {
			lastErr = err
			continue
		}
		if output, err := exec.CommandContext(ctx, args[0], args[1:]...).CombinedOutput(); err != nil {
			lastErr = fmt.Errorf("%s: %w: %s", args[0], err, strings.TrimSpace(string(output)))
			continue
		}
		data, err := os.ReadFile(path)
		if err != nil {
			lastErr = err
			continue
		}
		if len(data) == 0 {
			lastErr = errors.New("desktop screenshot is empty")
			continue
		}
		if len(data) > MaxScreenshotBytes {
			return Result{}, fmt.Errorf("desktop screenshot exceeds %d bytes", MaxScreenshotBytes)
		}
		if len(data) < 8 || string(data[:8]) != "\x89PNG\r\n\x1a\n" {
			lastErr = errors.New("desktop screenshot is not a PNG")
			continue
		}
		return Result{MIMEType: "image/png", Data: data, Summary: fmt.Sprintf("screenshot captured (%d bytes)", len(data))}, nil
	}
	if lastErr == nil {
		lastErr = errors.New("no supported screenshot utility found")
	}
	return Result{}, fmt.Errorf("capture desktop screenshot: %w", lastErr)
}
