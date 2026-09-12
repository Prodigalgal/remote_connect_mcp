package protocol

import (
	"strings"
	"testing"
)

func TestValidateTaskInputBounds(t *testing.T) {
	if err := ValidateTaskInput(strings.Repeat("x", MaxTaskCommandBytes+1), "", nil, 0); err == nil {
		t.Fatal("oversized command was accepted")
	}
	if err := ValidateTaskInput("echo ok", "", nil, MaxTaskTimeoutSeconds+1); err == nil {
		t.Fatal("oversized timeout was accepted")
	}
	if err := ValidateTaskInput("echo ok", "", map[string]string{"BAD=KEY": "value"}, 0); err == nil {
		t.Fatal("invalid environment key was accepted")
	}
	if err := ValidateTaskInput("echo\x00ok", "", nil, 0); err == nil {
		t.Fatal("NUL command was accepted")
	}
	if err := ValidateTaskInput("echo ok", "", map[string]string{"SAFE": "value"}, 0); err != nil {
		t.Fatalf("valid task was rejected: %v", err)
	}
}
