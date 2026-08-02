#!/usr/bin/env bash
# control-discrimination-guard.test.sh -- committed test for the
# "both controls failed as designed" assertion inside
# .github/workflows/behavioral.yml's `control-suite` job.
#
# Why extraction rather than a copy: the assertion under test lives inline in
# a workflow `run:` block, and a test that re-declared the same jq expression
# locally would pass forever while the committed workflow drifted away from
# it -- exactly the class of vacuous check this programme keeps finding. This
# file therefore reads the assertion's own bytes out of behavioral.yml
# (between two stable marker comments), dedents them, and executes them
# against committed report fixtures. If the markers are missing, or the
# extracted region does not contain the assertion, this test fails rather
# than silently testing nothing.
#
# The assertion under test closes this hole: before it existed, the step
# printed "both controls failed as designed" having only checked
# `muster_exit == 1` and `errored == 0`. One control silently passing while
# the other failed still yields exit 1 and zero errored runs, so the step
# accepted a half-broken discrimination control and printed a false message.
#
# Fixture provenance (no fabricated reports): control-both-failed.json is a
# byte-for-byte copy of this WP's own committed real-endpoint control report,
# conformance/behavioral/evidence/2026-08-02-01KYW5XK-control-healthy.json.
# control-one-passed.json and control-single-run.json are that same real
# report with one field group flipped by jq (verdict 0 forced to passed /
# both verdicts forced to a single run), so every other field keeps real
# muster @1.2.2 output shape.
#
# Usage: bash conformance/behavioral/scripts/control-discrimination-guard.test.sh
# Exits 0 if every case passes, 1 on any failing case.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
WORKFLOW="${REPO_ROOT}/.github/workflows/behavioral.yml"
FIXTURES="${SCRIPT_DIR}/fixtures"
BEGIN_MARKER="# >>> both-controls-failed assertion (extracted verbatim by control-discrimination-guard.test.sh)"
END_MARKER="# <<< both-controls-failed assertion"

failures=0
work_dir="$(mktemp -d)"
trap 'rm -rf "${work_dir}"' EXIT

if [ ! -f "${WORKFLOW}" ]; then
  echo "FAIL: workflow not found at ${WORKFLOW}"
  exit 1
fi

# Extract the marked region, strip the markers themselves, and dedent the
# YAML block scalar's 10-space indent.
snippet="${work_dir}/assertion.sh"
awk -v b="${BEGIN_MARKER}" -v e="${END_MARKER}" '
  index($0, b) { inside = 1; next }
  index($0, e) { inside = 0; next }
  inside { sub(/^ {10}/, ""); print }
' "${WORKFLOW}" > "${snippet}"

# Guard the extraction itself: an empty or assertion-free region would make
# every case below "pass" against a no-op script.
if ! grep -q 'both_failed=' "${snippet}"; then
  echo "FAIL: extraction -- no 'both_failed=' assignment found between the markers in ${WORKFLOW}"
  echo "      (extracted $(wc -l < "${snippet}") line(s); the markers are missing or the assertion was removed)"
  exit 1
fi
if ! grep -q 'exit 1' "${snippet}"; then
  echo "FAIL: extraction -- extracted region never exits nonzero; it cannot reject anything"
  exit 1
fi
echo "PASS: extraction -- pulled $(wc -l < "${snippet}") line(s) of the committed assertion out of behavioral.yml"

# run_assertion <fixture> -> echoes "<exit>|<stderr+stdout>"
# Reproduces the step's own shell state: `set +e` with `-u -o pipefail`
# (behavioral.yml's control-suite step disables -e deliberately), a `report`
# variable, and a writable GITHUB_OUTPUT.
run_assertion() {
  local fixture="$1"
  local harness="${work_dir}/harness.sh"
  {
    echo 'set +e'
    echo 'set -u -o pipefail'
    printf 'report=%q\n' "${FIXTURES}/${fixture}"
    echo 'GITHUB_OUTPUT="${WORK_GITHUB_OUTPUT}"'
    cat "${snippet}"
    echo 'exit 0'
  } > "${harness}"
  WORK_GITHUB_OUTPUT="${work_dir}/github_output" bash "${harness}" 2>&1
}

assert_case() {
  local label="$1" fixture="$2" expected_exit="$3" expected_substring="${4:-}"
  local output actual_exit

  : > "${work_dir}/github_output"
  set +e
  output="$(run_assertion "${fixture}")"
  actual_exit=$?
  set -e

  if [ "${actual_exit}" -ne "${expected_exit}" ]; then
    echo "FAIL: ${label} -- expected exit ${expected_exit}, got ${actual_exit} (output: '${output}')"
    failures=$((failures + 1))
    return
  fi

  if [ -n "${expected_substring}" ] && [[ "${output}" != *"${expected_substring}"* ]]; then
    echo "FAIL: ${label} -- expected output to contain '${expected_substring}', got '${output}'"
    failures=$((failures + 1))
    return
  fi

  echo "PASS: ${label} (exit ${actual_exit})"
}

# assert_reports_report_file <label> <fixture>
# Every failing exit path in this step must write report_file to
# GITHUB_OUTPUT before exiting, or the evidence-writing step's
# `if: always() && steps.control_suite.outputs.report_file != ''` guard skips
# and the failing run leaves no evidence behind at all.
assert_reports_report_file() {
  local label="$1" fixture="$2"

  : > "${work_dir}/github_output"
  set +e
  run_assertion "${fixture}" >/dev/null
  set -e

  if ! grep -q "^report_file=${FIXTURES}/${fixture}\$" "${work_dir}/github_output"; then
    echo "FAIL: ${label} -- report_file was not written to GITHUB_OUTPUT (got: '$(cat "${work_dir}/github_output")')"
    failures=$((failures + 1))
    return
  fi

  echo "PASS: ${label}"
}

# Case 1 (the healthy case): the real committed control report -- both
# controls failed as designed, 3 runs each. Must be accepted, exit 0.
assert_case "both controls failed, 3 runs each -> accepted, exit 0" \
  "control-both-failed.json" 0

# Case 2 (THE rejection case this assertion exists for): exactly one control
# passed. muster still exits 1 and errored is still 0, so every pre-existing
# guard in the step accepts this report; only this assertion rejects it.
assert_case "exactly one control passed -> rejected, exit 1" \
  "control-one-passed.json" 1 "got 1"

assert_reports_report_file "one control passed -> report_file written to GITHUB_OUTPUT" \
  "control-one-passed.json"

# Case 3: both controls failed but on a single run each. A 1-run control
# cannot support pass^k discrimination; totalRuns >= 3 is the floor the
# assertion enforces alongside the count.
assert_case "both failed but only 1 run each -> rejected, exit 1" \
  "control-single-run.json" 1 "got 0"

# Case 4: report has no `verdicts` key at all. jq errors; the assertion must
# reject on the resulting empty VALUE, not on jq's coincidental exit status.
assert_case "report missing verdicts key -> rejected, exit 1" \
  "control-no-verdicts-key.json" 1 "<unreadable>"

assert_reports_report_file "missing verdicts key -> report_file written to GITHUB_OUTPUT" \
  "control-no-verdicts-key.json"

# Case 5: unreadable report path (file does not exist). Same fail-closed
# requirement -- jq's stderr is suppressed, so the empty value must drive it.
assert_case "nonexistent report path -> rejected, exit 1" \
  "control-does-not-exist.json" 1 "<unreadable>"

# Case 6: a report whose verdicts are a dead-endpoint run. Both controls
# "failed", but every run errored -- this assertion is NOT the guard for that
# (errored > 0 is, later in the step), so it must not spuriously reject here;
# a false rejection would mask the real, more specific diagnostic.
assert_case "dead-endpoint control report -> this assertion stays silent, exit 0" \
  "control-dead-endpoint.json" 0

if [ "${failures}" -gt 0 ]; then
  echo "control-discrimination-guard.test.sh: ${failures} case(s) failed"
  exit 1
fi

echo "control-discrimination-guard.test.sh: all cases passed"
