#!/usr/bin/env bash
set -euo pipefail

center_url=''
enrollment_token=''
agent_name=''
host_id=''
version=''
release_tag=''
mode='command'
desktop=false
browser=false
desktop_user=''
browser_engine='playwright'
browser_name='chromium'
browser_headless='1'
playwright_version='1.63.0'
install_root='/opt/remote-connect-mcp-agent'
state_dir='/var/lib/remote-connect-mcp-agent'
re_enroll=false

usage() {
  cat >&2 <<'EOF'
Usage: first-install-java-agent.sh --center-url URL --enrollment-token TOKEN --agent-name NAME --version vX [options]
  --mode command|full       full enables Desktop and Browser
  --desktop                  install the Desktop companion
  --browser                  install the Browser companion and Playwright runtime
  --desktop-user USER        graphical Linux user for Desktop
  --browser-engine ENGINE    playwright|patchright|comoufox
  --browser-name NAME        chromium|firefox|webkit
  --browser-headless 0|1
  --re-enroll                consume a new token even when identity exists
EOF
}
while [[ $# -gt 0 ]]; do
  case "$1" in
    --center-url) center_url="${2:?missing URL}"; shift 2 ;;
    --enrollment-token) enrollment_token="${2:?missing token}"; shift 2 ;;
    --agent-name) agent_name="${2:?missing name}"; shift 2 ;;
    --host-id) host_id="${2:?missing host id}"; shift 2 ;;
    --version) version="${2:?missing version}"; shift 2 ;;
    --release-tag) release_tag="${2:?missing release tag}"; shift 2 ;;
    --mode) mode="${2:?missing mode}"; shift 2 ;;
    --desktop) desktop=true; shift ;;
    --browser) browser=true; shift ;;
    --desktop-user) desktop_user="${2:?missing desktop user}"; shift 2 ;;
    --browser-engine) browser_engine="${2:?missing browser engine}"; shift 2 ;;
    --browser-name) browser_name="${2:?missing browser name}"; shift 2 ;;
    --browser-headless) browser_headless="${2:?missing browser headless}"; shift 2 ;;
    --playwright-version) playwright_version="${2:?missing Playwright version}"; shift 2 ;;
    --install-root) install_root="${2:?missing install root}"; shift 2 ;;
    --state-dir) state_dir="${2:?missing state dir}"; shift 2 ;;
    --re-enroll) re_enroll=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown option: $1" >&2; usage; exit 2 ;;
  esac
done
if [[ "$mode" == full ]]; then desktop=true; browser=true; fi
[[ -n "$center_url" && "$center_url" == https://* ]] || { echo '--center-url must use HTTPS' >&2; exit 2; }
[[ -n "$enrollment_token" && -n "$agent_name" && "$agent_name" =~ ^[A-Za-z_][A-Za-z0-9_.-]{0,63}$ ]] || { echo 'enrollment token and a valid --agent-name are required' >&2; exit 2; }
[[ -n "$version" && "$version" =~ ^v[0-9A-Za-z][0-9A-Za-z.-]*$ ]] || { echo '--version must look like v0.1.29' >&2; exit 2; }
[[ -z "$release_tag" || "$release_tag" =~ ^[A-Za-z0-9_.-]{1,128}$ ]] || { echo '--release-tag contains unsupported characters' >&2; exit 2; }
[[ "$mode" == command || "$mode" == full ]] || { echo '--mode must be command or full' >&2; exit 2; }
[[ "$browser_engine" == playwright || "$browser_engine" == patchright || "$browser_engine" == comoufox ]] || { echo 'invalid --browser-engine' >&2; exit 2; }
[[ "$browser_name" == chromium || "$browser_name" == firefox || "$browser_name" == webkit ]] || { echo 'invalid --browser-name' >&2; exit 2; }
[[ "$browser_headless" == 0 || "$browser_headless" == 1 ]] || { echo '--browser-headless must be 0 or 1' >&2; exit 2; }
[[ -n "$host_id" ]] || host_id="$agent_name"
if [[ "$(id -u)" -ne 0 ]]; then
  exec sudo -E bash "$0" "$@"
fi
if $desktop; then
  if [[ -z "$desktop_user" ]]; then desktop_user="${SUDO_USER:-}"; fi
  [[ -n "$desktop_user" ]] || { echo 'Desktop mode requires --desktop-user or sudo from a graphical user' >&2; exit 2; }
  id "$desktop_user" >/dev/null 2>&1 || { echo "desktop user does not exist: $desktop_user" >&2; exit 2; }
fi

case "$(uname -m)" in
  x86_64|amd64) arch=amd64 ;;
  aarch64|arm64) arch=arm64 ;;
  *) echo "unsupported Linux architecture: $(uname -m)" >&2; exit 2 ;;
esac
[[ -n "$release_tag" ]] || release_tag="java-$version"
stage="$(mktemp -d -t rcm-first-install.XXXXXX)"
runtime="$state_dir/browser-runtime"
playwright_browsers_path="$state_dir/playwright-browsers"
trap 'rm -rf -- "$stage"; unset REMOTE_CONNECT_MCP_AGENT_ENROLLMENT_TOKEN' EXIT
mkdir -p "$state_dir"

command -v curl >/dev/null 2>&1 || { echo 'curl is required' >&2; exit 1; }
command -v sha256sum >/dev/null 2>&1 || { echo 'sha256sum is required' >&2; exit 1; }
command -v unzip >/dev/null 2>&1 || { echo 'unzip is required (install unzip)' >&2; exit 1; }
release_base="https://github.com/Prodigalgal/remote_connect_mcp/releases/download/$release_tag"
raw_base="https://raw.githubusercontent.com/Prodigalgal/remote_connect_mcp/$release_tag"
download_verified() {
  local asset="$1" destination="$stage/$1" sidecar="$stage/$1.sha256" expected listed actual
  curl --fail --location --silent --show-error --retry 3 --connect-timeout 15 --max-time 600 "$release_base/$asset" -o "$destination"
  curl --fail --location --silent --show-error --retry 3 --connect-timeout 15 --max-time 60 "$release_base/$asset.sha256" -o "$sidecar"
  read -r expected listed _ < "$sidecar" || { echo "invalid checksum sidecar for $asset" >&2; exit 1; }
  listed="${listed#\*}"
  [[ "$expected" =~ ^[[:xdigit:]]{64}$ && "$listed" == "$asset" ]] || { echo "invalid checksum sidecar for $asset" >&2; exit 1; }
  actual="$(sha256sum "$destination" | awk '{print tolower($1)}')"
  [[ "$actual" == "${expected,,}" ]] || { echo "checksum mismatch for $asset" >&2; exit 1; }
  printf '%s\n' "$destination"
}

agent_zip="$(download_verified "remote-connect-mcp-agent-$version-linux-$arch.zip")"
desktop_zip=''
browser_zip=''
if $desktop; then desktop_zip="$(download_verified "remote-connect-mcp-desktop-$version-linux-$arch.zip")"; fi
if $browser; then browser_zip="$(download_verified "remote-connect-mcp-browser-$version-linux-$arch.zip")"; fi
installer="$stage/install-java-agent.sh"
service_file="$stage/remote-connect-mcp-agent.service"
curl --fail --location --silent --show-error --retry 3 --connect-timeout 15 --max-time 60 "$raw_base/scripts/install-java-agent.sh" -o "$installer"
curl --fail --location --silent --show-error --retry 3 --connect-timeout 15 --max-time 60 "$raw_base/deploy/systemd/remote-connect-mcp-agent.service" -o "$service_file"
chmod 0700 "$installer"

adapter=''
if $browser; then
  node_path="$(command -v node || true)"
  npm_path="$(command -v npm || true)"
  [[ -x "$node_path" && -x "$npm_path" ]] || { echo 'Browser mode requires node and npm' >&2; exit 1; }
  worker="$runtime/browser-worker.mjs"
  mkdir -p "$runtime" "$playwright_browsers_path"
  curl --fail --location --silent --show-error --retry 3 --connect-timeout 15 --max-time 60 "$raw_base/scripts/browser-worker.mjs" -o "$worker"
  [[ -f "$runtime/package.json" ]] || printf '{"name":"rcm-browser-runtime","private":true}\n' > "$runtime/package.json"
  export PLAYWRIGHT_BROWSERS_PATH="$playwright_browsers_path"
  "$npm_path" install --prefix "$runtime" --no-save --ignore-scripts "playwright@$playwright_version"
  "$node_path" "$runtime/node_modules/playwright/cli.js" install "$browser_name"
  adapter="\"$node_path\" \"$worker\""
fi

capabilities='command,durable_tasks,file_transfer'
if $desktop; then capabilities+=',desktop'; fi
if $browser; then capabilities+=',browser'; fi
export REMOTE_CONNECT_MCP_AGENT_CENTER_URL="$center_url"
export REMOTE_CONNECT_MCP_AGENT_ENROLLMENT_TOKEN="$enrollment_token"
export REMOTE_CONNECT_MCP_AGENT_NAME="$agent_name"
export REMOTE_CONNECT_MCP_AGENT_HOST_ID="$host_id"
export REMOTE_CONNECT_MCP_AGENT_BINARY="$agent_zip"
export REMOTE_CONNECT_MCP_AGENT_SERVICE_FILE="$service_file"
export REMOTE_CONNECT_MCP_AGENT_CAPABILITIES="$capabilities"
export REMOTE_CONNECT_MCP_AGENT_VERSION="$version"
export REMOTE_CONNECT_MCP_AGENT_DEFAULT_CWD='/'
export REMOTE_CONNECT_MCP_AGENT_SCOPE_MODE='unrestricted'
export REMOTE_CONNECT_MCP_AGENT_STATE_DIR="$state_dir"
export REMOTE_CONNECT_MCP_AGENT_INSTALL_ROOT="$install_root"
export REMOTE_CONNECT_MCP_AGENT_DESKTOP_ENABLED="$desktop"
export REMOTE_CONNECT_MCP_AGENT_DESKTOP_USER="$desktop_user"
export REMOTE_CONNECT_MCP_AGENT_BROWSER_ADAPTER="$adapter"
export REMOTE_CONNECT_MCP_AGENT_BROWSER_PROFILE_DIR="$state_dir/browser-profile"
export REMOTE_CONNECT_MCP_AGENT_BROWSER_ENGINE="$browser_engine"
export REMOTE_CONNECT_MCP_AGENT_BROWSER="$browser_name"
export REMOTE_CONNECT_MCP_AGENT_BROWSER_HEADLESS="$browser_headless"
export REMOTE_CONNECT_MCP_AGENT_PLAYWRIGHT_BROWSERS_PATH="$playwright_browsers_path"
export REMOTE_CONNECT_MCP_AGENT_REENROLL="$re_enroll"
if $desktop; then export REMOTE_CONNECT_MCP_AGENT_DESKTOP_BINARY="$desktop_zip"; fi
if $browser; then export REMOTE_CONNECT_MCP_AGENT_BROWSER_BINARY="$browser_zip"; fi
bash "$installer"
printf '{"status":"installed","agent":"%s","version":"%s","capabilities":"%s","desktop":%s,"browser":%s}\n' "$agent_name" "$version" "$capabilities" "$desktop" "$browser"
