#!/usr/bin/env bash
# SBOM content check (AC-040): fails closed unless
#   1. the SBOM names a pinned Java dependency (jackson-core) with a version, and
#   2. producer-manifest.json lists the embedded toolchain: a non-empty toolchain_sha256 equal to a jdk-archive sha256 of pins.json.
#
#   check-sbom.sh <sbom.json> <producer-manifest.json> [pins.json]
#
# Exit 0 pass, 1 content check failed, 2 the check could not run (missing file or jq, unparseable JSON).
set -euo pipefail

sbom="${1:-}"
manifest="${2:-}"
pins="${3:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/pins.json}"

command -v jq >/dev/null 2>&1 || { echo "check-sbom: jq is required" >&2; exit 2; }
for f in "$sbom" "$manifest" "$pins"; do
  if [ -z "$f" ] || [ ! -f "$f" ]; then echo "check-sbom: file not found: '$f'" >&2; exit 2; fi
  jq -e . "$f" >/dev/null 2>&1 || { echo "check-sbom: not valid JSON: $f" >&2; exit 2; }
done

named=$(jq -r '[.components[]? | select(.name == "jackson-core" and ((.version // "") != ""))] | length' "$sbom")
if [ "$named" -lt 1 ]; then
  echo "check-sbom: FAIL: the SBOM names no versioned jackson-core component" >&2
  exit 1
fi

toolchain=$(jq -r '.toolchain_sha256 // ""' "$manifest")
if [ -z "$toolchain" ]; then
  echo "check-sbom: FAIL: producer-manifest.json carries no toolchain_sha256" >&2
  exit 1
fi
pinned=$(jq -r --arg sha "$toolchain" '[.tools[] | select(.kind == "jdk-archive" and .sha256 == $sha)] | length' "$pins")
if [ "$pinned" -lt 1 ]; then
  echo "check-sbom: FAIL: toolchain_sha256 $toolchain is not a pinned jdk-archive sha256 of pins.json" >&2
  exit 1
fi

components=$(jq -r '.components | length' "$sbom")
echo "check-sbom: ok components=$components toolchain=$toolchain"
