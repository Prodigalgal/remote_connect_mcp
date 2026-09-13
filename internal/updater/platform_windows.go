//go:build windows

package updater

import (
	"fmt"
	"os/exec"
	"runtime"
	"syscall"
	"time"

	"golang.org/x/sys/windows"
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
	if timeout <= 0 {
		return fmt.Errorf("service did not reach state %d before the deadline", expected)
	}
	// NotifyServiceStatusChange delivers the callback as an APC to the calling
	// thread. Pin this short-lived helper to one OS thread and enter an
	// alertable SleepEx instead of querying the SCM every 500 ms.
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	deadline := time.Now().Add(timeout)
	for {
		status, err := service.Query()
		if err == nil && status.State == expected {
			return nil
		}
		remaining := time.Until(deadline)
		if remaining <= 0 {
			if err != nil {
				return fmt.Errorf("query service state: %w", err)
			}
			return fmt.Errorf("service did not reach state %d before the deadline", expected)
		}

		notifier := &windows.SERVICE_NOTIFY{Version: windows.SERVICE_NOTIFY_STATUS_CHANGE}
		// PFN_SC_NOTIFY_CALLBACK receives the caller-provided SERVICE_NOTIFY
		// pointer. The callback deliberately does no work; after SleepEx returns
		// the owning goroutine reads the populated structure and re-registers if
		// an intermediate state was reported.
		notifier.NotifyCallback = windows.NewCallback(func(_ uintptr) uintptr { return 0 })
		if err := windows.NotifyServiceStatusChange(service.Handle,
			serviceNotifyMask(expected), notifier); err != nil {
			return fmt.Errorf("register service status notification: %w", err)
		}
		maxMillis := uint64(remaining / time.Millisecond)
		if maxMillis == 0 {
			maxMillis = 1
		}
		if maxMillis > uint64(^uint32(0)) {
			maxMillis = uint64(^uint32(0))
		}
		result := windows.SleepEx(uint32(maxMillis), true)
		if result == 0x102 { // WAIT_TIMEOUT
			status, err := service.Query()
			if err == nil && status.State == expected {
				return nil
			}
			return fmt.Errorf("service did not reach state %d before the deadline", expected)
		}
		if result != 0xC0 { // WAIT_IO_COMPLETION
			return fmt.Errorf("alertable service wait failed with result 0x%x", result)
		}
		if notifier.NotificationStatus != 0 {
			return fmt.Errorf("service status notification failed with code %d", notifier.NotificationStatus)
		}
		if svc.State(notifier.ServiceStatus.CurrentState) == expected {
			return nil
		}
	}
}

func serviceNotifyMask(expected svc.State) uint32 {
	if expected == svc.Running {
		return windows.SERVICE_NOTIFY_START_PENDING | windows.SERVICE_NOTIFY_RUNNING
	}
	return windows.SERVICE_NOTIFY_STOP_PENDING | windows.SERVICE_NOTIFY_STOPPED
}
