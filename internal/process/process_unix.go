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
