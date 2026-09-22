#!/usr/bin/env bash
set -euo pipefail

[[ $# -eq 1 || $# -eq 2 ]] || { echo "usage: $0 <bundle.zip> [executable-name]" >&2; exit 2; }
archive=$1
expected=${2:-}
[[ -f "$archive" ]] || { echo "archive not found: $archive" >&2; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo 'python3 is required to verify a Native Image ZIP.' >&2; exit 1; }

python3 - "$archive" "$expected" <<'PY'
import hashlib
import pathlib
import re
import sys
import zipfile

archive = pathlib.Path(sys.argv[1]).resolve()
max_entry = 128 * 1024 * 1024
max_total = 256 * 1024 * 1024
expected_arg = sys.argv[2].strip() if len(sys.argv) > 2 else ""
expected = expected_arg.casefold() or None
if expected is not None and not re.fullmatch(r"rcm-(?:center|agent|desktop-companion|browser-agent)(?:\.exe)?", expected, re.I):
    raise SystemExit("executable-name must be rcm-center, rcm-center.exe, rcm-agent, rcm-agent.exe, rcm-desktop-companion, rcm-desktop-companion.exe, rcm-browser-agent, or rcm-browser-agent.exe")
allowed = re.compile(r"(?:rcm-(?:center|agent|desktop-companion|browser-agent)(?:\.exe)?|[A-Za-z0-9_.-]+\.dll|[A-Za-z0-9_.-]+\.so(?:\.[0-9]+(?:\.[0-9]+)*)?)$", re.I)

with zipfile.ZipFile(archive) as bundle:
    entries = [entry for entry in bundle.infolist() if entry.filename and not entry.is_dir()]
    if not entries:
        raise SystemExit("Agent archive is empty.")
    names = set()
    total = 0
    for entry in entries:
        name = entry.filename.replace("\\", "/")
        if "/" in name or name.startswith("/") or ":" in name or re.search(r"(^|/)\.\.(?:/|$)", name):
            raise SystemExit(f"Agent archive must contain only flat file names: {entry.filename}")
        if not allowed.fullmatch(name):
            raise SystemExit(f"Unexpected file in Agent archive: {entry.filename}")
        folded = name.casefold()
        if folded in names:
            raise SystemExit(f"Duplicate file in Agent archive: {name}")
        names.add(folded)
        if entry.file_size < 0 or entry.file_size > max_entry:
            raise SystemExit(f"Agent archive entry exceeds 128 MiB: {name}")
        total += entry.file_size
        if total > max_total:
            raise SystemExit("Agent archive exceeds 256 MiB uncompressed.")
    executables = [name for name in names if re.fullmatch(r"rcm-(?:center|agent|desktop-companion|browser-agent)(?:\.exe)?", name, re.I)]
    if len(executables) != 1:
        raise SystemExit("Native bundle must contain exactly one canonical executable.")
    if expected is not None and executables[0].casefold() != expected:
        raise SystemExit(f"Native bundle contains {executables[0]}, expected {expected}.")

sidecar = pathlib.Path(str(archive) + ".sha256")
if sidecar.is_file():
    fields = sidecar.read_text(encoding="ascii").strip().split()
    if len(fields) != 2 or not re.fullmatch(r"[0-9a-fA-F]{64}", fields[0]) or fields[1].lstrip("*") != archive.name:
        raise SystemExit(f"Invalid archive SHA-256 sidecar: {sidecar}")
    actual = hashlib.sha256(archive.read_bytes()).hexdigest()
    if actual.casefold() != fields[0].casefold():
        raise SystemExit(f"Agent archive SHA-256 mismatch: {archive}")

print(f"Native bundle verified: {archive}")
PY
