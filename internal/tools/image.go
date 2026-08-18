package tools

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/modelcontextprotocol/go-sdk/mcp"
)

type viewImageArgs struct {
	Path string `json:"path" jsonschema:"Any local image path"`
}

func (s *Service) viewImage(ctx context.Context, _ *mcp.CallToolRequest, args viewImageArgs) (*mcp.CallToolResult, any, error) {
	start := time.Now()
	path, err := s.resolvePath(args.Path)
	if err != nil {
		return fail("view_image", s, start, err)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return fail("view_image", s, start, err)
	}
	mimeType := mimeFromExtension(path)
	if mimeType == "" {
		mimeType = http.DetectContentType(data)
	}
	if !strings.HasPrefix(mimeType, "image/") {
		return fail("view_image", s, start, fmt.Errorf("unsupported image type: %s", mimeType))
	}
	s.log("view_image", start, nil)
	return &mcp.CallToolResult{Content: []mcp.Content{&mcp.ImageContent{Data: data, MIMEType: mimeType}}}, nil, nil
}

func mimeFromExtension(path string) string {
	switch strings.ToLower(filepath.Ext(path)) {
	case ".png":
		return "image/png"
	case ".jpg", ".jpeg":
		return "image/jpeg"
	case ".gif":
		return "image/gif"
	case ".webp":
		return "image/webp"
	case ".bmp":
		return "image/bmp"
	case ".svg":
		return "image/svg+xml"
	default:
		return ""
	}
}
