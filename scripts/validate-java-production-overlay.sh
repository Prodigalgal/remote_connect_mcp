#!/usr/bin/env bash
set -Eeuo pipefail

# Validate the Java Center/React production overlay before it is handed to
# Kustomize/Argo CD.  The repository intentionally keeps a public,
# placeholder-safe template; a real deployment must run this script in
# --strict mode against a private overlay copy.  The script only prints paths,
# counts and failure reasons.  It never dumps rendered manifests or Secret
# values into a CI log.

usage() {
  cat >&2 <<'EOF'
Usage:
  validate-java-production-overlay.sh [--strict|--template] [overlay-directory]

  --strict    Require real domains, immutable image digests and a renderable
              overlay.  Use this for a private deployment overlay.
  --template  Validate the public placeholder-safe template (CI mode).
EOF
}

mode=template
overlay="deploy/k8s/overlays/java-production"
while (($# > 0)); do
  case "$1" in
    --strict) mode=strict ;;
    --template) mode=template ;;
    -h|--help) usage; exit 0 ;;
    -*) usage; exit 2 ;;
    *) overlay="$1" ;;
  esac
  shift
done

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ "$overlay" != /* ]]; then
  overlay="$ROOT/$overlay"
fi
overlay="$(cd "$overlay" 2>/dev/null && pwd)" || {
  echo "java production overlay: directory not found" >&2
  exit 1
}

failed=0
fail() {
  printf 'java production overlay: %s\n' "$1" >&2
  failed=1
}

required_files=(
  kustomization.yaml
  center-production-patch.yaml
  console-production-patch.yaml
  route-production-patch.yaml
)
for file in "${required_files[@]}"; do
  [[ -f "$overlay/$file" ]] || fail "missing required file: $file"
done
((failed == 0)) || exit 1

kustomization="$overlay/kustomization.yaml"
routes="$overlay/route-production-patch.yaml"

# The public overlay is deliberately a template.  Keep enough placeholders in
# it to prevent a maintainer from accidentally committing a real deployment
# configuration to the public repository.
placeholder_count="$(grep -R -n -I -E -- 'example[.]invalid|replace-me|v0[.]1[.]0' "$overlay" | wc -l | tr -d ' ')" || placeholder_count=0
if [[ "$mode" == template ]]; then
  ((placeholder_count >= 3)) || fail 'template mode expects placeholder-safe domains/images to remain in the public overlay'
else
  ((placeholder_count == 0)) || fail 'strict mode found a template placeholder (example.invalid, replace-me or v0.1.0)'
fi

# Kustomize must replace both base images.  Strict mode requires a digest for
# each image; tags alone are mutable and are not an acceptable production
# pin.  The template is expected to use tags until a private overlay supplies
# the two digests.
image_names="$(grep -n -I -E -- '^[[:space:]]*-[[:space:]]+name:' "$kustomization" || true)"
image_new_names="$(grep -n -I -E -- '^[[:space:]]+newName:' "$kustomization" || true)"
image_count="$(printf '%s\n' "$image_names" | sed '/^[[:space:]]*$/d' | wc -l | tr -d ' ')"
new_name_count="$(printf '%s\n' "$image_new_names" | sed '/^[[:space:]]*$/d' | wc -l | tr -d ' ')"
((image_count == 2)) || fail "expected two image entries, found $image_count"
((new_name_count == 2)) || fail "expected two newName entries, found $new_name_count"

digest_matches="$(grep -n -I -E -- '^[[:space:]]+digest:[[:space:]]*sha256:[0-9A-Fa-f]{64}[[:space:]]*$' "$kustomization" || true)"
digest_count="$(printf '%s\n' "$digest_matches" | sed '/^[[:space:]]*$/d' | wc -l | tr -d ' ')"
if [[ "$mode" == strict ]]; then
  ((digest_count == 2)) || fail "strict mode requires two SHA-256 image digests, found $digest_count"
else
  ((digest_count == 0)) || fail 'template mode must not contain a production image digest'
fi

# Every public route is intentionally prefixed with remote-connect-mcp-.  The
# check also catches a malformed or missing hostname without echoing it.
hostname_lines="$(grep -n -I -E -- '^[[:space:]]*-[[:space:]]+remote-connect-mcp-[A-Za-z0-9-]+[.][A-Za-z0-9.-]+[[:space:]]*$' "$routes" || true)"
hostname_count="$(printf '%s\n' "$hostname_lines" | sed '/^[[:space:]]*$/d' | wc -l | tr -d ' ')"
((hostname_count == 3)) || fail "expected three remote-connect-mcp hostnames, found $hostname_count"
hostname_values="$(printf '%s\n' "$hostname_lines" | sed -E 's/^[^:]+:[[:space:]]*-[[:space:]]*//; s/[[:space:]]+$//' | sort -u)"
unique_hostname_count="$(printf '%s\n' "$hostname_values" | sed '/^[[:space:]]*$/d' | wc -l | tr -d ' ')"
((unique_hostname_count == hostname_count)) || fail 'route hostnames must be unique'

# Secrets are intentionally external to Git.  A private overlay may reference
# the existing Secret through the base, but must not add a Secret manifest or
# inline stringData/data of its own.
if grep -R -n -I -E -- '^[[:space:]]*kind:[[:space:]]*Secret([[:space:]]|$)|^[[:space:]]*(stringData|data):' "$overlay" >/dev/null; then
  fail 'overlay must not contain an inline Secret, stringData or data block'
fi
base="$overlay/../../java-center"
base="$(cd "$base" 2>/dev/null && pwd)" || fail 'overlay resource base ../../java-center is missing'
if [[ -d "$base" ]]; then
  grep -R -q -I -E -- 'name:[[:space:]]*remote-connect-mcp-java-secrets' "$base" || \
    fail 'java-center base must reference the external remote-connect-mcp-java-secrets Secret'
  secret_ref_count="$(grep -R -n -I -E -- '^[[:space:]]*secretKeyRef:' "$base" | wc -l | tr -d ' ')"
  ((secret_ref_count >= 5)) || fail "java-center base has too few Secret references: $secret_ref_count"
fi

# A Center version must be explicit and must not retain the public template
# value.  This is checked separately because image digests and displayed
# release versions intentionally live in different Kustomize fields.
center_patch="$overlay/center-production-patch.yaml"
if ! grep -q -I -E -- '^[[:space:]]*-[[:space:]]+name:[[:space:]]*RCM_CENTER_VERSION[[:space:]]*$' "$center_patch"; then
  fail 'center-production-patch.yaml must set RCM_CENTER_VERSION'
fi
if [[ "$mode" == strict ]] && ! grep -q -I -E -- '^[[:space:]]+value:[[:space:]]+v?[0-9]+[.][0-9]+[.][0-9]+[[:space:]]*$' "$center_patch"; then
  fail 'strict mode requires a semantic Center version (for example v1.2.3)'
fi

# Render when a Kustomize implementation is available.  Strict mode requires
# this final check; template mode can run in minimal CI images and still
# enforce all static invariants above.
rendered=""
cleanup() {
  [[ -z "$rendered" ]] || rm -f -- "$rendered"
}
trap cleanup EXIT
if command -v kustomize >/dev/null 2>&1; then
  rendered="$(mktemp)"
  kustomize build "$overlay" > "$rendered" || fail 'kustomize build failed'
elif command -v kubectl >/dev/null 2>&1; then
  rendered="$(mktemp)"
  kubectl kustomize "$overlay" > "$rendered" || fail 'kubectl kustomize failed'
elif [[ "$mode" == strict ]]; then
  fail 'strict mode requires kustomize or kubectl for a final rendered-manifest check'
fi

if [[ -n "$rendered" && -s "$rendered" ]]; then
  if [[ "$mode" == strict ]]; then
    grep -n -I -E -- 'example[.]invalid|replace-me|v0[.]1[.]0|image:[^[:space:]]+:latest' "$rendered" >/dev/null && \
      fail 'rendered manifest still contains a template placeholder or latest image'
    rendered_digest_count="$(grep -n -I -E -- 'image:[^[:space:]]+@sha256:[0-9A-Fa-f]{64}([[:space:]]|$)' "$rendered" | wc -l | tr -d ' ')"
    ((rendered_digest_count >= 2)) || fail "rendered manifest has too few digest-pinned images: $rendered_digest_count"
  fi
fi

if ((failed != 0)); then
  if [[ "$mode" == strict ]]; then
    echo 'java production overlay validation failed; do not apply this overlay.' >&2
  else
    echo 'java production overlay template validation failed.' >&2
  fi
  exit 1
fi

if [[ "$mode" == strict ]]; then
  echo 'java production overlay strict validation passed (private values, immutable images, external Secret refs and render check)'
else
  echo 'java production overlay template validation passed (placeholders retained, structure and external Secret refs checked)'
fi
