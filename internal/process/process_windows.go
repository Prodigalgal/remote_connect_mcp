//go:build windows

package process

import (
	"os/exec"
	"syscall"

	"golang.org/x/sys/windows"

	"github.com/Prodigalgal/remote_connect_mcp/internal/winjob"
)

const processStillActive = 259

type platformJob = *winjob.Job

func configureProcess(cmd *exec.Cmd) {
	if cmd.SysProcAttr == nil {
		cmd.SysProcAttr = &syscall.SysProcAttr{}
	}
	cmd.SysProcAttr.CreationFlags |= syscall.CREATE_NEW_PROCESS_GROUP
}

func configureDurableProcess(cmd *exec.Cmd) {
	configureProcess(cmd)
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

func killPID(pid int) error {
	if pid <= 0 {
		return nil
	}
	process, err := windows.OpenProcess(windows.PROCESS_TERMINATE, false, uint32(pid))
	if err != nil {
		if err == windows.ERROR_INVALID_PARAMETER {
			return nil
		}
		return err
	}
	defer windows.CloseHandle(process)
	return windows.TerminateProcess(process, 1)
}

func processAlive(pid int) bool {
	_, alive := processExitCode(pid)
	return alive
}

func processExitCode(pid int) (int, bool) {
	if pid <= 0 {
		return -1, false
	}
	process, err := windows.OpenProcess(windows.PROCESS_QUERY_LIMITED_INFORMATION, false, uint32(pid))
	if err != nil {
		return -1, false
	}
	defer windows.CloseHandle(process)
	var exitCode uint32
	if err := windows.GetExitCodeProcess(process, &exitCode); err != nil {
		return -1, false
	}
	if exitCode == processStillActive {
		return -1, true
	}
	return int(exitCode), false
}
