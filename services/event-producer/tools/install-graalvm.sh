#!/usr/bin/env bash
# Install the pinned GraalVM Community JDK for one pins.json entry into a directory, verifying the sha256 BEFORE anything is unpacked,
# and export JAVA_HOME (and PATH) to later workflow steps. Single definition used by every job of event-producer.yml that needs the toolchain.
#
#   install-graalvm.sh <pins-tool-name> <destination-dir> [pins.json]
#
# <pins-tool-name> is a jdk-archive entry of pins.json (graalvm-jdk-linux-x64, graalvm-jdk-macos-aarch64, graalvm-jdk-windows-x64).
# Exits non-zero on a checksum mismatch, an unknown entry, a missing tool or a native-image whose version differs from the pin.
set -euo pipefail

name="${1:?pins tool name}"
dest="${2:?destination directory}"
pins="${3:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/pins.json}"

command -v jq >/dev/null 2>&1 || { echo "install-graalvm: jq is required" >&2; exit 2; }
entry=$(jq -c --arg n "$name" '[.tools[] | select(.name == $n and .kind == "jdk-archive")][0] // empty' "$pins")
[ -n "$entry" ] || { echo "install-graalvm: no jdk-archive entry named '$name' in $pins" >&2; exit 2; }
url=$(jq -r .url <<<"$entry")
want=$(jq -r .sha256 <<<"$entry")
version=$(jq -r .version <<<"$entry")
case "$url" in https://*) ;; *) echo "install-graalvm: refusing a non-https url: $url" >&2; exit 2 ;; esac

mkdir -p "$dest"
archive="$dest/graalvm-archive"
curl --fail --silent --show-error --location --retry 3 --output "$archive" "$url"

# Hash from stdin: sha256sum given a PATH that holds a backslash (a Windows RUNNER_TEMP) prefixes the digest with a backslash.
if command -v sha256sum >/dev/null 2>&1; then
  got=$(sha256sum <"$archive" | cut -d' ' -f1)
else
  got=$(shasum -a 256 <"$archive" | cut -d' ' -f1)
fi
if [ "$got" != "$want" ]; then
  echo "install-graalvm: CHECKSUM_MISMATCH for $name: pinned $want but downloaded $got" >&2
  exit 1
fi

case "$url" in
  *.zip) 7z x -y "-o$dest/unpacked" "$archive" >/dev/null ;;
  *.tar.gz) mkdir -p "$dest/unpacked" && tar -xzf "$archive" -C "$dest/unpacked" ;;
  *) echo "install-graalvm: unsupported archive format: $url" >&2; exit 2 ;;
esac
rm -f "$archive"

# The archive holds one top directory; macOS nests the JDK under Contents/Home. The JDK root is the directory holding bin/java AND
# bin/native-image: lib/svm/bin/native-image also exists but is not a JDK root. check-java-home.sh fails closed.
tools="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
java_home=$("$tools/check-java-home.sh" --find "$dest/unpacked")
"$tools/check-java-home.sh" "$java_home"

if command -v cygpath >/dev/null 2>&1; then java_home=$(cygpath -m "$java_home"); fi
# Pin check: the toolchain must be the pinned release.
reported=$("$java_home/bin/native-image" --version 2>&1 | head -n 3)
pinned_number="${version#jdk-}"
case "$reported" in
  *"$pinned_number"*) ;;
  *) echo "install-graalvm: native-image --version does not report the pinned $pinned_number: $reported" >&2; exit 1 ;;
esac

echo "install-graalvm: $name $version sha256=$want JAVA_HOME=$java_home"
if [ -n "${GITHUB_ENV:-}" ]; then
  echo "JAVA_HOME=$java_home" >> "$GITHUB_ENV"
  echo "GRAALVM_TOOLCHAIN_SHA256=$want" >> "$GITHUB_ENV"
fi
if [ -n "${GITHUB_PATH:-}" ]; then echo "$java_home/bin" >> "$GITHUB_PATH"; fi
