//go:build linux

package main

import (
	"os/exec"
	"strings"
)

func copyTokenToClipboard(token string) bool {
	for _, candidate := range [][]string{{"wl-copy"}, {"xclip", "-selection", "clipboard"}} {
		path, err := exec.LookPath(candidate[0])
		if err != nil {
			continue
		}
		command := exec.Command(path, candidate[1:]...)
		command.Stdin = strings.NewReader(token)
		if command.Run() == nil {
			return true
		}
	}
	return false
}
