#!/usr/bin/env sh
set -eu

if [ "${GITHUB_ACTIONS:-}" != "true" ]; then
  echo "Local compilation is disabled. Use the GitHub Actions Java/React and release workflows." >&2
  exit 2
fi

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"

"$ROOT/scripts/build-java.sh"

if ! command -v go >/dev/null 2>&1; then
  echo "Go compatibility baseline skipped because Go is not installed."
  exit 0
fi

echo "== Go compatibility baseline =="

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
