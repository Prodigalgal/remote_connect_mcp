//go:build linux

package process

import (
	"errors"
	"os/exec"
	"syscall"
)

type platformJob struct{}

func configureProcess(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true, Pdeathsig: syscall.SIGKILL}
}

func configureDurableProcess(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
}

func shellCommand(command string) *exec.Cmd {
	return exec.Command("/bin/sh", "-c", command)
}

func attachJob(_ *exec.Cmd) (platformJob, error) { return platformJob{}, nil }
func closeJob(_ platformJob)                     {}

func killProcessTree(cmd *exec.Cmd, _ platformJob) error {
	if cmd == nil || cmd.Process == nil {
		return nil
	}
	err := syscall.Kill(-cmd.Process.Pid, syscall.SIGKILL)
	if errors.Is(err, syscall.ESRCH) {
		return nil
	}
	return err
}

func killPID(pid int) error {
	if pid <= 0 {
		return nil
	}
	err := syscall.Kill(-pid, syscall.SIGKILL)
	if errors.Is(err, syscall.ESRCH) {
		return nil
	}
	return err
}

func processAlive(pid int) bool {
	if pid <= 0 {
		return false
	}
	err := syscall.Kill(pid, 0)
	return err == nil || errors.Is(err, syscall.EPERM)
}

func processExitCode(pid int) (int, bool) {
	if processAlive(pid) {
		return -1, true
	}
	// A non-child process cannot be waited on by the restarted Agent. Linux
	// does not expose a stable exit code without owning the wait relationship,
	// so callers treat the outcome as unknown and avoid replaying it.
	return -1, false
}
