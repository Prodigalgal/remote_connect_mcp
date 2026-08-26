#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"
command -v go >/dev/null 2>&1 || { echo "Go was not found." >&2; exit 1; }

VERSION=$(git describe --tags --always --dirty 2>/dev/null || go env GOVERSION)
go test -mod=mod ./...
for TARGET in windows/amd64 windows/arm64 linux/amd64 linux/arm64; do
  OS=${TARGET%/*}
  ARCH=${TARGET#*/}
  SUFFIX=
  [ "$OS" = windows ] && SUFFIX=.exe
  for COMPONENT in center agent; do
    OUT="dist/$OS-$ARCH/remote-connect-mcp-$COMPONENT$SUFFIX"
    mkdir -p "$(dirname "$OUT")"
    CGO_ENABLED=0 GOOS="$OS" GOARCH="$ARCH" go build -mod=mod -trimpath \
      -ldflags "-s -w -X main.version=$VERSION" -o "$OUT" "./cmd/remote-connect-mcp-$COMPONENT"
    echo "Built $OUT"
  done
done
