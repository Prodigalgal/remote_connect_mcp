//go:build linux

package tunnel

import (
	"errors"
	"os/exec"
	"syscall"
)

type platformJob struct{}

func configureCommand(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true, Pdeathsig: syscall.SIGKILL}
}

func attachJob(_ *exec.Cmd) (platformJob, error) { return platformJob{}, nil }
func closeJob(_ platformJob)                     {}

func stopProcess(cmd *exec.Cmd, _ platformJob) error {
	if cmd == nil || cmd.Process == nil {
		return nil
	}
	err := syscall.Kill(-cmd.Process.Pid, syscall.SIGTERM)
	if errors.Is(err, syscall.ESRCH) {
		return nil
	}
	return err
}
