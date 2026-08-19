package config

import (
	"bufio"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

func loadEnvFile(cwd string) (string, error) {
	configured, explicit := os.LookupEnv("REMOTE_CONNECT_MCP_ENV_FILE")
	path := strings.TrimSpace(configured)
	if path == "" {
		path = filepath.Join(cwd, "remote_connect_mcp.env")
	} else if !filepath.IsAbs(path) {
		path = filepath.Join(cwd, path)
	}
	path = filepath.Clean(path)

	file, err := os.Open(path)
	if os.IsNotExist(err) && !explicit {
		return path, nil
	}
	if err != nil {
		return "", fmt.Errorf("open env file %s: %w", path, err)
	}
	defer file.Close()

	scanner := bufio.NewScanner(file)
	scanner.Buffer(make([]byte, 4096), 1024*1024)
	lineNumber := 0
	for scanner.Scan() {
		lineNumber++
		line := strings.TrimSpace(strings.TrimPrefix(scanner.Text(), "\ufeff"))
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		if strings.HasPrefix(line, "export ") {
			line = strings.TrimSpace(strings.TrimPrefix(line, "export "))
		}
		index := strings.IndexByte(line, '=')
		if index < 1 {
			return "", fmt.Errorf("%s:%d: expected KEY=VALUE", path, lineNumber)
		}
		key := strings.TrimSpace(line[:index])
		if !validEnvKey(key) {
			return "", fmt.Errorf("%s:%d: invalid environment key %q", path, lineNumber, key)
		}
		value, err := parseEnvValue(strings.TrimSpace(line[index+1:]))
		if err != nil {
			return "", fmt.Errorf("%s:%d: %w", path, lineNumber, err)
		}
		if _, exists := os.LookupEnv(key); !exists {
			if err := os.Setenv(key, value); err != nil {
				return "", fmt.Errorf("%s:%d: set %s: %w", path, lineNumber, key, err)
			}
		}
	}
	if err := scanner.Err(); err != nil {
		return "", fmt.Errorf("read env file %s: %w", path, err)
	}
	return path, nil
}

func validEnvKey(key string) bool {
	if key == "" || !isEnvKeyStart(key[0]) {
		return false
	}
	for index := 1; index < len(key); index++ {
		character := key[index]
		if !isEnvKeyStart(character) && (character < '0' || character > '9') {
			return false
		}
	}
	return true
}

func isEnvKeyStart(character byte) bool {
	return character == '_' || character >= 'A' && character <= 'Z' || character >= 'a' && character <= 'z'
}

func parseEnvValue(value string) (string, error) {
	if len(value) < 2 {
		return value, nil
	}
	if value[0] == '\'' && value[len(value)-1] == '\'' {
		return value[1 : len(value)-1], nil
	}
	if value[0] == '"' && value[len(value)-1] == '"' {
		parsed, err := strconv.Unquote(value)
		if err != nil {
			return "", fmt.Errorf("invalid quoted value: %w", err)
		}
		return parsed, nil
	}
	return value, nil
}
