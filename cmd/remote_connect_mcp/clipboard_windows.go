//go:build windows

package main

import (
	"os/exec"
	"strings"
)

func copyTokenToClipboard(token string) bool {
	command := exec.Command("clip.exe")
	command.Stdin = strings.NewReader(token)
	return command.Run() == nil
}
