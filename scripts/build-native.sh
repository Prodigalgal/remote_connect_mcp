#!/usr/bin/env bash
set -eu

if [ "${GITHUB_ACTIONS:-}" != "true" ]; then
  echo "Local Native Image compilation is disabled. Push a java-vX.Y.Z tag and let GitHub Actions build the matching runner artifact." >&2
  exit 2
fi

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
if ! command -v native-image >/dev/null 2>&1; then
  for candidate in "${RCM_GRAALVM_HOME:-}" "${GRAALVM_HOME:-}" "${JAVA_HOME:-}"; do
    if [ -n "${candidate}" ] && [ -x "${candidate}/bin/native-image" ]; then
      export JAVA_HOME="${candidate}"
      export PATH="${candidate}/bin:${PATH}"
      break
    fi
  done
fi
command -v native-image >/dev/null 2>&1 || { echo "native-image was not found; set RCM_GRAALVM_HOME to GraalVM/NIK 25.x. JVM JARs are not production binaries." >&2; exit 1; }
command -v zip >/dev/null 2>&1 || { echo "zip was not found; install the zip utility to package Native Image runtime libraries." >&2; exit 1; }
JAVA_VERSION=$(java -version 2>&1 | head -n 1)
case "$JAVA_VERSION" in *25*) ;; *) echo "Java 25 is required, detected: $JAVA_VERSION" >&2; exit 1 ;; esac

case "$(uname -s)" in
  Linux*) OS=linux ;;
  *) echo "build-native.sh must run on Linux; use build-native.ps1 on Windows." >&2; exit 1 ;;
esac
case "$(uname -m)" in
  x86_64|amd64) ARCH=amd64 ;;
  aarch64|arm64) ARCH=arm64 ;;
  *) echo "unsupported host architecture: $(uname -m)" >&2; exit 1 ;;
esac
VERSION=${RCM_RELEASE_VERSION:-$(git describe --tags --always --dirty 2>/dev/null || printf '%s' dev)}
OUT="$ROOT/dist/java-$OS-$ARCH"
# This directory is owned by this script. Remove only the architecture-specific
# output so a stale executable/DLL/checksum cannot be mistaken for the current
# build. The release archives are written one level above it.
rm -rf -- "$OUT"
mkdir -p "$OUT"

cd "$ROOT/java"
# Native Image is intentionally serialized; running Center and Agent images
# together can consume several GiB per process.
./gradlew :center:nativeCompile :agent:nativeCompile --no-daemon --no-parallel
for name in rcm-center rcm-agent; do
  src="${name#rcm-}/build/native/nativeCompile/$name"
  test -f "$src" || { echo "native artifact missing: $src" >&2; exit 1; }
  bundle="$OUT/${name#rcm-}"
  mkdir -p "$bundle"
  cp "$src" "$bundle/$name"
  # AWT/Desktop support can emit libawt/libfontmanager and other shared
  # objects beside the executable on Linux. Keep those libraries with the
  # matching executable and never merge Center/Agent runtime directories.
  find "$(dirname "$src")" -maxdepth 1 -type f \( -name '*.so' -o -name '*.so.*' \) -exec cp -f {} "$bundle/" \;
  (cd "$bundle" && sha256sum $(find . -maxdepth 1 -type f -printf '%f\n' | sort) > SHA256SUMS)
  chmod +x "$bundle/$name"
done

# Keep the local package shape identical to the Linux GitHub Release assets:
# flat Center/Agent ZIP bundles contain the executable and all Native Image
# shared objects. The archives are outside OUT so they are never packed into
# themselves; a raw executable is retained only inside its bundle directory.
AGENT_ASSET="$ROOT/dist/remote-connect-mcp-agent-$VERSION-$OS-$ARCH"
CENTER_ASSET="$ROOT/dist/remote-connect-mcp-center-$VERSION-$OS-$ARCH"
AGENT_ARCHIVE="$AGENT_ASSET.zip"
CENTER_ARCHIVE="$CENTER_ASSET.zip"
agent_files=$(cd "$OUT/agent" && find . -maxdepth 1 -type f \( -name 'rcm-agent' -o -name '*.so' -o -name '*.so.*' \) -printf '%f\n' | sort)
center_files=$(cd "$OUT/center" && find . -maxdepth 1 -type f \( -name 'rcm-center' -o -name '*.so' -o -name '*.so.*' \) -printf '%f\n' | sort)
(cd "$OUT/agent" && zip -q -j "$AGENT_ARCHIVE" $agent_files)
(cd "$OUT/center" && zip -q -j "$CENTER_ARCHIVE" $center_files)
sha256sum "$AGENT_ARCHIVE" > "$AGENT_ARCHIVE.sha256"
sha256sum "$CENTER_ARCHIVE" > "$CENTER_ARCHIVE.sha256"
"$ROOT/scripts/verify-native-bundle.sh" "$AGENT_ARCHIVE"

cp "$ROOT/scripts/install-java-agent.sh" "$ROOT/deploy/systemd/remote-connect-mcp-agent.service" "$OUT/"
cp "$ROOT/deploy/systemd/agent.env.example" "$OUT/agent.env.example"
cp "$ROOT/README.md" "$ROOT/LICENSE" "$ROOT/NOTICE" "$OUT/"
chmod +x "$OUT/install-java-agent.sh"

printf '{"version":"%s","os":"%s","arch":"%s","artifacts":["center/rcm-center","agent/rcm-agent","install-java-agent.sh","remote-connect-mcp-agent.service","agent.env.example","README.md","LICENSE","NOTICE"]}\n' \
  "$VERSION" "$OS" "$ARCH" > "$OUT/manifest.json"
ARCHIVE="$ROOT/dist/remote-connect-mcp-$VERSION-$OS-$ARCH.tar.gz"
tar -C "$OUT" -czf "$ARCHIVE" .
sha256sum "$ARCHIVE" > "$ARCHIVE.sha256"
echo "Native Java artifacts written to $OUT"
echo "Release assets written to $ROOT/dist"
