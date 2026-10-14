#!/usr/bin/env bash
# Package one native producer leg (WP01 T005): copy the built binary, measure it against the pinned floor, verify it, record the
# provenance manifest and the start-time measurement, and run the SBOM content check (AC-040).
#
#   package-leg.sh <leg> <out-dir> [--enforce-start-gate]
#
# <leg> is a leg of scripts/release/dist-set.json (linux-x86_64, macos-arm64, windows-x86_64). The pinned floor is derived from that
# leg's wheel tag (manylinux_2_28 -> glibc 2.28, macosx_11_0 -> minos 11.0); the measured floor must not exceed it (a lower value is
# fine). Writes producer-manifest.json, producer-measure.json, the binary and producer-sbom.json into <out-dir>.
#
# Exit 0 packaged, 1 a gate failed (floor exceeded, signature, SBOM content, start-time gate), 2 the packaging could not run.
set -euo pipefail

leg="${1:?leg}"
out="${2:?output directory}"
enforce=""
[ "${3:-}" = "--enforce-start-gate" ] && enforce="yes"

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
project="$(cd "$here/.." && pwd)"
root="$(cd "$project/../.." && pwd)"
command -v jq >/dev/null 2>&1 || { echo "package-leg: jq is required" >&2; exit 2; }

# DIST_SET overrides the file only for the planted floor-gate run of expect-failure.sh.
dist_set="${DIST_SET:-$root/scripts/release/dist-set.json}"
tag=$(jq -r --arg leg "$leg" '[.platform_wheels[] | select(.leg == $leg)][0].tag // empty' "$dist_set")
[ -n "$tag" ] || { echo "package-leg: leg '$leg' is not in $dist_set" >&2; exit 2; }

name="spec-kitty-event-producer"
case "$leg" in windows-*) name="$name.exe" ;; esac
binary="$project/launcher/build/native/nativeCompile/$name"
[ -f "$binary" ] || { echo "package-leg: no native binary at $binary" >&2; exit 2; }

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

# --- the measured floor against the pinned floor ---------------------------------------------------------------
floor_kind="none"; floor_pinned=""; floor_measured=""
case "$leg" in
  linux-*)
    floor_kind="glibc"
    floor_pinned=$(sed -E 's/^py3-none-manylinux_([0-9]+)_([0-9]+)_.*$/\1.\2/' <<<"$tag")
    floor_measured=$(objdump -T "$binary" | grep -o 'GLIBC_[0-9.]*' | sed 's/^GLIBC_//' | sort -V | tail -n 1 || true)
    ;;
  macos-*)
    floor_kind="minos"
    floor_pinned=$(sed -E 's/^py3-none-macosx_([0-9]+)_([0-9]+)_.*$/\1.\2/' <<<"$tag")
    floor_measured=$(otool -l "$binary" | awk '/cmd LC_BUILD_VERSION/ {f=1} f && $1 == "minos" {print $2; exit}')
    ;;
esac
if [ "$floor_kind" != "none" ]; then
  if [ -z "$floor_measured" ] || [ -z "$floor_pinned" ]; then
    echo "package-leg: FAIL: could not measure the $floor_kind floor (measured='$floor_measured' pinned='$floor_pinned')" >&2
    exit 1
  fi
  highest=$(printf '%s\n%s\n' "$floor_measured" "$floor_pinned" | sort -V | tail -n 1)
  if [ "$highest" != "$floor_pinned" ]; then
    echo "package-leg: FAIL: measured $floor_kind floor $floor_measured exceeds the pinned floor $floor_pinned (tag $tag)" >&2
    exit 1
  fi
  echo "package-leg: $floor_kind floor measured $floor_measured <= pinned $floor_pinned"
fi

# --- macOS: the binary is never stripped and must carry a valid (ad-hoc) signature ----------------------------------
case "$leg" in
  macos-*) codesign --verify --verbose=2 "$binary" ;;
esac

# --- outputs -------------------------------------------------------------------------------------------------------
mkdir -p "$out"
cp "$binary" "$out/$name"
sbom="$project/build/sbom/producer-sbom.json"
[ -f "$sbom" ] || { echo "package-leg: no SBOM at $sbom (run cyclonedxBom)" >&2; exit 2; }
cp "$sbom" "$out/producer-sbom.json"

toolchain=$(jq -r '[.tools[] | select(.kind == "jdk-archive")] | map(.sha256) | .[]' "$project/tools/pins.json" | grep -x "${GRAALVM_TOOLCHAIN_SHA256:-none}" || true)
[ -n "$toolchain" ] || { echo "package-leg: GRAALVM_TOOLCHAIN_SHA256 is not set to a pinned jdk-archive sha256 (run install-graalvm.sh first)" >&2; exit 2; }
commit=$(git -C "$root" rev-parse HEAD)

jq -n \
  --arg leg "$leg" --arg tag "$tag" --arg name "$name" --arg binary_sha256 "$(sha256_of "$out/$name")" \
  --arg toolchain_sha256 "$toolchain" --arg source_commit "$commit" \
  --arg kind "$floor_kind" --arg pinned "$floor_pinned" --arg measured "$floor_measured" \
  '{v: 1, leg: $leg, wheel_tag: $tag, binary: $name, binary_sha256: $binary_sha256, toolchain_sha256: $toolchain_sha256,
    source_commit: $source_commit, measured_floor: {kind: $kind, pinned: $pinned, measured: $measured}}' > "$out/producer-manifest.json"

"$here/check-sbom.sh" "$out/producer-sbom.json" "$out/producer-manifest.json" "$project/tools/pins.json"

# --- the start-time measurement (recorded always; a gate only when asked) ------------------------------------------
set +e
if [ -n "$enforce" ]; then
  "$here/measure-start.sh" "$out/$name" > "$out/producer-measure.json"
else
  "$here/measure-start.sh" "$out/$name" --max-median-ms 1000000 > "$out/producer-measure.json"
fi
status=$?
set -e
cat "$out/producer-measure.json"
[ "$status" -eq 0 ] || { echo "package-leg: FAIL: the warm start-time gate (median of the last 20 spawns) failed" >&2; exit 1; }

echo "package-leg: packaged $leg into $out"
