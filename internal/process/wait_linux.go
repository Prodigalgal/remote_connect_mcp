//go:build linux

package process

import "golang.org/x/sys/unix"

// waitForProcessExit blocks on a Linux pidfd.  Unlike repeatedly probing
// /proc or kill(2), pidfd readiness is delivered by the kernel when the
// process exits and does not wake a goroutine on a fixed timer.  It returns
// false only when this kernel cannot provide a pidfd.
func waitForProcessExit(pid int) bool {
	if pid <= 0 {
		return true
	}
	fd, err := unix.PidfdOpen(pid, 0)
	if err != nil {
		// The process may have exited between processAlive and pidfd_open, or
		// the kernel may be too old to expose pidfds. The caller performs one
		// authoritative state read after this return.
		return false
	}
	defer unix.Close(fd)
	fds := []unix.PollFd{{Fd: int32(fd), Events: unix.POLLIN}}
	for {
		_, err = unix.Poll(fds, -1)
		if err == unix.EINTR {
			continue
		}
		return err == nil
	}
}
