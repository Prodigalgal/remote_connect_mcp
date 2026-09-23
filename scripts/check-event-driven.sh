#!/usr/bin/env bash
set -Eeuo pipefail

# Static gate for event-driven production paths. Retry/backoff and request
# deadlines remain valid; only fleet-wide fixed-rate loops are rejected. The
# Center's single GitHub Release metadata check does not poll individual Agents.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

failed=0
tracked_matches() {
  local pattern="$1"
  git grep -n -I -E -e "$pattern" -- java web scripts 2>/dev/null \
    | awk '!/[\\/]src[\\/]test[\\/]|[\\/]node_modules[\\/]|[\\/]dist[\\/]|scripts[\\/]check-event-driven[.]sh:/'
}
report_forbidden() {
  local label="$1" pattern="$2" matches
  matches="$(tracked_matches "$pattern" || true)"
  if [[ -n "$matches" ]]; then
    printf 'event-driven gate: %s found in:\n%s\n' "$label" "$matches" >&2
    failed=1
  fi
}

report_forbidden 'fixed-interval scheduler' 'setInterval|scheduleAtFixedRate|scheduleWithFixedDelay|time[.]Tick[[:space:]]*\\('
report_forbidden 'non-blocking PostgreSQL notification API' 'getNotifications[[:space:]]*\\([[:space:]]*(0[[:space:]]*)?\\)'

if (( failed != 0 )); then
  echo 'event-driven gate failed; use conditions, OS/file/process events, long-poll deadlines or failure backoff instead of fixed polling.' >&2
  exit 1
fi

echo 'event-driven gate passed (no fleet-wide fixed-rate loop or non-blocking PG listener found)'
