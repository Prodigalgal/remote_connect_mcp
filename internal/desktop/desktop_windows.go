//go:build windows

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

	// A desktop Agent is started in the interactive user's session. A Windows
	// SCM service normally runs in Session 0 and cannot see that desktop.
	script := "$ErrorActionPreference='Stop'; Add-Type -AssemblyName System.Windows.Forms; Add-Type -AssemblyName System.Drawing; $b=[System.Windows.Forms.SystemInformation]::VirtualScreen; if($b.Width -le 0 -or $b.Height -le 0){$b=[System.Windows.Forms.Screen]::PrimaryScreen.Bounds}; $bmp=[System.Drawing.Bitmap]::new($b.Width,$b.Height); try{$g=[System.Drawing.Graphics]::FromImage($bmp); try{$g.CopyFromScreen($b.X,$b.Y,0,0,$bmp.Size)}finally{$g.Dispose()}; $bmp.Save($env:RCM_DESKTOP_SCREENSHOT,[System.Drawing.Imaging.ImageFormat]::Png)}finally{$bmp.Dispose()}"
	command := exec.CommandContext(ctx, "powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script)
	command.Env = append(os.Environ(), "RCM_DESKTOP_SCREENSHOT="+path)
	output, err := command.CombinedOutput()
	if err != nil {
		message := strings.TrimSpace(string(output))
		if len(message) > 2048 {
			message = message[:2048]
		}
		return Result{}, fmt.Errorf("capture desktop screenshot: %w: %s", err, message)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return Result{}, fmt.Errorf("read screenshot: %w", err)
	}
	if len(data) == 0 {
		return Result{}, errors.New("desktop screenshot is empty")
	}
	if len(data) > MaxScreenshotBytes {
		return Result{}, fmt.Errorf("desktop screenshot exceeds %d bytes", MaxScreenshotBytes)
	}
	if len(data) < 8 || string(data[:8]) != "\x89PNG\r\n\x1a\n" {
		return Result{}, errors.New("desktop screenshot is not a PNG")
	}
	return Result{MIMEType: "image/png", Data: data, Summary: fmt.Sprintf("screenshot captured (%d bytes)", len(data))}, nil
}
