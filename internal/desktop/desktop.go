// Package desktop contains the optional user-session desktop surface. It is
// intentionally small so desktop data does not expand the MCP tool schema.
package desktop

import (
	"context"
	"errors"
	"fmt"
	"strings"

	"github.com/Prodigalgal/remote_connect_mcp/internal/protocol"
)

const (
	OperationScreenshot = "screenshot"
	OperationLaunch     = "launch"
	MaxExecutableBytes  = 4096
	MaxArgumentBytes    = 4096
	MaxArguments        = 128
	MaxArgumentTotal    = 64 * 1024
	MaxScreenshotBytes  = 8 * 1024 * 1024
)

type Action struct {
	Operation  string
	Executable string
	Args       []string
	CWD        string
}

type Result struct {
	MIMEType string
	Data     []byte
	Summary  string
}

// ValidateAction applies protocol/resource safety checks without an
// executable allow-list. The OS account and deployment policy remain the
// authority for what the desktop Agent may launch.
func ValidateAction(action Action) error {
	action.Operation = strings.ToLower(strings.TrimSpace(action.Operation))
	switch action.Operation {
	case OperationScreenshot:
		if action.Executable != "" || len(action.Args) > 0 {
			return errors.New("screenshot does not accept executable or args")
		}
	case OperationLaunch:
		if strings.TrimSpace(action.Executable) == "" {
			return errors.New("launch executable is required")
		}
	default:
		return fmt.Errorf("unsupported desktop operation %q", action.Operation)
	}
	if strings.IndexByte(action.Executable, 0) >= 0 {
		return errors.New("desktop executable contains NUL")
	}
	if strings.IndexByte(action.CWD, 0) >= 0 {
		return errors.New("desktop cwd contains NUL")
	}
	if len(action.CWD) > 16*1024 {
		return errors.New("desktop cwd is too long")
	}
	if len(action.Executable) > MaxExecutableBytes {
		return fmt.Errorf("desktop executable cannot exceed %d bytes", MaxExecutableBytes)
	}
	if len(action.Args) > MaxArguments {
		return fmt.Errorf("desktop args cannot contain more than %d items", MaxArguments)
	}
	total := 0
	for _, arg := range action.Args {
		if strings.IndexByte(arg, 0) >= 0 {
			return errors.New("desktop argument contains NUL")
		}
		if len(arg) > MaxArgumentBytes {
			return fmt.Errorf("desktop argument cannot exceed %d bytes", MaxArgumentBytes)
		}
		total += len(arg)
		if total > MaxArgumentTotal {
			return fmt.Errorf("desktop arguments cannot exceed %d bytes", MaxArgumentTotal)
		}
	}
	return nil
}

func Execute(ctx context.Context, value *protocol.DesktopAction) (Result, error) {
	if value == nil {
		return Result{}, errors.New("desktop action is required")
	}
	action := Action{Operation: value.Operation, Executable: value.Executable, Args: append([]string(nil), value.Args...), CWD: value.CWD}
	if err := ValidateAction(action); err != nil {
		return Result{}, err
	}
	return execute(ctx, action)
}
