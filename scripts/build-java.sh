#!/usr/bin/env bash
set -euo pipefail

if [[ "${GITHUB_ACTIONS:-}" != "true" ]]; then
  echo "Local Java/React compilation is disabled. Push a branch or java-vX.Y.Z tag and let GitHub Actions build it." >&2
  exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE="$ROOT/java/gradlew"
if [[ ! -x "$GRADLE" ]]; then
  echo "Java Gradle Wrapper not found: $GRADLE" >&2
  exit 1
fi
cd "$ROOT/java"

echo '== Java tests =='
"$GRADLE" test --no-daemon

echo '== JVM artifacts =='
"$GRADLE" :center:bootJar :agent:jar :desktop:jar :browser:jar --no-daemon

echo '== React production build =='
pnpm --dir "$ROOT/web" install --frozen-lockfile
pnpm --dir "$ROOT/web" build

echo 'Java/React migration build completed.'
