//go:build windows

package updater

import (
	"fmt"
	"os/exec"
	"syscall"
	"time"

	"golang.org/x/sys/windows/svc"
	"golang.org/x/sys/windows/svc/mgr"
)

func launchHelper(helper, encodedConfig, _ string) error {
	command := exec.Command(helper, "apply-update", "--config", encodedConfig)
	command.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x00000008 | 0x00000200 | 0x08000000}
	return command.Start()
}

func stopService(name string, timeout time.Duration) error {
	manager, err := mgr.Connect()
	if err != nil {
		return err
	}
	defer manager.Disconnect()
	service, err := manager.OpenService(name)
	if err != nil {
		return err
	}
	defer service.Close()
	status, err := service.Query()
	if err != nil {
		return err
	}
	if status.State != svc.Stopped {
		if _, err := service.Control(svc.Stop); err != nil {
			return err
		}
	}
	return waitWindowsState(service, svc.Stopped, timeout)
}

func startService(name string, timeout time.Duration) error {
	manager, err := mgr.Connect()
	if err != nil {
		return err
	}
	defer manager.Disconnect()
	service, err := manager.OpenService(name)
	if err != nil {
		return err
	}
	defer service.Close()
	status, err := service.Query()
	if err != nil {
		return err
	}
	if status.State != svc.Running {
		if err := service.Start(); err != nil {
			return err
		}
	}
	return waitWindowsState(service, svc.Running, timeout)
}

func waitWindowsState(service *mgr.Service, expected svc.State, timeout time.Duration) error {
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		status, err := service.Query()
		if err == nil && status.State == expected {
			return nil
		}
		time.Sleep(500 * time.Millisecond)
	}
	return fmt.Errorf("service did not reach state %d", expected)
}
