//go:build linux

package updater

import (
	"context"
	"fmt"
	"os/exec"
	"time"
)

func launchHelper(helper, encodedConfig, key string) error {
	unit := "remote-connect-mcp-agent-upgrade-" + safeName(key)
	command := exec.Command("systemd-run", "--unit="+unit, "--collect", "--no-block", helper, "apply-update", "--config", encodedConfig)
	output, err := command.CombinedOutput()
	if err != nil {
		return fmt.Errorf("systemd-run: %w: %s", err, output)
	}
	return nil
}

func stopService(name string, timeout time.Duration) error {
	output, err := runSystemctl(timeout, "stop", name)
	if err != nil {
		return fmt.Errorf("systemctl stop: %w: %s", err, output)
	}
	return verifySystemdState(name, "inactive")
}

func startService(name string, timeout time.Duration) error {
	output, err := runSystemctl(timeout, "start", name)
	if err != nil {
		return fmt.Errorf("systemctl start: %w: %s", err, output)
	}
	return verifySystemdState(name, "active")
}

func runSystemctl(timeout time.Duration, action, name string) ([]byte, error) {
	if timeout <= 0 {
		return nil, fmt.Errorf("systemctl %s deadline must be positive", action)
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	output, err := exec.CommandContext(ctx, "systemctl", action, name).CombinedOutput()
	if ctx.Err() != nil {
		return output, ctx.Err()
	}
	return output, err
}

func verifySystemdState(name, expected string) error {
	output, err := exec.Command("systemctl", "is-active", name).Output()
	if err == nil && string(output) == expected+"\n" {
		return nil
	}
	return fmt.Errorf("service %s did not become %s", name, expected)
}
