#!/usr/bin/env bash
set -euo pipefail

[[ $# -eq 1 ]] || { echo "usage: $0 <agent.zip>" >&2; exit 2; }
archive=$1
[[ -f "$archive" ]] || { echo "archive not found: $archive" >&2; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo 'python3 is required to verify a Native Image ZIP.' >&2; exit 1; }

python3 - "$archive" <<'PY'
import hashlib
import pathlib
import re
import sys
import zipfile

archive = pathlib.Path(sys.argv[1]).resolve()
max_entry = 128 * 1024 * 1024
max_total = 256 * 1024 * 1024
allowed = re.compile(r"(?:rcm-agent(?:\.exe)?|[A-Za-z0-9_.-]+\.dll|[A-Za-z0-9_.-]+\.so(?:\.[0-9]+(?:\.[0-9]+)*)?)$", re.I)

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
    if ("rcm-agent.exe" in names) == ("rcm-agent" in names):
        raise SystemExit("Agent archive must contain exactly one of rcm-agent.exe or rcm-agent.")

sidecar = pathlib.Path(str(archive) + ".sha256")
if sidecar.is_file():
    fields = sidecar.read_text(encoding="ascii").strip().split()
    if len(fields) != 2 or not re.fullmatch(r"[0-9a-fA-F]{64}", fields[0]) or fields[1].lstrip("*") != archive.name:
        raise SystemExit(f"Invalid archive SHA-256 sidecar: {sidecar}")
    actual = hashlib.sha256(archive.read_bytes()).hexdigest()
    if actual.casefold() != fields[0].casefold():
        raise SystemExit(f"Agent archive SHA-256 mismatch: {archive}")

print(f"Native Agent archive verified: {archive}")
PY
