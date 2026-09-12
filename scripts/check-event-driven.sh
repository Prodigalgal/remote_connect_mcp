#!/usr/bin/env bash
set -Eeuo pipefail

# Event-driven regression gate.  This is intentionally a small static check:
# it does not replace tests, but prevents a future change from reintroducing
# a fleet-wide fixed-interval scheduler or the non-blocking PostgreSQL
# notification API in production paths.  Retry/backoff and request deadlines
# remain valid; they are not blanket-banned here.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

failed=0

tracked_matches() {
  local pattern="$1"
  git grep -n -I -E -e "$pattern" -- internal java web scripts 2>/dev/null \
    | awk '!/_test[.]go:|[\\/]src[\\/]test[\\/]|[\\/]node_modules[\\/]|[\\/]dist[\\/]|scripts[\\/]check-event-driven[.]sh:/'
}

report_forbidden() {
  local label="$1"
  local pattern="$2"
  local matches
  matches="$(tracked_matches "$pattern" || true)"
  if [[ -n "$matches" ]]; then
    printf 'event-driven gate: %s found in:\n%s\n' "$label" "$matches" >&2
    failed=1
  fi
}

# A fixed-interval loop is the failure mode this project is designed to
# avoid. setTimeout remains allowed for one-shot deadlines and retry backoff.
report_forbidden 'fixed-interval scheduler' 'setInterval|scheduleAtFixedRate|scheduleWithFixedDelay|time[.]Tick[[:space:]]*\('

# pgjdbc's no-argument overload returns immediately and can spin a listener at
# CPU speed. LISTEN/NOTIFY listeners must use getNotifications(0), which blocks
# on the database socket until a notification or connection error.
report_forbidden 'non-blocking PostgreSQL notification API' 'getNotifications[[:space:]]*\([[:space:]]*\)'

# One deliberately explicit compatibility exception remains for Go agents
# recovered on very old kernels/ACLs where no waitable process handle exists.
# Keep that fallback rare and recognizable; every other ticker is forbidden.
ticker_matches="$(tracked_matches 'time[.]NewTicker[[:space:]]*\(' || true)"
while IFS= read -r match; do
  [[ -z "$match" ]] && continue
  if [[ "$match" != *'ticker := time.NewTicker(5 * time.Second)'* ]]; then
    printf 'event-driven gate: unexpected Go ticker (only the documented 5s recovery fallback is allowed):\n%s\n' "$match" >&2
    failed=1
  fi
done <<< "$ticker_matches"

if ! grep -Fq 'Very old Linux kernels or restrictive Windows ACLs' internal/process/manager.go; then
  echo 'event-driven gate: documented legacy process-wait fallback marker is missing' >&2
  failed=1
fi

if (( failed != 0 )); then
  echo 'event-driven gate failed; use conditions, OS/file/process events, long-poll deadlines or failure backoff instead of fixed polling.' >&2
  exit 1
fi

echo 'event-driven gate passed (no fixed-interval production scheduler or non-blocking PG listener found)'
