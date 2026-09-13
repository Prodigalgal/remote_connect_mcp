package protocol

import (
	"errors"
	"fmt"
	"strings"
)

// These limits bound untrusted JSON without constraining the shell language.
// A task can still run any command the Agent account is allowed to run.
const (
	MaxTaskCommandBytes   = 256 * 1024
	MaxTaskCWDBytes       = 16 * 1024
	MaxTaskEnvEntries     = 256
	MaxTaskEnvKeyBytes    = 256
	MaxTaskEnvValueBytes  = 16 * 1024
	MaxTaskTimeoutSeconds = 30 * 24 * 60 * 60
)

func ValidateTaskInput(command, cwd string, env map[string]string, timeoutSeconds int) error {
	if strings.IndexByte(command, 0) >= 0 {
		return errors.New("command contains NUL")
	}
	if len(command) > MaxTaskCommandBytes {
		return fmt.Errorf("command cannot exceed %d bytes", MaxTaskCommandBytes)
	}
	if strings.IndexByte(cwd, 0) >= 0 {
		return errors.New("cwd contains NUL")
	}
	if len(cwd) > MaxTaskCWDBytes {
		return fmt.Errorf("cwd cannot exceed %d bytes", MaxTaskCWDBytes)
	}
	if timeoutSeconds < 0 {
		return errors.New("timeout_seconds cannot be negative")
	}
	if timeoutSeconds > MaxTaskTimeoutSeconds {
		return fmt.Errorf("timeout_seconds cannot exceed %d", MaxTaskTimeoutSeconds)
	}
	if len(env) > MaxTaskEnvEntries {
		return fmt.Errorf("env cannot contain more than %d entries", MaxTaskEnvEntries)
	}
	for key, value := range env {
		if key == "" || len(key) > MaxTaskEnvKeyBytes {
			return fmt.Errorf("environment key must be 1-%d bytes", MaxTaskEnvKeyBytes)
		}
		if strings.IndexByte(key, 0) >= 0 || strings.ContainsRune(key, '=') {
			return fmt.Errorf("environment key %q is invalid", key)
		}
		if strings.IndexByte(value, 0) >= 0 {
			return fmt.Errorf("environment value for %q contains NUL", key)
		}
		if len(value) > MaxTaskEnvValueBytes {
			return fmt.Errorf("environment value for %q cannot exceed %d bytes", key, MaxTaskEnvValueBytes)
		}
	}
	return nil
}
