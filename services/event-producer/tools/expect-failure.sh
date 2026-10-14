#!/usr/bin/env bash
# Negative controls of the event producer build (WP01 T003): each step PLANTS a defect and passes only when the build goes red for
# the right reason (the gate's own message in the output, not just any red). A green planted run, or a red for another reason, fails.
#
#   expect-failure.sh [floor|tamper|module|sbom|javahome|all]     (default: all)
#
# Run from anywhere; it works on services/event-producer. GRADLE overrides the launcher (default: the committed wrapper).
set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
project="$(cd "$here/.." && pwd)"
gradle="${GRADLE:-$project/gradlew}"
mode="${1:-all}"
failures=0
steps=0

log="$(mktemp)"
scratch=""
trap 'rm -f "$log"; [ -z "$scratch" ] || rm -rf "$scratch"' EXIT

pass() { steps=$((steps + 1)); echo "PASS: $1"; }
fail() { steps=$((steps + 1)); failures=$((failures + 1)); echo "FAIL: $1"; }

# expect_green <label> <command...>
expect_green() {
  local label="$1"; shift
  if "$@" >"$log" 2>&1; then pass "$label"; else fail "$label (build was red)"; tail -20 "$log"; fi
}

# expect_red <label> <required output text> <command...>
expect_red() {
  local label="$1" needle="$2"; shift 2
  if "$@" >"$log" 2>&1; then
    fail "$label (the planted defect was accepted: build green)"
  elif grep -qF -- "$needle" "$log"; then
    pass "$label"
  else
    fail "$label (red, but without '$needle' in the output)"; tail -20 "$log"
  fi
}

floor() {
  # Positive control first: the probe has code and a test, so it measures something.
  expect_green "floor probe measures and passes at its own floor" "$gradle" --no-daemon -p "$here/floor-probe" check
  expect_red "coverage floor breach (genuine shortfall) goes red" "lines covered ratio is 0.50, but expected minimum is 0.51" \
    "$gradle" --no-daemon -p "$here/floor-probe" check -PjacocoLineFloor=0.51
  expect_red "coverage floor breach (jacocoLineFloor=1.01) goes red" "Rule violated" \
    "$gradle" --no-daemon -p "$here/floor-probe" check -PjacocoLineFloor=1.01
  expect_red "an unparseable floor fails closed" "jacoco-floors: the line floor is not a decimal ratio" \
    "$gradle" --no-daemon -p "$here/floor-probe" check -PjacocoLineFloor=high
}

tamper() {
  # A planted checksum change must fail dependency verification. Run against a COPY; the committed file is never touched.
  scratch="$(mktemp -d)"
  mkdir -p "$scratch/ep"
  (cd "$project" && tar --exclude=build --exclude=.gradle --exclude='*/build' -cf - .) | (cd "$scratch/ep" && tar -xf -)
  local metadata="$scratch/ep/gradle/verification-metadata.xml"
  if ! grep -q '<sha256 value="' "$metadata"; then fail "tamper: no checksum to plant in $metadata"; return; fi
  # Replace the first 8 hex digits of every recorded checksum with 00000000.
  sed -i.bak -E 's/(<sha256 value=")[0-9a-f]{8}/\100000000/' "$metadata" && rm -f "$metadata.bak"
  expect_red "tampered verification metadata fails the build" "Dependency verification failed" \
    "$scratch/ep/gradlew" --no-daemon -p "$scratch/ep" :adapter:compileJava
  rm -rf "$scratch"; scratch=""
}

module() {
  expect_green "module dependency rules hold on the real build" "$gradle" --no-daemon -p "$project" verifyModuleDependencyRules
  expect_red "planted :adapter main -> :domain dependency goes red" "verifyModuleDependencyRules VIOLATION" \
    "$gradle" --no-daemon -p "$project" verifyModuleDependencyRules -PplantAdapterDomainMain=true
}

sbom() {
  scratch="$(mktemp -d)"
  local pins="$here/pins.json"
  local toolchain
  toolchain="$(jq -r '[.tools[] | select(.kind == "jdk-archive")][0].sha256' "$pins")"
  printf '{"components":[{"name":"jackson-core","version":"3.2.1"}]}\n' > "$scratch/good-sbom.json"
  printf '{"toolchain_sha256":"%s"}\n' "$toolchain" > "$scratch/good-manifest.json"
  printf '{"components":[{"name":"something-else","version":"1"}]}\n' > "$scratch/no-dep-sbom.json"
  printf '{"toolchain_sha256":"0000000000000000000000000000000000000000000000000000000000000000"}\n' > "$scratch/no-toolchain-manifest.json"
  printf '{"components":[]}\n' > "$scratch/empty-sbom.json"
  expect_green "SBOM check passes on a good SBOM and manifest (positive control)" "$here/check-sbom.sh" "$scratch/good-sbom.json" "$scratch/good-manifest.json" "$pins"
  expect_red "planted SBOM without a pinned dependency fails" "names no versioned jackson-core component" \
    "$here/check-sbom.sh" "$scratch/no-dep-sbom.json" "$scratch/good-manifest.json" "$pins"
  expect_red "planted empty SBOM fails" "names no versioned jackson-core component" \
    "$here/check-sbom.sh" "$scratch/empty-sbom.json" "$scratch/good-manifest.json" "$pins"
  expect_red "planted manifest without the embedded toolchain fails" "is not a pinned jdk-archive sha256" \
    "$here/check-sbom.sh" "$scratch/good-sbom.json" "$scratch/no-toolchain-manifest.json" "$pins"
  rm -rf "$scratch"; scratch=""
}

javahome() {
  # JAVA_HOME must be the directory holding bin/java (WP01 CI finding: the install script once took lib/svm, a directory that also has
  # bin/native-image, and the manylinux container mounted a path without bin/java). Plants an unpacked JDK that carries that decoy.
  local check="$here/check-java-home.sh" jdk
  scratch="$(mktemp -d)"
  jdk="$scratch/unpacked/graalvm-test-jdk"
  mkdir -p "$jdk/bin" "$jdk/lib/svm/bin"
  : > "$jdk/bin/java"; : > "$jdk/bin/native-image"; : > "$jdk/lib/svm/bin/native-image"
  chmod +x "$jdk/bin/java" "$jdk/bin/native-image" "$jdk/lib/svm/bin/native-image"
  expect_green "JAVA_HOME with bin/java and bin/native-image is accepted (positive control)" "$check" "$jdk"
  expect_red "JAVA_HOME lib/svm (a native-image but no bin/java) is refused" "JAVA_HOME_INVALID" "$check" "$jdk/lib/svm"
  expect_red "a JAVA_HOME that does not exist is refused" "JAVA_HOME_INVALID" "$check" "$scratch/absent"
  expect_red "an empty JAVA_HOME value is refused" "JAVA_HOME_INVALID" "$check" ""
  if [ "$("$check" --find "$scratch/unpacked" 2>"$log")" = "$jdk" ]; then pass "detection picks the JDK root, not the lib/svm decoy"; else fail "detection did not pick the JDK root"; cat "$log"; fi
  expect_red "detection in a tree without bin/java is refused" "JAVA_HOME_INVALID" "$check" --find "$scratch/unpacked/graalvm-test-jdk/lib"
  rm -rf "$scratch"; scratch=""
}

floorgate() {
  # The measured-floor gate of package-leg.sh must go red when the pinned floor is tighter than the binary. Needs a built binary and
  # the toolchain checksum (event-producer.yml runs it after the leg is built). Windows has no floor: nothing to plant.
  local leg="${LEG:?set LEG to the leg built on this runner}"
  case "$leg" in
    windows-*) pass "floor gate: windows has no pinned floor, nothing to plant"; return ;;
    linux-*) old="py3-none-manylinux_2_28_x86_64"; planted="py3-none-manylinux_2_10_x86_64" ;;
    macos-*) old="py3-none-macosx_11_0_arm64"; planted="py3-none-macosx_10_9_arm64" ;;
    *) fail "floor gate: unknown leg $leg"; return ;;
  esac
  scratch="$(mktemp -d)"
  sed "s/$old/$planted/" "$project/../../scripts/release/dist-set.json" > "$scratch/dist-set.json"
  grep -q "$planted" "$scratch/dist-set.json" || { fail "floor gate: could not plant a tighter floor"; return; }
  DIST_SET="$scratch/dist-set.json" expect_red "a binary above the pinned floor is refused" "exceeds the pinned floor" \
    "$here/package-leg.sh" "$leg" "$scratch/out"
  rm -rf "$scratch"; scratch=""
}

case "$mode" in
  floorgate) floorgate ;;
  floor) floor ;;
  tamper) tamper ;;
  module) module ;;
  sbom) sbom ;;
  javahome) javahome ;;
  all) floor; tamper; module; sbom; javahome ;;
  *) echo "expect-failure: unknown step '$mode'" >&2; exit 2 ;;
esac

echo "expect-failure: steps=$steps failures=$failures"
[ "$steps" -gt 0 ] && [ "$failures" -eq 0 ]
