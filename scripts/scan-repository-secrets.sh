#!/usr/bin/env bash
set -Eeuo pipefail

# Repository hygiene gate.  It intentionally reports paths only and never
# prints the matching line, so a CI failure cannot echo a credential into the
# Actions log.  This script is run by GitHub Actions after checkout; it does
# not inspect or mutate any deployment Secret.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

failed=0

report_matches() {
  local label="$1"
  local pattern="$2"
  local matches
  # Use -e so patterns that begin with a dash (for example the PEM header
  # detector) cannot be parsed as git-grep options. A scanner error must not
  # be silently mistaken for a clean repository.
  local status=0
  matches="$(git grep -l -I -E -e "$pattern" -- . \
    ':!scripts/scan-repository-secrets.sh' \
    ':!*.lock' \
    ':!java/gradle/wrapper/gradle-wrapper.jar')" || status=$?
  if (( status > 1 )); then
    printf 'repository hygiene: %s scanner failed (git grep exit %d)\n' "$label" "$status" >&2
    failed=1
    return
  fi
  if [[ -n "$matches" ]]; then
    printf 'repository hygiene: %s found in:\n%s\n' "$label" "$matches" >&2
    failed=1
  fi
}

report_matches 'private deployment domain' 'fantong[.]eu[.]org'
report_matches 'private network address' '(^|[^0-9])(10[.]([0-9]{1,3}[.]){2}[0-9]{1,3}|192[.]168[.]([0-9]{1,3}[.])?[0-9]{1,3}|172[.](1[6-9]|2[0-9]|3[01])([.][0-9]{1,3}){2}|100[.]64[.]([0-9]{1,3}[.])?[0-9]{1,3})([^0-9]|$)'
report_matches 'SSH URL with embedded credentials' 'ssh://[^[:space:]]+@'
report_matches 'private key material' '-----BEGIN (RSA|OPENSSH|EC|DSA|PRIVATE) KEY-----'
report_matches 'common provider credential' '(ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16}|sk-[A-Za-z0-9]{20,})'
report_matches 'high-entropy RCM secret assignment' 'REMOTE_CONNECT_MCP_[A-Z0-9_]*(TOKEN|PASSWORD|SECRET)=[A-Za-z0-9._~+/=-]{32,}'

tracked_sensitive="$(git ls-files | grep -E '(^|/)[.]env([.]|$)|(^|/)(kubeconfig|id_rsa|id_ed25519|[^/]+[.]pem)$' || true)"
if [[ -n "$tracked_sensitive" ]]; then
  printf 'repository hygiene: sensitive-looking tracked file names found:\n%s\n' "$tracked_sensitive" >&2
  failed=1
fi

if (( failed != 0 )); then
  echo 'repository hygiene failed; keep private domains, credentials, keys and env files outside Git.' >&2
  exit 1
fi

echo 'repository hygiene passed (no private deployment values or high-confidence secrets found)'
