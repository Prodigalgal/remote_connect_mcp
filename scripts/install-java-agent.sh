#!/usr/bin/env bash
set -euo pipefail

if [[ "$(id -u)" -ne 0 ]]; then echo "run as root" >&2; exit 1; fi
: "${REMOTE_CONNECT_MCP_AGENT_CENTER_URL:?required}"
: "${REMOTE_CONNECT_MCP_AGENT_NAME:?required}"

script_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
service_file="${REMOTE_CONNECT_MCP_AGENT_SERVICE_FILE:-$script_dir/remote-connect-mcp-agent.service}"
[[ -f "$service_file" ]] || { echo "systemd service template is missing: $service_file (set REMOTE_CONNECT_MCP_AGENT_SERVICE_FILE)" >&2; exit 1; }

binary_source="${REMOTE_CONNECT_MCP_AGENT_BINARY:-./rcm-agent}"
[[ -f "$binary_source" ]] || { echo "Java native Agent binary or flat ZIP is missing: $binary_source" >&2; exit 1; }

verify_archive_checksum() {
  local artifact="$1" sidecar="${1}.sha256" expected listed actual
  [[ -f "$sidecar" ]] || return 0
  read -r expected listed _ < "$sidecar" || { echo "invalid SHA-256 sidecar: $sidecar" >&2; exit 1; }
  listed="${listed#\*}"
  [[ "$expected" =~ ^[[:xdigit:]]{64}$ && "$listed" == "$(basename "$artifact")" ]] || {
    echo "invalid SHA-256 sidecar: $sidecar" >&2
    exit 1
  }
  actual="$(sha256sum "$artifact" | awk '{print tolower($1)}')"
  [[ "$actual" == "${expected,,}" ]] || { echo "SHA-256 mismatch: $artifact" >&2; exit 1; }
}
center_url="${REMOTE_CONNECT_MCP_AGENT_CENTER_URL%/}"
[[ "$center_url" == https://* ]] || { echo "REMOTE_CONNECT_MCP_AGENT_CENTER_URL must use HTTPS" >&2; exit 1; }
state_dir="${REMOTE_CONNECT_MCP_AGENT_STATE_DIR:-/var/lib/remote-connect-mcp-agent}"
install_root="${REMOTE_CONNECT_MCP_AGENT_INSTALL_ROOT:-/opt/remote-connect-mcp-agent}"
re_enroll="${REMOTE_CONNECT_MCP_AGENT_REENROLL:-false}"
max_concurrency="${REMOTE_CONNECT_MCP_AGENT_MAX_CONCURRENCY:-1}"
max_output="${REMOTE_CONNECT_MCP_AGENT_MAX_OUTPUT_BYTES:-67108864}"
max_aggregate="${REMOTE_CONNECT_MCP_AGENT_MAX_AGGREGATE_OUTPUT_BYTES:-}"
if [[ -z "$max_aggregate" ]]; then
  max_aggregate=$((max_output * max_concurrency))
  (( max_aggregate > 268435456 )) && max_aggregate=268435456
  (( max_aggregate < max_output )) && max_aggregate=$max_output
fi
(( max_aggregate >= max_output && max_aggregate <= 4294967296 )) || { echo "aggregate output limit is invalid" >&2; exit 1; }

install -d -m 0755 "$install_root"
install -d -m 0700 "$state_dir"

# Native Image may emit shared libraries beside the ELF.  Accept the flat
# Agent ZIP produced by GitHub Actions as well as an already extracted binary;
# never copy arbitrary nested archive paths into the service directory.
staging=""
source_dir=""
source_binary=""
if [[ "${binary_source,,}" == *.zip ]]; then
  command -v unzip >/dev/null 2>&1 || { echo "unzip is required to install a Java Agent ZIP (apt install unzip)" >&2; exit 1; }
  command -v sha256sum >/dev/null 2>&1 || { echo "sha256sum is required to verify the Agent bundle" >&2; exit 1; }
  verify_archive_checksum "$binary_source"
  staging="$(mktemp -d -t rcm-agent-install.XXXXXX)"
  trap 'rm -rf "$staging"' EXIT
  unzip -q "$binary_source" -d "$staging"
  mapfile -t executables < <(find "$staging" -type f -name 'rcm-agent' -print)
  (( ${#executables[@]} == 1 )) || { echo "Agent ZIP must contain exactly one flat rcm-agent executable" >&2; exit 1; }
  source_dir="$(dirname "${executables[0]}")"
  [[ "$source_dir" == "$staging" ]] || { echo "Agent ZIP must be flat (no nested executable path)" >&2; exit 1; }
  source_binary="${executables[0]}"
else
  [[ -x "$binary_source" ]] || { echo "Java native Agent binary is missing or not executable: $binary_source" >&2; exit 1; }
  source_binary="$(readlink -f "$binary_source")"
  source_dir="$(dirname "$source_binary")"
fi

[[ -x "$source_binary" ]] || { echo "selected Java Agent executable is not executable" >&2; exit 1; }
declare -A bundle_files=()
bundle_files[rcm-agent]=1
while IFS= read -r -d '' sidecar; do
  name="$(basename "$sidecar")"
  bundle_files["$name"]=1
done < <(find "$source_dir" -mindepth 1 -maxdepth 1 -type f \( -name '*.so' -o -name '*.so.*' \) -print0)

for old in "$install_root"/*.so "$install_root"/*.so.*; do
  [[ -e "$old" ]] || continue
  [[ -n "${bundle_files[$(basename "$old")]+x}" ]] || rm -f -- "$old"
done
install -m 0755 "$source_binary" "$install_root/rcm-agent"
while IFS= read -r -d '' sidecar; do
  install -m 0755 "$sidecar" "$install_root/$(basename "$sidecar")"
done < <(find "$source_dir" -mindepth 1 -maxdepth 1 -type f \( -name '*.so' -o -name '*.so.*' \) -print0)
identity="$state_dir/identity.json"
if [[ "$re_enroll" == true || ! -f "$identity" ]]; then
  : "${REMOTE_CONNECT_MCP_AGENT_ENROLLMENT_TOKEN:?required for first registration or re-enroll}"
  tmp_log="$(mktemp)"
  chmod 600 "$tmp_log"
  set +e
  REMOTE_CONNECT_MCP_AGENT_CENTER_URL="$center_url" \
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
  REMOTE_CONNECT_MCP_AGENT_STATE_DIR="$state_dir" \
  REMOTE_CONNECT_MCP_AGENT_MAX_CONCURRENCY="$max_concurrency" \
  REMOTE_CONNECT_MCP_AGENT_MAX_OUTPUT_BYTES="$max_output" \
  REMOTE_CONNECT_MCP_AGENT_MAX_AGGREGATE_OUTPUT_BYTES="$max_aggregate" \
  "$install_root/rcm-agent" --register-once >"$tmp_log" 2>&1
  rc=$?
  set -e
  if (( rc != 0 )) || [[ ! -f "$identity" ]]; then
    echo "Java Agent registration failed:" >&2
    sed -n '1,80p' "$tmp_log" >&2
    rm -f "$tmp_log"
    exit 1
  fi
  rm -f "$tmp_log"
fi

umask 077
install -d -m 0700 /etc/remote-connect-mcp-agent
{
  printf 'REMOTE_CONNECT_MCP_AGENT_CENTER_URL=%s\n' "$center_url"
  printf 'REMOTE_CONNECT_MCP_AGENT_NAME=%s\n' "$REMOTE_CONNECT_MCP_AGENT_NAME"
  printf 'REMOTE_CONNECT_MCP_AGENT_HOST_ID=%s\n' "${REMOTE_CONNECT_MCP_AGENT_HOST_ID:-$REMOTE_CONNECT_MCP_AGENT_NAME}"
  printf 'REMOTE_CONNECT_MCP_AGENT_DEFAULT_CWD=%s\n' "${REMOTE_CONNECT_MCP_AGENT_DEFAULT_CWD:-/}"
  printf 'REMOTE_CONNECT_MCP_AGENT_SCOPE_MODE=%s\n' "${REMOTE_CONNECT_MCP_AGENT_SCOPE_MODE:-unrestricted}"
  printf 'REMOTE_CONNECT_MCP_AGENT_WORKSPACE_ROOT=%s\n' "${REMOTE_CONNECT_MCP_AGENT_WORKSPACE_ROOT:-}"
  printf 'REMOTE_CONNECT_MCP_AGENT_CAPABILITIES=%s\n' "${REMOTE_CONNECT_MCP_AGENT_CAPABILITIES:-command,durable_tasks}"
  printf 'REMOTE_CONNECT_MCP_AGENT_VERSION=%s\n' "${REMOTE_CONNECT_MCP_AGENT_VERSION:-dev}"
  printf 'REMOTE_CONNECT_MCP_AGENT_BROWSER_ADAPTER=%s\n' "${REMOTE_CONNECT_MCP_AGENT_BROWSER_ADAPTER:-}"
  printf 'REMOTE_CONNECT_MCP_AGENT_DESKTOP_ENABLED=%s\n' "${REMOTE_CONNECT_MCP_AGENT_DESKTOP_ENABLED:-false}"
  printf 'REMOTE_CONNECT_MCP_AGENT_STATE_DIR=%s\n' "$state_dir"
  printf 'REMOTE_CONNECT_MCP_AGENT_MAX_CONCURRENCY=%s\n' "$max_concurrency"
  printf 'REMOTE_CONNECT_MCP_AGENT_MAX_OUTPUT_BYTES=%s\n' "$max_output"
  printf 'REMOTE_CONNECT_MCP_AGENT_MAX_AGGREGATE_OUTPUT_BYTES=%s\n' "$max_aggregate"
  printf 'REMOTE_CONNECT_MCP_AGENT_BINARY_PATH=%s\n' "$install_root/rcm-agent"
  printf 'REMOTE_CONNECT_MCP_AGENT_SERVICE_NAME=remote-connect-mcp-agent\n'
} > /etc/remote-connect-mcp-agent/agent.env

install -m 0644 "$service_file" /etc/systemd/system/remote-connect-mcp-agent.service
systemctl daemon-reload
systemctl enable --now remote-connect-mcp-agent.service
systemctl is-active --quiet remote-connect-mcp-agent.service
echo "remote-connect-mcp Java Agent is active; Enrollment Token is not stored in agent.env"
