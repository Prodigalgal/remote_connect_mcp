#!/bin/sh
set -eu

if [ "$(id -u)" -ne 0 ]; then
  echo "run as root" >&2
  exit 1
fi

: "${REMOTE_CONNECT_MCP_AGENT_CENTER_URL:?required}"
: "${REMOTE_CONNECT_MCP_AGENT_ENROLLMENT_TOKEN:?required}"
: "${REMOTE_CONNECT_MCP_AGENT_NAME:?required}"

BINARY_SOURCE=${REMOTE_CONNECT_MCP_AGENT_BINARY:-./remote-connect-mcp-agent}
if [ ! -x "$BINARY_SOURCE" ]; then
  echo "agent binary is missing or not executable: $BINARY_SOURCE" >&2
  exit 1
fi

MAX_CONCURRENCY=${REMOTE_CONNECT_MCP_AGENT_MAX_CONCURRENCY:-1}
MAX_OUTPUT_BYTES=${REMOTE_CONNECT_MCP_AGENT_MAX_OUTPUT_BYTES:-67108864}
MAX_AGGREGATE_OUTPUT_BYTES=${REMOTE_CONNECT_MCP_AGENT_MAX_AGGREGATE_OUTPUT_BYTES:-}
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
if [ "$MAX_AGGREGATE_OUTPUT_BYTES" -lt "$MAX_OUTPUT_BYTES" ]; then
  echo "REMOTE_CONNECT_MCP_AGENT_MAX_AGGREGATE_OUTPUT_BYTES must be at least MAX_OUTPUT_BYTES" >&2
  exit 1
fi

install -d -m 0755 /opt/remote-connect-mcp-agent
install -m 0755 "$BINARY_SOURCE" /opt/remote-connect-mcp-agent/remote-connect-mcp-agent
install -d -m 0700 /etc/remote-connect-mcp-agent /var/lib/remote-connect-mcp-agent

umask 077
{
  printf 'REMOTE_CONNECT_MCP_AGENT_CENTER_URL=%s\n' "$REMOTE_CONNECT_MCP_AGENT_CENTER_URL"
  printf 'REMOTE_CONNECT_MCP_AGENT_ENROLLMENT_TOKEN=%s\n' "$REMOTE_CONNECT_MCP_AGENT_ENROLLMENT_TOKEN"
  printf 'REMOTE_CONNECT_MCP_AGENT_NAME=%s\n' "$REMOTE_CONNECT_MCP_AGENT_NAME"
  printf 'REMOTE_CONNECT_MCP_AGENT_HOST_ID=%s\n' "${REMOTE_CONNECT_MCP_AGENT_HOST_ID:-$REMOTE_CONNECT_MCP_AGENT_NAME}"
  printf 'REMOTE_CONNECT_MCP_AGENT_DEFAULT_CWD=%s\n' "${REMOTE_CONNECT_MCP_AGENT_DEFAULT_CWD:-/}"
  printf 'REMOTE_CONNECT_MCP_AGENT_SCOPE_MODE=%s\n' "${REMOTE_CONNECT_MCP_AGENT_SCOPE_MODE:-unrestricted}"
  printf 'REMOTE_CONNECT_MCP_AGENT_WORKSPACE_ROOT=%s\n' "${REMOTE_CONNECT_MCP_AGENT_WORKSPACE_ROOT:-}"
  printf 'REMOTE_CONNECT_MCP_AGENT_CAPABILITIES=%s\n' "${REMOTE_CONNECT_MCP_AGENT_CAPABILITIES:-command,durable_tasks}"
  printf 'REMOTE_CONNECT_MCP_AGENT_VERSION=%s\n' "${REMOTE_CONNECT_MCP_AGENT_VERSION:-dev}"
  printf 'REMOTE_CONNECT_MCP_AGENT_BROWSER_ADAPTER=%s\n' "${REMOTE_CONNECT_MCP_AGENT_BROWSER_ADAPTER:-}"
  printf 'REMOTE_CONNECT_MCP_AGENT_DESKTOP_ENABLED=%s\n' "${REMOTE_CONNECT_MCP_AGENT_DESKTOP_ENABLED:-false}"
  printf 'REMOTE_CONNECT_MCP_AGENT_STATE_DIR=%s\n' "${REMOTE_CONNECT_MCP_AGENT_STATE_DIR:-/var/lib/remote-connect-mcp-agent}"
  printf 'REMOTE_CONNECT_MCP_AGENT_MAX_CONCURRENCY=%s\n' "$MAX_CONCURRENCY"
  printf 'REMOTE_CONNECT_MCP_AGENT_MAX_OUTPUT_BYTES=%s\n' "$MAX_OUTPUT_BYTES"
  printf 'REMOTE_CONNECT_MCP_AGENT_MAX_AGGREGATE_OUTPUT_BYTES=%s\n' "$MAX_AGGREGATE_OUTPUT_BYTES"
  printf 'REMOTE_CONNECT_MCP_AGENT_BINARY_PATH=%s\n' '/opt/remote-connect-mcp-agent/remote-connect-mcp-agent'
  printf 'REMOTE_CONNECT_MCP_AGENT_SERVICE_NAME=%s\n' 'remote-connect-mcp-agent'
} > /etc/remote-connect-mcp-agent/agent.env

install -m 0644 "${REMOTE_CONNECT_MCP_AGENT_SERVICE_FILE:-./remote-connect-mcp-agent.service}" /etc/systemd/system/remote-connect-mcp-agent.service
systemctl daemon-reload
systemctl enable --now remote-connect-mcp-agent.service
systemctl is-active --quiet remote-connect-mcp-agent.service
echo "remote-connect-mcp-agent is active"
