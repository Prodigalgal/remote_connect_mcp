//go:build windows

package winjob

import (
	"fmt"
	"sync"
	"unsafe"

	"golang.org/x/sys/windows"
)

type Job struct {
	handle windows.Handle
	mu     sync.Mutex
}

func Attach(pid int) (*Job, error) {
	handle, err := windows.CreateJobObject(nil, nil)
	if err != nil {
		return nil, err
	}
	info := windows.JOBOBJECT_EXTENDED_LIMIT_INFORMATION{}
	info.BasicLimitInformation.LimitFlags = windows.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
	_, err = windows.SetInformationJobObject(
		handle,
		windows.JobObjectExtendedLimitInformation,
		uintptr(unsafe.Pointer(&info)),
		uint32(unsafe.Sizeof(info)),
	)
	if err != nil {
		_ = windows.CloseHandle(handle)
		return nil, fmt.Errorf("configure job object: %w", err)
	}
	process, err := windows.OpenProcess(windows.PROCESS_SET_QUOTA|windows.PROCESS_TERMINATE, false, uint32(pid))
	if err != nil {
		_ = windows.CloseHandle(handle)
		return nil, fmt.Errorf("open process: %w", err)
	}
	defer windows.CloseHandle(process)
	if err := windows.AssignProcessToJobObject(handle, process); err != nil {
		_ = windows.CloseHandle(handle)
		return nil, fmt.Errorf("assign process to job: %w", err)
	}
	return &Job{handle: handle}, nil
}

func (j *Job) Terminate() error {
	if j == nil {
		return nil
	}
	j.mu.Lock()
	defer j.mu.Unlock()
	if j.handle == 0 {
		return nil
	}
	return windows.TerminateJobObject(j.handle, 1)
}

func (j *Job) Close() error {
	if j == nil {
		return nil
	}
	j.mu.Lock()
	defer j.mu.Unlock()
	if j.handle == 0 {
		return nil
	}
	err := windows.CloseHandle(j.handle)
	j.handle = 0
	return err
}
