#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source="${root}/web/src/artifact-viewer/artifact-viewer-v1.html"
target="${root}/java/center/src/main/resources/mcp/artifact-viewer-v1.html"
test -s "${source}"
mkdir -p "$(dirname "${target}")"
cp "${source}" "${target}"
grep -q 'ui/notifications/tool-result' "${target}"
grep -q 'uploadFile' "${target}"
echo "synced artifact viewer: ${target}"
