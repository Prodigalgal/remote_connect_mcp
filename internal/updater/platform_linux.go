//go:build linux

package updater

import (
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
	command := exec.Command("systemctl", "stop", name)
	if output, err := command.CombinedOutput(); err != nil {
		return fmt.Errorf("systemctl stop: %w: %s", err, output)
	}
	return waitSystemdState(name, "inactive", timeout)
}

func startService(name string, timeout time.Duration) error {
	command := exec.Command("systemctl", "start", name)
	if output, err := command.CombinedOutput(); err != nil {
		return fmt.Errorf("systemctl start: %w: %s", err, output)
	}
	return waitSystemdState(name, "active", timeout)
}

func waitSystemdState(name, expected string, timeout time.Duration) error {
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		output, _ := exec.Command("systemctl", "is-active", name).Output()
		if string(output) == expected+"\n" {
			return nil
		}
		time.Sleep(500 * time.Millisecond)
	}
	return fmt.Errorf("service %s did not become %s", name, expected)
}
