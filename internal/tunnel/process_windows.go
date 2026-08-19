//go:build windows

package tunnel

import (
	"os/exec"
	"syscall"

	"github.com/Prodigalgal/remote_connect_mcp/internal/winjob"
)

type platformJob = *winjob.Job

func configureCommand(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{CreationFlags: syscall.CREATE_NEW_PROCESS_GROUP}
}

func attachJob(cmd *exec.Cmd) (platformJob, error) { return winjob.Attach(cmd.Process.Pid) }
func closeJob(job platformJob)                     { _ = job.Close() }

func stopProcess(cmd *exec.Cmd, job platformJob) error {
	if cmd == nil || cmd.Process == nil {
		return nil
	}
	if job != nil {
		return job.Terminate()
	}
	return cmd.Process.Kill()
}
