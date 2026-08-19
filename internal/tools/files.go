package tools

import (
	"bufio"
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/modelcontextprotocol/go-sdk/mcp"
)

type listFilesArgs struct {
	Path          string `json:"path,omitempty" jsonschema:"Path; default current directory"`
	Depth         int    `json:"depth,omitempty" jsonschema:"Recursion depth; default 2; -1 unlimited"`
	IncludeHidden bool   `json:"include_hidden,omitempty" jsonschema:"Include dot-prefixed entries"`
	Cursor        string `json:"cursor,omitempty" jsonschema:"Next cursor from prior call"`
	Limit         int    `json:"limit,omitempty" jsonschema:"Entries per page; default 200, max 2000"`
}

type fileEntry struct {
	Path    string `json:"path"`
	Type    string `json:"type"`
	Size    int64  `json:"size,omitempty"`
	ModTime string `json:"modified,omitempty"`
}

var errPageFull = errors.New("page full")

func (s *Service) listFiles(ctx context.Context, _ *mcp.CallToolRequest, args listFilesArgs) (*mcp.CallToolResult, any, error) {
	start := time.Now()
	root, err := s.resolvePath(args.Path)
	if err != nil {
		return fail("list_files", s, start, err)
	}
	depth := args.Depth
	if depth == 0 {
		depth = 2
	}
	limit := args.Limit
	if limit <= 0 {
		limit = 200
	}
	limit = min(limit, 2000)
	offset, err := parseCursor(args.Cursor)
	if err != nil {
		return fail("list_files", s, start, err)
	}
	var entries []fileEntry
	seen := 0
	err = filepath.WalkDir(root, func(path string, entry fs.DirEntry, walkErr error) error {
		if err := ctx.Err(); err != nil {
			return err
		}
		if path == root {
			return walkErr
		}
		rel, _ := filepath.Rel(root, path)
		if walkErr != nil {
			if seen >= offset {
				entries = append(entries, fileEntry{Path: filepath.ToSlash(rel), Type: "error: " + walkErr.Error()})
				if len(entries) > limit {
					return errPageFull
				}
			}
			seen++
			return nil
		}
		level := strings.Count(filepath.ToSlash(rel), "/") + 1
		if !args.IncludeHidden && strings.HasPrefix(entry.Name(), ".") {
			if entry.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}
		if depth >= 0 && level > depth {
			if entry.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}
		var item fileEntry
		info, infoErr := entry.Info()
		if infoErr != nil {
			item = fileEntry{Path: filepath.ToSlash(rel), Type: "error: " + infoErr.Error()}
		} else {
			typeName := "file"
			if entry.IsDir() {
				typeName = "dir"
			} else if info.Mode()&os.ModeSymlink != 0 {
				typeName = "symlink"
			}
			item = fileEntry{Path: filepath.ToSlash(rel), Type: typeName, Size: info.Size(), ModTime: info.ModTime().Format(time.RFC3339)}
		}
		if seen >= offset {
			entries = append(entries, item)
			if len(entries) > limit {
				return errPageFull
			}
		}
		seen++
		return nil
	})
	if err != nil && !errors.Is(err, errPageFull) {
		return fail("list_files", s, start, err)
	}
	hasMore := len(entries) > limit
	if hasMore {
		entries = entries[:limit]
	}
	result := map[string]any{"root": root, "entries": entries, "count": len(entries)}
	if hasMore {
		result["next_cursor"] = strconv.Itoa(offset + len(entries))
	}
	s.log("list_files", start, nil)
	return jsonResult(result)
}

type searchTextArgs struct {
	Query         string   `json:"query" jsonschema:"Literal text or regular expression"`
	Path          string   `json:"path,omitempty" jsonschema:"File or directory; default current directory"`
	Regex         bool     `json:"regex,omitempty" jsonschema:"Treat query as Go regular expression"`
	CaseSensitive bool     `json:"case_sensitive,omitempty"`
	Include       []string `json:"include,omitempty" jsonschema:"Optional filepath globs"`
	Exclude       []string `json:"exclude,omitempty" jsonschema:"Optional filepath globs"`
	IncludeHidden bool     `json:"include_hidden,omitempty"`
	Cursor        string   `json:"cursor,omitempty"`
	Limit         int      `json:"limit,omitempty" jsonschema:"Matches per page; default 100, max 1000"`
}

type searchMatch struct {
	Path string `json:"path"`
	Line int    `json:"line"`
	Text string `json:"text"`
}

func (s *Service) searchText(ctx context.Context, _ *mcp.CallToolRequest, args searchTextArgs) (*mcp.CallToolResult, any, error) {
	start := time.Now()
	if args.Query == "" {
		return fail("search_text", s, start, fmt.Errorf("query is required"))
	}
	root, err := s.resolvePath(args.Path)
	if err != nil {
		return fail("search_text", s, start, err)
	}
	offset, err := parseCursor(args.Cursor)
	if err != nil {
		return fail("search_text", s, start, err)
	}
	limit := args.Limit
	if limit <= 0 {
		limit = 100
	}
	limit = min(limit, 1000)
	pattern := args.Query
	if !args.Regex {
		pattern = regexp.QuoteMeta(pattern)
	}
	if !args.CaseSensitive {
		pattern = "(?i)" + pattern
	}
	re, err := regexp.Compile(pattern)
	if err != nil {
		return fail("search_text", s, start, err)
	}

	var matches []searchMatch
	seen := 0
	scanFile := func(path, display string) error {
		file, err := os.Open(path)
		if err != nil {
			return nil
		}
		defer file.Close()
		scanner := bufio.NewScanner(file)
		scanner.Buffer(make([]byte, 64*1024), 16*1024*1024)
		line := 0
		for scanner.Scan() {
			if err := ctx.Err(); err != nil {
				return err
			}
			line++
			if !re.Match(scanner.Bytes()) {
				continue
			}
			if seen >= offset {
				text := scanner.Text()
				if len(text) > 1000 {
					text = text[:1000]
				}
				matches = append(matches, searchMatch{Path: display, Line: line, Text: text})
				if len(matches) > limit {
					return errPageFull
				}
			}
			seen++
		}
		return scanner.Err()
	}

	info, err := os.Stat(root)
	if err != nil {
		return fail("search_text", s, start, err)
	}
	if info.IsDir() {
		err = filepath.WalkDir(root, func(path string, entry fs.DirEntry, walkErr error) error {
			if walkErr != nil {
				return nil
			}
			if err := ctx.Err(); err != nil {
				return err
			}
			if entry.IsDir() {
				if path != root && !args.IncludeHidden && strings.HasPrefix(entry.Name(), ".") {
					return filepath.SkipDir
				}
				return nil
			}
			rel, _ := filepath.Rel(root, path)
			rel = filepath.ToSlash(rel)
			if (!args.IncludeHidden && strings.HasPrefix(entry.Name(), ".")) || !matchesGlobs(rel, args.Include, args.Exclude) {
				return nil
			}
			return scanFile(path, rel)
		})
	} else {
		err = scanFile(root, filepath.Base(root))
	}
	if err != nil && !errors.Is(err, errPageFull) {
		return fail("search_text", s, start, err)
	}
	hasMore := len(matches) > limit
	if hasMore {
		matches = matches[:limit]
	}
	result := map[string]any{"matches": matches, "count": len(matches)}
	if hasMore {
		result["next_cursor"] = strconv.Itoa(offset + len(matches))
	}
	s.log("search_text", start, nil)
	return jsonResult(result)
}

type readFileArgs struct {
	Path       string `json:"path" jsonschema:"Any local file path"`
	ByteOffset int64  `json:"byte_offset,omitempty" jsonschema:"Byte offset; default 0"`
	ByteLimit  int    `json:"byte_limit,omitempty" jsonschema:"Bytes to return; default 32768, max 1048576"`
	Encoding   string `json:"encoding,omitempty" jsonschema:"auto, text, or base64"`
}

func (s *Service) readFile(ctx context.Context, _ *mcp.CallToolRequest, args readFileArgs) (*mcp.CallToolResult, any, error) {
	start := time.Now()
	path, err := s.resolvePath(args.Path)
	if err != nil {
		return fail("read_file", s, start, err)
	}
	file, err := os.Open(path)
	if err != nil {
		return fail("read_file", s, start, err)
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		return fail("read_file", s, start, err)
	}
	if info.IsDir() {
		return fail("read_file", s, start, fmt.Errorf("path is a directory"))
	}
	if args.ByteOffset < 0 || args.ByteOffset > info.Size() {
		return fail("read_file", s, start, fmt.Errorf("byte_offset out of range"))
	}
	limit := args.ByteLimit
	if limit <= 0 {
		limit = 32 * 1024
	}
	limit = min(limit, 1024*1024)
	if _, err := file.Seek(args.ByteOffset, io.SeekStart); err != nil {
		return fail("read_file", s, start, err)
	}
	data, err := io.ReadAll(io.LimitReader(file, int64(limit)))
	if err != nil {
		return fail("read_file", s, start, err)
	}
	encoding := strings.ToLower(args.Encoding)
	if encoding == "" || encoding == "auto" {
		if utf8.Valid(data) {
			encoding = "text"
		} else {
			encoding = "base64"
		}
	}
	var content string
	switch encoding {
	case "text":
		content = string(data)
	case "base64":
		content = base64.StdEncoding.EncodeToString(data)
	default:
		return fail("read_file", s, start, fmt.Errorf("encoding must be auto, text, or base64"))
	}
	next := args.ByteOffset + int64(len(data))
	result := map[string]any{"path": path, "encoding": encoding, "byte_offset": args.ByteOffset, "bytes": len(data), "total_bytes": info.Size(), "content": content}
	if next < info.Size() {
		result["next_byte_offset"] = next
	}
	s.log("read_file", start, nil)
	return jsonResult(result)
}

func matchesGlobs(path string, includes, excludes []string) bool {
	match := len(includes) == 0
	for _, pattern := range includes {
		if globMatch(pattern, path) {
			match = true
			break
		}
	}
	if !match {
		return false
	}
	for _, pattern := range excludes {
		if globMatch(pattern, path) {
			return false
		}
	}
	return true
}

func globMatch(pattern, path string) bool {
	pattern = filepath.ToSlash(pattern)
	path = filepath.ToSlash(path)
	if ok, _ := filepath.Match(filepath.FromSlash(pattern), filepath.FromSlash(path)); ok {
		return true
	}
	if strings.HasPrefix(pattern, "**/") {
		ok, _ := filepath.Match(filepath.FromSlash(strings.TrimPrefix(pattern, "**/")), filepath.FromSlash(filepath.Base(path)))
		return ok
	}
	return false
}
