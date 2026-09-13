//go:build linux

package updater

import (
	"context"
	"fmt"
	"os/exec"
	"strings"
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
	return waitSystemdState(name, "inactive", timeout)
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
	args := []string{}
	// systemd supports --wait for start/restart, but not for stop.  Stop is
	// followed by waitSystemdState below because long-polling Agents can remain
	// in `deactivating` after the stop job is queued.
	if action == "start" || action == "restart" {
		args = append(args, "--wait")
	}
	args = append(args, action, name)
	output, err := exec.CommandContext(ctx, "systemctl", args...).CombinedOutput()
	if ctx.Err() != nil {
		return output, ctx.Err()
	}
	return output, err
}

func verifySystemdState(name, expected string) error {
	output, err := exec.Command("systemctl", "is-active", name).Output()
	if err == nil && strings.TrimSpace(string(output)) == expected {
		return nil
	}
	return fmt.Errorf("service %s did not become %s", name, expected)
}

func waitSystemdState(name, expected string, timeout time.Duration) error {
	if timeout <= 0 {
		return fmt.Errorf("service state deadline must be positive")
	}
	deadline := time.Now().Add(timeout)
	for {
		if verifySystemdState(name, expected) == nil {
			return nil
		}
		if time.Now().After(deadline) {
			return fmt.Errorf("service %s did not become %s", name, expected)
		}
		time.Sleep(250 * time.Millisecond)
	}
}
