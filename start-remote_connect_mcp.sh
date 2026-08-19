#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$ROOT"

: "${REMOTE_CONNECT_MCP_ENV_FILE:=$ROOT/remote_connect_mcp.env}"
export REMOTE_CONNECT_MCP_ENV_FILE

case "$(uname -m)" in
  x86_64|amd64) ARCH=amd64 ;;
  aarch64|arm64) ARCH=arm64 ;;
  *) echo "Unsupported architecture: $(uname -m)" >&2; exit 1 ;;
esac

BIN="$ROOT/dist/linux-$ARCH/remote_connect_mcp"
if [ ! -x "$BIN" ]; then
  if ! command -v go >/dev/null 2>&1; then
    echo "No prebuilt binary or Go toolchain was found." >&2
    echo "Run scripts/build-all.sh on a build machine, then copy this folder." >&2
    exit 1
  fi
  echo "Prebuilt remote_connect_mcp was not found. Building it now..."
  mkdir -p "$(dirname "$BIN")"
  CGO_ENABLED=0 GOOS=linux GOARCH="$ARCH" go build -mod=mod -trimpath \
    -ldflags "-s -w -X main.version=0.1.0" -o "$BIN" ./cmd/remote_connect_mcp
fi

exec "$BIN"
