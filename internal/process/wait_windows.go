//go:build windows

package process

import "golang.org/x/sys/windows"

// waitForProcessExit blocks on a Windows process handle.  The kernel signals
// the handle at process exit; there is no periodic liveness probe.  It returns
// false only when the handle cannot be opened.
func waitForProcessExit(pid int) bool {
	if pid <= 0 {
		return true
	}
	handle, err := windows.OpenProcess(windows.SYNCHRONIZE, false, uint32(pid))
	if err != nil {
		return false
	}
	defer windows.CloseHandle(handle)
	_, err = windows.WaitForSingleObject(handle, windows.INFINITE)
	return err == nil
}
