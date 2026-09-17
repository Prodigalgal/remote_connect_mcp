#!/usr/bin/env bash
set -euo pipefail

# Keep a failing native smoke diagnostic actionable.  The script is executed
# under `bash -e` in GitHub Actions, so an early command failure used to leave
# only an exit code in the job log (especially when a newly-started native
# Center was still binding its port).  Do not print credentials or response
# bodies here; only the command and the bounded Center stderr are emitted.
on_error() {
  local status=$?
  echo "Java Center smoke failed (exit $status, line ${BASH_LINENO[0]}): ${BASH_COMMAND}" >&2
  if [[ -n "${tmp:-}" && -f "$tmp/err" ]]; then
    echo "--- Center stderr (first 120 lines) ---" >&2
    sed -n '1,120p' "$tmp/err" >&2 || true
  fi
  exit "$status"
}
trap on_error ERR

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
port="${RCM_SMOKE_PORT:-18180}"
center_binary="${1:-${RCM_SMOKE_CENTER_BINARY:-}}"
jar="$root/java/center/build/libs/center-0.1.0-SNAPSHOT.jar"
if [[ -n "$center_binary" ]]; then
  [[ -f "$center_binary" ]] || { echo "Center native binary not found: $center_binary" >&2; exit 1; }
else
  [[ -f "$jar" ]] || { echo "Center bootJar not found: $jar; this smoke script consumes a prebuilt GitHub Actions artifact; pass the downloaded Native Image as the first argument." >&2; exit 1; }
fi

tmp="$(mktemp -d)"
pid=""
cleanup() {
  if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null || true
    for _ in {1..20}; do kill -0 "$pid" 2>/dev/null || break; sleep 0.25; done
    kill -9 "$pid" 2>/dev/null || true
  fi
  rm -rf "$tmp"
}
finish() {
  local status=$?
  # Preserve the script status explicitly.  This also prevents a command in
  # the cleanup path from turning a successful smoke into a false failure.
  trap - EXIT ERR
  set +e
  cleanup
  exit "$status"
}
trap finish EXIT

if [[ -n "$center_binary" ]]; then
  RCM_CENTER_PERSISTENCE_MODE=memory \
  RCM_CENTER_VERSION=smoke \
  REMOTE_CONNECT_MCP_CENTER_MCP_TOKEN=smoke-mcp-token \
  REMOTE_CONNECT_MCP_CENTER_ADMIN_TOKEN=smoke-admin-token \
  REMOTE_CONNECT_MCP_CENTER_ENROLLMENT_TOKEN=smoke-enrollment-token \
  "$center_binary" "--server.port=$port" >"$tmp/out" 2>"$tmp/err" &
else
  RCM_CENTER_PERSISTENCE_MODE=memory \
  RCM_CENTER_VERSION=smoke \
  REMOTE_CONNECT_MCP_CENTER_MCP_TOKEN=smoke-mcp-token \
  REMOTE_CONNECT_MCP_CENTER_ADMIN_TOKEN=smoke-admin-token \
  REMOTE_CONNECT_MCP_CENTER_ENROLLMENT_TOKEN=smoke-enrollment-token \
  java -jar "$jar" "--server.port=$port" >"$tmp/out" 2>"$tmp/err" &
fi
pid=$!

healthy="false"
for _ in {1..120}; do
  if curl --silent --fail --max-time 1 "http://127.0.0.1:$port/api/v1/healthz" >/dev/null 2>&1; then
    healthy="true"
    break
  fi
  if ! kill -0 "$pid" 2>/dev/null; then
    echo "Java Center exited before health check:" >&2
    sed -n '1,120p' "$tmp/err" >&2
    exit 1
  fi
  sleep 0.25
done
[[ "$healthy" == "true" ]] || { echo "Java Center health check timed out" >&2; exit 1; }
curl --silent --fail --max-time 5 "http://127.0.0.1:$port/api/v1/healthz" >/dev/null 2>&1

rpc() {
  local id="$1" method="$2" session="${3:-}"
  local headers="$tmp/rpc-${id}.headers" body="$tmp/rpc-${id}.body"
  local session_header=()
  if [[ -n "$session" ]]; then session_header=(-H "Mcp-Session-Id: $session"); fi
  local payload
  if [[ "$method" == "initialize" ]]; then
    payload="{\"jsonrpc\":\"2.0\",\"id\":$id,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"rcm-smoke\",\"version\":\"1\"}}}"
  else
    payload="{\"jsonrpc\":\"2.0\",\"id\":$id,\"method\":\"$method\",\"params\":{}}"
  fi
  curl --silent --show-error --fail --max-time 5 \
    -H 'Authorization: Bearer smoke-mcp-token' \
    -H 'Accept: application/json, text/event-stream' \
    -H 'Content-Type: application/json' \
    "${session_header[@]}" \
    -D "$headers" -o "$body" \
    -d "$payload" \
    "http://127.0.0.1:$port/mcp"
  cat "$body"
}
init="$(rpc 1 initialize)"
grep -q '"result"' <<<"$init"
session="$(awk 'tolower($1) == "mcp-session-id:" {print $2; exit}' "$tmp/rpc-1.headers" | tr -d '\r')"
tools="$(rpc 2 tools/list "$session")"
grep -q 'machines_list' <<<"$tools"
grep -q 'command_start' <<<"$tools"
metrics="$(curl --silent --show-error --fail --max-time 5 \
  -H 'Authorization: Bearer smoke-admin-token' \
  -H 'Accept: text/plain' \
  "http://127.0.0.1:$port/metrics")"
grep -q 'remote_connect_mcp_machines_total' <<<"$metrics"
if grep -q 'smoke-mcp-token' <<<"$metrics"; then
  echo 'metrics response contains a secret' >&2
  exit 1
fi
bad_metrics_status="$(curl --silent --show-error --max-time 5 -o /dev/null -w '%{http_code}' \
  -H 'Authorization: Bearer wrong-admin-token' \
  "http://127.0.0.1:$port/metrics")"
[[ "$bad_metrics_status" == "401" ]] || { echo "metrics accepted invalid Admin Token: HTTP $bad_metrics_status" >&2; exit 1; }
implementation="jvm"
if [[ -n "$center_binary" ]]; then implementation="native"; fi
printf '{"health":"ok","initialize":"ok","tools":"ok","metrics":"ok","implementation":"%s","mcp":"http://127.0.0.1:%s/mcp"}\n' "$implementation" "$port"
