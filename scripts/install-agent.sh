#!/bin/sh
set -eu

if [ "$(id -u)" -ne 0 ]; then
  echo "run as root" >&2
  exit 1
fi

: "${REMOTE_CONNECT_MCP_AGENT_CENTER_URL:?required}"
: "${REMOTE_CONNECT_MCP_AGENT_NAME:?required}"

BINARY_SOURCE=${REMOTE_CONNECT_MCP_AGENT_BINARY:-./remote-connect-mcp-agent}
DESKTOP_SOURCE=${REMOTE_CONNECT_MCP_AGENT_DESKTOP_BINARY:-}
BROWSER_SOURCE=${REMOTE_CONNECT_MCP_AGENT_BROWSER_BINARY:-}
if [ ! -f "$BINARY_SOURCE" ]; then
  echo "agent binary or ZIP is missing: $BINARY_SOURCE" >&2
  exit 1
fi

MAX_CONCURRENCY=${REMOTE_CONNECT_MCP_AGENT_MAX_CONCURRENCY:-1}
MAX_BROWSER_WORKERS=${REMOTE_CONNECT_MCP_AGENT_MAX_BROWSER_WORKERS:-1}
DESKTOP_MAX_LAUNCHED_PROCESSES=${REMOTE_CONNECT_MCP_AGENT_DESKTOP_MAX_LAUNCHED_PROCESSES:-16}
MAX_OUTPUT_BYTES=${REMOTE_CONNECT_MCP_AGENT_MAX_OUTPUT_BYTES:-67108864}
MAX_AGGREGATE_OUTPUT_BYTES=${REMOTE_CONNECT_MCP_AGENT_MAX_AGGREGATE_OUTPUT_BYTES:-}
case "$MAX_CONCURRENCY" in
  ''|*[!0-9]*) echo "REMOTE_CONNECT_MCP_AGENT_MAX_CONCURRENCY must be an integer" >&2; exit 1 ;;
esac
if [ "$MAX_CONCURRENCY" -lt 1 ] || [ "$MAX_CONCURRENCY" -gt 32 ]; then
  echo "REMOTE_CONNECT_MCP_AGENT_MAX_CONCURRENCY must be between 1 and 32" >&2
  exit 1
fi
case "$MAX_OUTPUT_BYTES" in
  ''|*[!0-9]*) echo "REMOTE_CONNECT_MCP_AGENT_MAX_OUTPUT_BYTES must be an integer" >&2; exit 1 ;;
esac
if [ "$MAX_OUTPUT_BYTES" -lt 1048576 ] || [ "$MAX_OUTPUT_BYTES" -gt 1073741824 ]; then
  echo "REMOTE_CONNECT_MCP_AGENT_MAX_OUTPUT_BYTES must be between 1 MiB and 1 GiB" >&2
  exit 1
fi
if [ -n "$MAX_AGGREGATE_OUTPUT_BYTES" ]; then
  case "$MAX_AGGREGATE_OUTPUT_BYTES" in
    ''|*[!0-9]*) echo "REMOTE_CONNECT_MCP_AGENT_MAX_AGGREGATE_OUTPUT_BYTES must be an integer" >&2; exit 1 ;;
  esac
fi
if [ -z "$MAX_AGGREGATE_OUTPUT_BYTES" ]; then
  if [ "$MAX_OUTPUT_BYTES" -gt 268435456 ]; then
    MAX_AGGREGATE_OUTPUT_BYTES=$MAX_OUTPUT_BYTES
  else
    MAX_AGGREGATE_OUTPUT_BYTES=$((MAX_OUTPUT_BYTES * MAX_CONCURRENCY))
    if [ "$MAX_AGGREGATE_OUTPUT_BYTES" -gt 268435456 ]; then
      MAX_AGGREGATE_OUTPUT_BYTES=268435456
    fi
  fi
fi

STATE_DIR=${REMOTE_CONNECT_MCP_AGENT_STATE_DIR:-/var/lib/remote-connect-mcp-agent}
if [ ! -f "$STATE_DIR/identity.json" ]; then
  : "${REMOTE_CONNECT_MCP_AGENT_ENROLLMENT_TOKEN:?required for first registration}"
fi
if [ "$MAX_AGGREGATE_OUTPUT_BYTES" -lt "$MAX_OUTPUT_BYTES" ]; then
  echo "REMOTE_CONNECT_MCP_AGENT_MAX_AGGREGATE_OUTPUT_BYTES must be at least MAX_OUTPUT_BYTES" >&2
  exit 1
fi
case "$MAX_BROWSER_WORKERS" in
  ''|*[!0-9]*) echo "REMOTE_CONNECT_MCP_AGENT_MAX_BROWSER_WORKERS must be an integer" >&2; exit 1 ;;
esac
if [ "$MAX_BROWSER_WORKERS" -lt 1 ] || [ "$MAX_BROWSER_WORKERS" -gt 8 ] || [ "$MAX_BROWSER_WORKERS" -gt "$MAX_CONCURRENCY" ]; then
  echo "REMOTE_CONNECT_MCP_AGENT_MAX_BROWSER_WORKERS must be between 1 and MAX_CONCURRENCY (maximum 8)" >&2
  exit 1
fi
case "$DESKTOP_MAX_LAUNCHED_PROCESSES" in
  ''|*[!0-9]*) echo "REMOTE_CONNECT_MCP_AGENT_DESKTOP_MAX_LAUNCHED_PROCESSES must be an integer" >&2; exit 1 ;;
esac
if [ "$DESKTOP_MAX_LAUNCHED_PROCESSES" -lt 1 ] || [ "$DESKTOP_MAX_LAUNCHED_PROCESSES" -gt 64 ]; then
  echo "REMOTE_CONNECT_MCP_AGENT_DESKTOP_MAX_LAUNCHED_PROCESSES must be between 1 and 64" >&2
  exit 1
fi

verify_archive_checksum() {
  checksum_artifact=$1
  checksum_sidecar="$checksum_artifact.sha256"
  [ -f "$checksum_sidecar" ] || return 0
  read -r checksum_expected checksum_listed _ < "$checksum_sidecar" || { echo "invalid SHA-256 sidecar: $checksum_sidecar" >&2; exit 1; }
  checksum_listed=${checksum_listed#\*}
  case "$checksum_expected" in
    ''|*[!0-9A-Fa-f]*) echo "invalid SHA-256 sidecar: $checksum_sidecar" >&2; exit 1 ;;
  esac
  [ "${#checksum_expected}" -eq 64 ] || { echo "invalid SHA-256 sidecar: $checksum_sidecar" >&2; exit 1; }
  checksum_actual=$(sha256sum "$checksum_artifact" | awk '{print tolower($1)}')
  [ "$checksum_listed" = "$(basename "$checksum_artifact")" ] && [ "$checksum_actual" = "$(printf '%s' "$checksum_expected" | tr '[:upper:]' '[:lower:]')" ] || {
    echo "SHA-256 mismatch or invalid sidecar: $checksum_sidecar" >&2; exit 1;
  }
}

install_companion() {
  source_path=$1
  expected=$2
  destination_dir=$3
  [ -n "$source_path" ] || return 0
  if [ ! -f "$source_path" ]; then
    echo "companion bundle is missing: $source_path" >&2
    exit 1
  fi
  staging=''
  cleanup_companion() {
    if [ -n "$staging" ] && [ -d "$staging" ]; then rm -rf -- "$staging"; fi
  }
  trap cleanup_companion EXIT INT TERM
  source_root=''
  if printf '%s' "$source_path" | grep -qi '\.zip$'; then
    command -v unzip >/dev/null 2>&1 || { echo 'unzip is required for companion ZIPs' >&2; exit 1; }
    command -v sha256sum >/dev/null 2>&1 || { echo 'sha256sum is required for companion ZIPs' >&2; exit 1; }
    verify_archive_checksum "$source_path"
    staging=$(mktemp -d)
    unzip -q "$source_path" -d "$staging"
    source_file=$(find "$staging" -type f -name "$expected" -print -quit)
    [ -n "$source_file" ] || { echo "companion ZIP does not contain $expected" >&2; exit 1; }
    source_root=$(dirname "$source_file")
    [ "$source_root" = "$staging" ] || { echo 'companion ZIP must be flat' >&2; exit 1; }
    nested=$(find "$staging" -mindepth 2 -type f -print -quit)
    [ -z "$nested" ] || { echo 'companion ZIP contains nested files' >&2; exit 1; }
    for file in "$staging"/*; do
      [ -f "$file" ] || continue
      case "$(basename "$file")" in
        "$expected"|*.so|*.so.*) ;;
        *) echo "unexpected file in companion ZIP: $(basename "$file")" >&2; exit 1 ;;
      esac
    done
  else
    raw_name=$(basename "$source_path")
    if [ "$raw_name" != "$expected" ] && [ "$expected" != 'rcm-agent' -o "$raw_name" != 'remote-connect-mcp-agent' ]; then
      echo "companion binary must be named $expected" >&2
      exit 1
    fi
    source_root=$(dirname "$source_path")
    source_file=$source_path
  fi
  install -d -m 0755 "$destination_dir"
  find "$destination_dir" -maxdepth 1 -type f \( -name '*.so' -o -name '*.so.*' -o -name "$expected" \) -delete
  install -m 0755 "$source_file" "$destination_dir/$expected"
  find "$source_root" -maxdepth 1 -type f \( -name '*.so' -o -name '*.so.*' \) -exec install -m 0644 {} "$destination_dir/" \;
  cleanup_companion
  trap - EXIT INT TERM
}

install -d -m 0755 /opt/remote-connect-mcp-agent
install_companion "$BINARY_SOURCE" rcm-agent /opt/remote-connect-mcp-agent
install_companion "$DESKTOP_SOURCE" rcm-desktop-companion /opt/remote-connect-mcp-agent/desktop
install_companion "$BROWSER_SOURCE" rcm-browser-agent /opt/remote-connect-mcp-agent/browser
install -d -m 0700 /etc/remote-connect-mcp-agent "$STATE_DIR"

if [ ! -f "$STATE_DIR/identity.json" ]; then
  REMOTE_CONNECT_MCP_AGENT_CENTER_URL="$REMOTE_CONNECT_MCP_AGENT_CENTER_URL" \
  REMOTE_CONNECT_MCP_AGENT_ENROLLMENT_TOKEN="$REMOTE_CONNECT_MCP_AGENT_ENROLLMENT_TOKEN" \
  REMOTE_CONNECT_MCP_AGENT_NAME="$REMOTE_CONNECT_MCP_AGENT_NAME" \
  REMOTE_CONNECT_MCP_AGENT_HOST_ID="${REMOTE_CONNECT_MCP_AGENT_HOST_ID:-$REMOTE_CONNECT_MCP_AGENT_NAME}" \
  REMOTE_CONNECT_MCP_AGENT_DEFAULT_CWD="${REMOTE_CONNECT_MCP_AGENT_DEFAULT_CWD:-/}" \
  REMOTE_CONNECT_MCP_AGENT_SCOPE_MODE="${REMOTE_CONNECT_MCP_AGENT_SCOPE_MODE:-unrestricted}" \
  REMOTE_CONNECT_MCP_AGENT_WORKSPACE_ROOT="${REMOTE_CONNECT_MCP_AGENT_WORKSPACE_ROOT:-}" \
  REMOTE_CONNECT_MCP_AGENT_CAPABILITIES="${REMOTE_CONNECT_MCP_AGENT_CAPABILITIES:-command,durable_tasks}" \
  REMOTE_CONNECT_MCP_AGENT_VERSION="${REMOTE_CONNECT_MCP_AGENT_VERSION:-dev}" \
  REMOTE_CONNECT_MCP_AGENT_BROWSER_ADAPTER="${REMOTE_CONNECT_MCP_AGENT_BROWSER_ADAPTER:-}" \
  REMOTE_CONNECT_MCP_AGENT_DESKTOP_ENABLED="${REMOTE_CONNECT_MCP_AGENT_DESKTOP_ENABLED:-false}" \
  REMOTE_CONNECT_MCP_AGENT_STATE_DIR="$STATE_DIR" \
  REMOTE_CONNECT_MCP_AGENT_MAX_CONCURRENCY="$MAX_CONCURRENCY" \
  REMOTE_CONNECT_MCP_AGENT_MAX_BROWSER_WORKERS="$MAX_BROWSER_WORKERS" \
  REMOTE_CONNECT_MCP_AGENT_DESKTOP_MAX_LAUNCHED_PROCESSES="$DESKTOP_MAX_LAUNCHED_PROCESSES" \
  REMOTE_CONNECT_MCP_AGENT_MAX_OUTPUT_BYTES="$MAX_OUTPUT_BYTES" \
  REMOTE_CONNECT_MCP_AGENT_MAX_AGGREGATE_OUTPUT_BYTES="$MAX_AGGREGATE_OUTPUT_BYTES" \
  /opt/remote-connect-mcp-agent/rcm-agent --register-once
  [ -f "$STATE_DIR/identity.json" ] || { echo 'Agent registration did not create identity.json' >&2; exit 1; }
fi

umask 077
{
  printf 'REMOTE_CONNECT_MCP_AGENT_CENTER_URL=%s\n' "$REMOTE_CONNECT_MCP_AGENT_CENTER_URL"
  printf 'REMOTE_CONNECT_MCP_AGENT_NAME=%s\n' "$REMOTE_CONNECT_MCP_AGENT_NAME"
  printf 'REMOTE_CONNECT_MCP_AGENT_HOST_ID=%s\n' "${REMOTE_CONNECT_MCP_AGENT_HOST_ID:-$REMOTE_CONNECT_MCP_AGENT_NAME}"
  printf 'REMOTE_CONNECT_MCP_AGENT_DEFAULT_CWD=%s\n' "${REMOTE_CONNECT_MCP_AGENT_DEFAULT_CWD:-/}"
  printf 'REMOTE_CONNECT_MCP_AGENT_SCOPE_MODE=%s\n' "${REMOTE_CONNECT_MCP_AGENT_SCOPE_MODE:-unrestricted}"
  printf 'REMOTE_CONNECT_MCP_AGENT_WORKSPACE_ROOT=%s\n' "${REMOTE_CONNECT_MCP_AGENT_WORKSPACE_ROOT:-}"
  printf 'REMOTE_CONNECT_MCP_AGENT_CAPABILITIES=%s\n' "${REMOTE_CONNECT_MCP_AGENT_CAPABILITIES:-command,durable_tasks}"
  printf 'REMOTE_CONNECT_MCP_AGENT_VERSION=%s\n' "${REMOTE_CONNECT_MCP_AGENT_VERSION:-dev}"
  printf 'REMOTE_CONNECT_MCP_AGENT_BROWSER_ADAPTER=%s\n' "${REMOTE_CONNECT_MCP_AGENT_BROWSER_ADAPTER:-}"
  printf 'REMOTE_CONNECT_MCP_AGENT_DESKTOP_ENABLED=%s\n' "${REMOTE_CONNECT_MCP_AGENT_DESKTOP_ENABLED:-false}"
  printf 'REMOTE_CONNECT_MCP_AGENT_STATE_DIR=%s\n' "$STATE_DIR"
  printf 'REMOTE_CONNECT_MCP_AGENT_MAX_CONCURRENCY=%s\n' "$MAX_CONCURRENCY"
  printf 'REMOTE_CONNECT_MCP_AGENT_MAX_BROWSER_WORKERS=%s\n' "$MAX_BROWSER_WORKERS"
  printf 'REMOTE_CONNECT_MCP_AGENT_DESKTOP_MAX_LAUNCHED_PROCESSES=%s\n' "$DESKTOP_MAX_LAUNCHED_PROCESSES"
  printf 'REMOTE_CONNECT_MCP_AGENT_MAX_OUTPUT_BYTES=%s\n' "$MAX_OUTPUT_BYTES"
  printf 'REMOTE_CONNECT_MCP_AGENT_MAX_AGGREGATE_OUTPUT_BYTES=%s\n' "$MAX_AGGREGATE_OUTPUT_BYTES"
  printf 'REMOTE_CONNECT_MCP_AGENT_BINARY_PATH=%s\n' '/opt/remote-connect-mcp-agent/rcm-agent'
  printf 'REMOTE_CONNECT_MCP_AGENT_SERVICE_NAME=%s\n' 'remote-connect-mcp-agent'
  if [ -n "$DESKTOP_SOURCE" ]; then printf 'REMOTE_CONNECT_MCP_AGENT_DESKTOP_BINARY=%s\n' '/opt/remote-connect-mcp-agent/desktop/rcm-desktop-companion'; fi
  if [ -n "$BROWSER_SOURCE" ]; then printf 'REMOTE_CONNECT_MCP_AGENT_BROWSER_BINARY=%s\n' '/opt/remote-connect-mcp-agent/browser/rcm-browser-agent'; fi
} > /etc/remote-connect-mcp-agent/agent.env

install -m 0644 "${REMOTE_CONNECT_MCP_AGENT_SERVICE_FILE:-./remote-connect-mcp-agent.service}" /etc/systemd/system/remote-connect-mcp-agent.service
systemctl daemon-reload
systemctl enable --now remote-connect-mcp-agent.service
systemctl is-active --quiet remote-connect-mcp-agent.service
echo "remote-connect-mcp-agent is active"
