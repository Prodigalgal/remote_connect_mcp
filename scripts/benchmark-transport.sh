#!/usr/bin/env bash
set -euo pipefail

# One-shot transport capability probe. It never starts a scheduler and never
# prints the bearer token. The Agent protocol remains HTTPS-first; HTTP/3 is
# measured only when the local curl build explicitly supports it.
url="${RCM_TRANSPORT_URL:-}"
if [[ -z "${url}" ]]; then
  echo 'RCM_TRANSPORT_URL is required (for example https://center.example.invalid/api/v1/healthz)' >&2
  exit 2
fi
command -v curl >/dev/null || { echo 'curl is required' >&2; exit 2; }

echo 'transport capability:'
curl --version | sed -n '1,2p'

measure() {
  local label="$1"
  shift
  local output
  if output="$(curl -fsS --connect-timeout 5 --max-time 15 -o /dev/null -w '%{http_code} %{time_connect} %{time_starttransfer} %{time_total}' "$@" "$url" 2>&1)"; then
    printf '%-10s %s\n' "$label" "$output"
  else
    printf '%-10s unavailable (%s)\n' "$label" "${output//$'\n'/ }"
  fi
}

measure http1 --http1.1
measure http2 --http2
if curl --version | grep -qiE 'http3|quic'; then
  measure http3 --http3-only
else
  echo 'http3      unsupported by this curl build (no request sent)'
fi

cat <<'EOF'
decision: keep HTTPS/WebSocket as the correctness path. Enable an HTTP/3
provider only after repeated measurements show a material tail-latency or
loss benefit, and retain an explicit HTTPS fallback.
EOF
