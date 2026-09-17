#!/usr/bin/env bash
set -Eeuo pipefail

# Native Center + Agent lifecycle smoke test for Linux CI/build hosts.
# The script intentionally uses only a short-lived in-memory Center and
# one-time enrollment token; it never reads production credentials.

on_error() {
  local status=$?
  echo "Native Agent smoke failed (exit $status, line ${BASH_LINENO[0]}): ${BASH_COMMAND}" >&2
  if [[ -n "${STATE_DIR:-}" ]]; then
    echo "--- Center log (first 240 lines) ---" >&2
    sed -n '1,240p' "${CENTER_LOG:-}" >&2 2>/dev/null || true
    echo "--- Agent registration log (first 160 lines) ---" >&2
    sed -n '1,160p' "${REGISTER_LOG:-}" >&2 2>/dev/null || true
    echo "--- Agent runtime log (first 240 lines) ---" >&2
    sed -n '1,240p' "${AGENT_LOG:-}" >&2 2>/dev/null || true
  fi
  exit "$status"
}
trap on_error ERR

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CENTER_BINARY="${1:-${RCM_SMOKE_CENTER_BINARY:-}}"
AGENT_BINARY="${2:-${RCM_SMOKE_AGENT_BINARY:-}}"
PORT="${3:-${RCM_SMOKE_PORT:-18183}}"
RESOURCE_REPORT="${RCM_SMOKE_RESOURCE_REPORT:-}"

if [[ -z "$CENTER_BINARY" || -z "$AGENT_BINARY" ]]; then
  echo "usage: $0 /path/to/rcm-center /path/to/rcm-agent [port]" >&2
  exit 2
fi

CENTER_BINARY="$(readlink -f "$CENTER_BINARY")"
AGENT_BINARY="$(readlink -f "$AGENT_BINARY")"
[[ -x "$CENTER_BINARY" ]] || { echo "Center binary is not executable: $CENTER_BINARY" >&2; exit 2; }
[[ -x "$AGENT_BINARY" ]] || { echo "Agent binary is not executable: $AGENT_BINARY" >&2; exit 2; }
command -v curl >/dev/null || { echo "curl is required" >&2; exit 2; }
command -v python3 >/dev/null || { echo "python3 is required" >&2; exit 2; }

BASE="http://127.0.0.1:${PORT}"
MCP_TOKEN="smoke-mcp-token"
ADMIN_TOKEN="smoke-admin-token"
STATE_DIR="$(mktemp -d "${TMPDIR:-/tmp}/rcm-java-agent-smoke.XXXXXX")"
CENTER_LOG="$STATE_DIR/center.log"
AGENT_LOG="$STATE_DIR/agent.log"
REGISTER_LOG="$STATE_DIR/register.log"
CENTER_PID=""
AGENT_PID=""
AGENT_PEAK_RSS_KIB=0
AGENT_RSS_SAMPLES=0

sample_agent_rss() {
  [[ -n "$AGENT_PID" ]] || return 0
  local rss
  rss="$(ps -o rss= -p "$AGENT_PID" 2>/dev/null | awk '{print $1}')"
  if [[ "$rss" =~ ^[0-9]+$ ]]; then
    AGENT_RSS_SAMPLES=$((AGENT_RSS_SAMPLES + 1))
    if (( rss > AGENT_PEAK_RSS_KIB )); then AGENT_PEAK_RSS_KIB=$rss; fi
  fi
}

write_resource_report() {
  [[ -n "$RESOURCE_REPORT" ]] || return 0
  mkdir -p "$(dirname "$RESOURCE_REPORT")"
  python3 - "$RESOURCE_REPORT" "$AGENT_PID" "$AGENT_PEAK_RSS_KIB" "$AGENT_RSS_SAMPLES" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
payload = {
    "agent_pid": int(sys.argv[2]) if sys.argv[2].isdigit() else None,
    "peak_rss_kib": int(sys.argv[3]),
    "peak_rss_mib": round(int(sys.argv[3]) / 1024, 1),
    "rss_samples": int(sys.argv[4]),
}
tmp = path.with_name(path.name + ".tmp")
tmp.write_text(json.dumps(payload, separators=(",", ":")) + "\n", encoding="utf-8")
tmp.replace(path)
PY
}

cleanup() {
  set +e
  if [[ -n "$AGENT_PID" ]] && kill -0 "$AGENT_PID" 2>/dev/null; then
    kill "$AGENT_PID" 2>/dev/null
    wait "$AGENT_PID" 2>/dev/null
  fi
  if [[ -n "$CENTER_PID" ]] && kill -0 "$CENTER_PID" 2>/dev/null; then
    kill "$CENTER_PID" 2>/dev/null
    wait "$CENTER_PID" 2>/dev/null
  fi
  sample_agent_rss || true
  write_resource_report || true
  rm -rf "$STATE_DIR"
}
finish() {
  local status=$?
  # Preserve the test result even when a child exits via SIGTERM during
  # cleanup. Clear traps first so cleanup cannot recurse or overwrite it.
  trap - EXIT INT TERM ERR
  set +e
  cleanup
  exit "$status"
}
trap finish EXIT INT TERM

json_field() {
  local json="$1"
  local field="$2"
  python3 - "$json" "$field" <<'PY'
import json
import sys

value = json.loads(sys.argv[1])
for part in sys.argv[2].split('.'):
    if isinstance(value, dict):
        value = value.get(part)
    else:
        value = None
        break
if isinstance(value, bool):
    print("true" if value else "false")
elif value is None:
    print("")
else:
    print(value)
PY
}

machine_online() {
  local json="$1"
  local machine_id="$2"
  python3 - "$json" "$machine_id" <<'PY'
import json
import sys

doc = json.loads(sys.argv[1])
machine_id = sys.argv[2]
print("true" if any(str(item.get("id")) == machine_id and item.get("online") is True
                    for item in doc.get("items", [])) else "false")
PY
}

fail_with_logs() {
  echo "native Agent smoke failed: $*" >&2
  echo "--- Center log ---" >&2
  sed -n '1,240p' "$CENTER_LOG" >&2 || true
  echo "--- Agent registration log ---" >&2
  sed -n '1,160p' "$REGISTER_LOG" >&2 || true
  echo "--- Agent runtime log ---" >&2
  sed -n '1,240p' "$AGENT_LOG" >&2 || true
  exit 1
}

export RCM_CENTER_PERSISTENCE_MODE=memory
export RCM_CENTER_VERSION=native-agent-smoke
export REMOTE_CONNECT_MCP_CENTER_MCP_TOKEN="$MCP_TOKEN"
export REMOTE_CONNECT_MCP_CENTER_ADMIN_TOKEN="$ADMIN_TOKEN"
"$CENTER_BINARY" "--server.port=$PORT" >"$CENTER_LOG" 2>&1 &
CENTER_PID=$!

healthy="false"
for _ in $(seq 1 120); do
  if curl --fail --silent --show-error "$BASE/api/v1/healthz" >/dev/null 2>&1; then
    healthy="true"
    break
  fi
  if ! kill -0 "$CENTER_PID" 2>/dev/null; then
    fail_with_logs "Center exited before health check"
  fi
  sleep 0.25
done
[[ "$healthy" == "true" ]] || fail_with_logs "Center health check timed out"

issued="$(curl --fail --silent --show-error \
  -X POST "$BASE/api/v1/admin/enrollment-tokens" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  --data '{"requested_name":"linux-native-smoke-agent","expires_in_seconds":3600}')" \
  || fail_with_logs "could not issue enrollment token"
enrollment_token="$(json_field "$issued" token)"
[[ -n "$enrollment_token" ]] || fail_with_logs "Center did not return a one-time enrollment token"

export REMOTE_CONNECT_MCP_AGENT_CENTER_URL="$BASE"
export REMOTE_CONNECT_MCP_AGENT_ENROLLMENT_TOKEN="$enrollment_token"
export REMOTE_CONNECT_MCP_AGENT_NAME=linux-native-smoke-agent
export REMOTE_CONNECT_MCP_AGENT_HOST_ID=linux-native-smoke-host
export REMOTE_CONNECT_MCP_AGENT_DEFAULT_CWD="$ROOT"
export REMOTE_CONNECT_MCP_AGENT_STATE_DIR="$STATE_DIR/agent-state"
export REMOTE_CONNECT_MCP_AGENT_CAPABILITIES=command,durable_tasks
export REMOTE_CONNECT_MCP_AGENT_POLL_INTERVAL_MS=250
mkdir -p "$REMOTE_CONNECT_MCP_AGENT_STATE_DIR"

"$AGENT_BINARY" --register-once >"$REGISTER_LOG" 2>&1 &
register_pid=$!
for _ in $(seq 1 120); do
  if [[ -f "$REMOTE_CONNECT_MCP_AGENT_STATE_DIR/identity.json" ]]; then
    break
  fi
  if ! kill -0 "$register_pid" 2>/dev/null; then
    wait "$register_pid" || fail_with_logs "Agent registration exited unsuccessfully"
    break
  fi
  sleep 0.25
done
if [[ ! -f "$REMOTE_CONNECT_MCP_AGENT_STATE_DIR/identity.json" ]]; then
  wait "$register_pid" 2>/dev/null || true
  fail_with_logs "Agent registration timed out"
fi
wait "$register_pid" || fail_with_logs "Agent registration exited unsuccessfully"
machine_id="$(json_field "$(<"$REMOTE_CONNECT_MCP_AGENT_STATE_DIR/identity.json")" machine_id)"
[[ -n "$machine_id" ]] || fail_with_logs "identity.json has no machine_id"

# The long-running process must work from identity.json alone.  Remove the
# enrollment token from its environment before starting it.
unset REMOTE_CONNECT_MCP_AGENT_ENROLLMENT_TOKEN
"$AGENT_BINARY" --run >"$AGENT_LOG" 2>&1 &
AGENT_PID=$!

online="false"
for _ in $(seq 1 120); do
  if machines="$(curl --fail --silent --show-error \
      -H "Authorization: Bearer $ADMIN_TOKEN" \
      "$BASE/api/v1/admin/machines?offset=0&limit=50" 2>/dev/null)"; then
    if [[ "$(machine_online "$machines" "$machine_id")" == "true" ]]; then
      online="true"
      break
    fi
  fi
  if ! kill -0 "$AGENT_PID" 2>/dev/null; then
    fail_with_logs "Agent runtime exited before becoming online"
  fi
  sample_agent_rss
  sleep 0.25
done
[[ "$online" == "true" ]] || fail_with_logs "Agent did not become online within the smoke timeout"

task_payload=$(python3 - "$machine_id" "$ROOT" <<'PY'
import json
import sys
print(json.dumps({
    "machine_id": sys.argv[1],
    "idempotency_key": "linux-native-agent-smoke-1",
    "command": {
        "kind": "command",
        "required_capability": "command",
        "command": "echo rcm-native-agent-smoke",
        "cwd": sys.argv[2],
        "env": {},
        "timeout_seconds": 30,
    },
    # A native smoke agent is registered with the safe workspace default.
    # Keep the scope explicit here: Admin task creation intentionally rejects
    # omitted scope metadata for an unrestricted machine so that a test (or a
    # real caller) cannot gain full-host authority through a legacy payload.
    "scope_mode": "workspace",
    "scope_root": sys.argv[2],
}, separators=(",", ":")))
PY
)
created="$(curl --fail --silent --show-error \
  -X POST "$BASE/api/v1/admin/tasks" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  --data "$task_payload")" \
  || fail_with_logs "could not create command task"
task_id="$(json_field "$created" id)"
[[ -n "$task_id" ]] || fail_with_logs "Center did not return a task id"

terminal="false"
status=""
for _ in $(seq 1 120); do
  task="$(curl --fail --silent --show-error \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$BASE/api/v1/admin/tasks/$task_id" 2>/dev/null)" || true
  if [[ -n "$task" ]]; then
    status="$(json_field "$task" status)"
    case "$status" in
      completed|failed|canceled) terminal="true"; break ;;
    esac
  fi
  sample_agent_rss
  sleep 0.25
done
[[ "$terminal" == "true" ]] || fail_with_logs "task $task_id did not reach a terminal state"
[[ "$status" == "completed" ]] || fail_with_logs "task $task_id ended as $status"

output="$(curl --fail --silent --show-error \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  "$BASE/api/v1/admin/tasks/$task_id/output?cursor=0&limit=65536")" \
  || fail_with_logs "could not read task output"
if [[ "$(json_field "$output" text)" != *rcm-native-agent-smoke* ]]; then
  fail_with_logs "task output did not contain the smoke marker"
fi

sample_agent_rss
write_resource_report
printf '{"center":"native","agent":"native","registration":"ok",'
printf '"identity_without_enrollment_token":"ok","command":"ok","task":"%s"' "$task_id"
if [[ -n "$RESOURCE_REPORT" ]]; then
  printf ',"resource_report":"%s","agent_peak_rss_mib":%s,"agent_rss_samples":%s' \
    "$RESOURCE_REPORT" "$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["peak_rss_mib"])' "$RESOURCE_REPORT" 2>/dev/null || printf 'null')" \
    "$AGENT_RSS_SAMPLES"
fi
printf '}\n'
