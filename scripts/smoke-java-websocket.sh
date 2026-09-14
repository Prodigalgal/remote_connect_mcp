#!/usr/bin/env bash
set -Eeuo pipefail

# Linux WebSocket wake smoke. The script uses Node 22's standards-based
# WebSocket client so it does not add a third-party dependency to CI.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CENTER_BINARY="${1:-${RCM_SMOKE_CENTER_BINARY:-}}"
PORT="${2:-${RCM_SMOKE_PORT:-18187}}"
if [[ -z "$CENTER_BINARY" ]]; then
  echo "usage: $0 /path/to/rcm-center [port]" >&2
  exit 2
fi
CENTER_BINARY="$(readlink -f "$CENTER_BINARY")"
[[ -x "$CENTER_BINARY" ]] || { echo "Center binary is not executable: $CENTER_BINARY" >&2; exit 2; }
command -v curl >/dev/null || { echo "curl is required" >&2; exit 2; }
command -v node >/dev/null || { echo "Node.js 22+ is required" >&2; exit 2; }

BASE="http://127.0.0.1:${PORT}"
STATE_DIR="$(mktemp -d "${TMPDIR:-/tmp}/rcm-java-websocket-smoke.XXXXXX")"
CENTER_PID=""
cleanup() {
  set +e
  if [[ -n "$CENTER_PID" ]] && kill -0 "$CENTER_PID" 2>/dev/null; then
    kill "$CENTER_PID" 2>/dev/null
    wait "$CENTER_PID" 2>/dev/null
  fi
  rm -rf "$STATE_DIR"
}
trap cleanup EXIT INT TERM

export RCM_CENTER_PERSISTENCE_MODE=memory
export RCM_CENTER_VERSION=websocket-smoke
export RCM_CENTER_AGENT_WEBSOCKET_ENABLED=true
export RCM_CENTER_ALLOW_SHARED_ENROLLMENT=true
export REMOTE_CONNECT_MCP_CENTER_MCP_TOKEN=smoke-mcp-token
export REMOTE_CONNECT_MCP_CENTER_ADMIN_TOKEN=smoke-admin-token
export REMOTE_CONNECT_MCP_CENTER_ENROLLMENT_TOKEN=smoke-enrollment-token
"$CENTER_BINARY" "--server.port=$PORT" >"$STATE_DIR/center.log" 2>&1 &
CENTER_PID=$!

healthy=false
for _ in $(seq 1 120); do
  if curl --fail --silent --show-error "$BASE/api/v1/healthz" >/dev/null 2>&1; then
    healthy=true
    break
  fi
  if ! kill -0 "$CENTER_PID" 2>/dev/null; then
    sed -n '1,240p' "$STATE_DIR/center.log" >&2
    exit 1
  fi
  sleep 0.25
done
[[ "$healthy" == true ]] || { echo "Center health check timed out" >&2; exit 1; }

PORT="$PORT" BASE="$BASE" node <<'NODE'
const port = process.env.PORT;
const base = process.env.BASE;
const adminToken = "smoke-admin-token";
const enrollmentToken = "smoke-enrollment-token";

async function jsonFetch(path, init = {}) {
  const response = await fetch(base + path, {
    ...init,
    headers: {
      Authorization: `Bearer ${init.token || adminToken}`,
      "Content-Type": "application/json",
      ...(init.headers || {}),
    },
  });
  const body = await response.text();
  if (!response.ok) throw new Error(`HTTP ${response.status} ${path}: ${body}`);
  return body ? JSON.parse(body) : {};
}

const messages = [];
const messageWaiters = [];
function onMessage(event) {
  let value;
  try { value = JSON.parse(String(event.data)); } catch { return; }
  for (let index = 0; index < messageWaiters.length; index += 1) {
    if (!messageWaiters[index].predicate(value)) continue;
    const waiter = messageWaiters.splice(index, 1)[0];
    clearTimeout(waiter.timer);
    waiter.resolve(value);
    return;
  }
  if (messages.length < 16) messages.push(value);
}

function waitForMessage(socket, predicate, timeoutMs = 8000) {
  for (let index = 0; index < messages.length; index += 1) {
    if (!predicate(messages[index])) continue;
    return Promise.resolve(messages.splice(index, 1)[0]);
  }
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      const index = messageWaiters.findIndex((waiter) => waiter.resolve === resolve);
      if (index >= 0) messageWaiters.splice(index, 1);
      reject(new Error("WebSocket message timeout"));
    }, timeoutMs);
    messageWaiters.push({ predicate, resolve, timer });
  });
}

const registration = await jsonFetch("/agent/v1/register", {
  method: "POST",
  token: enrollmentToken,
  body: JSON.stringify({
    name: "websocket-smoke-agent",
    host_id: "websocket-smoke-host",
    hostname: "websocket-smoke-host",
    os: "linux",
    arch: "amd64",
    version: "smoke",
    default_cwd: process.cwd(),
    scope_mode: "unrestricted",
    capabilities: ["command"],
  }),
});
if (!registration.machine_id || !registration.token) throw new Error("incomplete registration response");

const socket = new WebSocket(`ws://127.0.0.1:${port}/agent/v1/ws`, {
  headers: {
    Authorization: `Bearer ${registration.token}`,
    "X-Machine-ID": registration.machine_id,
  },
});
socket.addEventListener("message", onMessage);
await new Promise((resolve, reject) => {
  const timer = setTimeout(() => reject(new Error("WebSocket open timeout")), 8000);
  socket.addEventListener("open", () => { clearTimeout(timer); resolve(); }, { once: true });
  socket.addEventListener("error", () => { clearTimeout(timer); reject(new Error("WebSocket open failed")); }, { once: true });
});
await waitForMessage(socket, (value) => value.type === "ready");
socket.send(JSON.stringify({ type: "ping" }));
await waitForMessage(socket, (value) => value.type === "pong");

const task = await jsonFetch("/api/v1/admin/tasks", {
  method: "POST",
  body: JSON.stringify({
    machine_id: registration.machine_id,
    scope_mode: "unrestricted",
    command: { kind: "command", required_capability: "command", command: "echo websocket-wake", cwd: process.cwd(), env: {}, timeout_seconds: 30 },
  }),
});
if (!task.id) throw new Error("Center did not return a task id");
await waitForMessage(socket, (value) => value.type === "wake");
socket.close();
console.log(JSON.stringify({ center: "native", registration: "ok", websocket: "connected", ping_pong: "ok", wake: "ok", task: task.id }));
NODE
