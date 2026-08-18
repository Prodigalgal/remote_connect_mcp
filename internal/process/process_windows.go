//go:build windows

package process

import (
	"os/exec"
	"syscall"

	"github.com/Prodigalgal/remote-mcp/internal/winjob"
)

type platformJob = *winjob.Job

func configureProcess(cmd *exec.Cmd) {
	if cmd.SysProcAttr == nil {
		cmd.SysProcAttr = &syscall.SysProcAttr{}
	}
	cmd.SysProcAttr.CreationFlags |= syscall.CREATE_NEW_PROCESS_GROUP
}

func shellCommand(command string) *exec.Cmd {
	if path, err := exec.LookPath("pwsh.exe"); err == nil {
		return exec.Command(path, "-NoLogo", "-NoProfile", "-Command", command)
	}
	cmd := exec.Command("cmd.exe")
	cmd.Args = nil
	cmd.SysProcAttr = &syscall.SysProcAttr{CmdLine: "/d /s /c \"" + command + "\""}
	return cmd
}

func attachJob(cmd *exec.Cmd) (platformJob, error) {
	return winjob.Attach(cmd.Process.Pid)
}

func closeJob(job platformJob) {
	_ = job.Close()
}

func killProcessTree(cmd *exec.Cmd, job platformJob) error {
	if cmd == nil || cmd.Process == nil {
		return nil
	}
	if job != nil {
		return job.Terminate()
	}
	return cmd.Process.Kill()
}
