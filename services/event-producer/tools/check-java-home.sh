#!/usr/bin/env bash
# Fail-closed JAVA_HOME checks for the event producer toolchain (single definition; install-graalvm.sh and event-producer.yml use it).
#
#   check-java-home.sh <dir>            exit 0 only when <dir> holds bin/java (or bin/java.exe) AND bin/native-image (or .cmd)
#   check-java-home.sh --find <tree>    print the one JDK root found under <tree>: the shallowest directory that passes the check above.
#                                       A GraalVM JDK also carries lib/svm/bin/native-image, which is NOT a JDK root; it has no bin/java.
#
# Every refusal prints JAVA_HOME_INVALID and exits 1.
set -euo pipefail

is_jdk_root() {
  local d="$1"
  [ -n "$d" ] && [ -d "$d" ] || return 1
  { [ -f "$d/bin/java" ] || [ -f "$d/bin/java.exe" ]; } && { [ -f "$d/bin/native-image" ] || [ -f "$d/bin/native-image.cmd" ]; }
}

invalid() { echo "check-java-home: JAVA_HOME_INVALID: $1" >&2; exit 1; }

for t in find awk dirname; do command -v "$t" >/dev/null 2>&1 || { echo "check-java-home: MISSING_TOOL $t" >&2; exit 2; }; done

if [ "${1-}" = "--find" ]; then
  tree="${2-}"
  [ -n "$tree" ] && [ -d "$tree" ] || invalid "no tree to search: '$tree'"
  best=""; best_depth=999
  while IFS= read -r launcher; do
    candidate="$(cd "$(dirname "$launcher")/.." && pwd)"
    is_jdk_root "$candidate" || continue
    depth=$(awk -F/ '{print NF}' <<<"$candidate")
    if [ "$depth" -lt "$best_depth" ]; then best="$candidate"; best_depth="$depth"; fi
  done < <(find "$tree" -maxdepth 6 \( -type f -o -type l \) \( -name java -o -name java.exe \) -path '*/bin/*')
  [ -n "$best" ] || invalid "no directory under $tree holds both bin/java and bin/native-image"
  echo "$best"
  exit 0
fi

dir="${1-}"
is_jdk_root "$dir" || invalid "'$dir' does not hold bin/java and bin/native-image"
