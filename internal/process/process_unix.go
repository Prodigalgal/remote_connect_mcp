//go:build linux

package process

import (
	"bytes"
	"errors"
	"os"
	"os/exec"
	"strconv"
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
	// kill(pid, 0) also succeeds for a zombie until its current parent (or
	// init) reaps it.  A restarted Agent has no Wait relationship with the
	// child, so treating that zombie as alive makes durable recovery wait
	// forever.  Read the Linux process state first and only fall back to the
	// signal probe when /proc is unavailable.
	if state, ok := linuxProcessState(pid); ok && (state == 'Z' || state == 'X') {
		return false
	}
	err := syscall.Kill(pid, 0)
	return err == nil || errors.Is(err, syscall.EPERM)
}

func linuxProcessState(pid int) (byte, bool) {
	data, err := os.ReadFile("/proc/" + strconv.Itoa(pid) + "/stat")
	if err != nil {
		return 0, false
	}
	// The executable name is enclosed in parentheses and may itself contain
	// spaces or ')'.  The state byte is therefore the first byte after the
	// final ')' followed by a space, not a fixed whitespace field index.
	closeName := bytes.LastIndexByte(data, ')')
	if closeName < 0 || closeName+2 >= len(data) || data[closeName+1] != ' ' {
		return 0, false
	}
	return data[closeName+2], true
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
