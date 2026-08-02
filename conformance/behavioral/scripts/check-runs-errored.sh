#!/usr/bin/env bash
# check-runs-errored.sh -- FR-007's runsErrored computation, packaged once so
# local falsification runs and the cadence workflow (.github/workflows/
# behavioral.yml) never duplicate the inline jq logic and drift apart.
#
# muster's own exit code and `report.passed` are identical between "the
# control correctly fired" and "the endpoint was unreachable" (both are
# exit 1 / passed: false). The per-run error, nested at verdicts[].runs[].
# error, distinguishes those two conditions from each other -- there is no
# top-level SOPSuiteReport.runsErrored convenience field (confirmed against
# src/adapters/openclaw-sop/manifest.ts:156-192, @garrison-hq/muster@1.2.2).
#
# F8 precision (an earlier commit, ca28c71b1, overstated this in both
# directions -- restated here precisely, since this claim is cited
# downstream): the runs[].error walk is not the *only* field capable of
# distinguishing "correctly fired" from "endpoint unreachable" -- measured
# against this WP's own committed evidence
# (conformance/behavioral/evidence/2026-08-02-01KYW5XK-control-*.json), the
# healthy run has 9 grades across 6 runs and 12 transcript entries, while
# both the dead-endpoint and stripped-env runs have 0 grades and 6
# transcript entries; `[.verdicts[].runs[] | select((.grades|length)==0)] |
# length` gives 0 vs 6, a second, independent discriminator this script
# does not compute. Conversely, this script's own count does NOT separate
# a dead endpoint from a stripped/unset one: both conditions produce
# runsErrored == 6 in this WP's captured evidence; only the `runs[].error`
# message string differs ("chat request to ... failed: fetch failed" vs
# "muster sop: MUSTER_ENDPOINT not set ..."). What this script's count
# reliably distinguishes is exit code / report.passed / passCount, none of
# which differ between a healthy control run and either broken-endpoint
# condition -- not dead from stripped, and not the sole possible signal.
#
# Usage: check-runs-errored.sh <report.json>
# Prints the count of runs across every verdict whose `error` field is set
# (non-null) to stdout.
#
# Exit code contract (F9: aligned with measured behavior, not aspirational):
#   0  success -- count printed.
#   1  usage error -- the report path argument is missing, or the path does
#      not exist / is not a regular file.
#   2  `jq` is not installed on PATH.
#   3  the report exists but is not valid JSON, including a zero-byte file
#      (F3: fail-closed check via `jq empty`). Before this check existed, a
#      zero-byte report -- the shape produced when `muster sop run` exits 1
#      having written nothing -- made `jq` print nothing and exit 0, which
#      a caller could misread as "0 errored runs" on a report that in fact
#      never ran at all.
#   5  the report is valid JSON but does not have the expected
#      `verdicts[].runs[]` shape (e.g. `verdicts` absent, or a verdict has
#      no `runs` array) -- this is jq's own runtime-error exit code,
#      propagated unchanged rather than reinterpreted.
#
# Pin note: this script has no muster invocation of its own -- it only reads
# a `--json` report already produced by `muster sop run`. Callers must pin
# @garrison-hq/muster@1.2.2 (never @1.2.1, which has a live pass-k/k-of-n
# judge-threshold defect fixed by garrison-hq/muster#89, commit db80a4295)
# when producing that report.
set -euo pipefail

if ! command -v jq >/dev/null 2>&1; then
  echo "check-runs-errored.sh: jq is required but not found on PATH" >&2
  exit 2
fi

report_path="${1:-}"
if [ -z "${report_path}" ] || [ ! -f "${report_path}" ]; then
  echo "check-runs-errored.sh: usage: check-runs-errored.sh <report.json>" >&2
  exit 1
fi

# F3: fail closed on a report that is not valid JSON at all. A zero-byte
# file -- the shape muster leaves behind when `sop run` exits 1 before
# writing any output -- is the case that most needs this: `jq empty` alone
# treats zero documents as vacuously valid and exits 0 on an empty file, so
# the emptiness check must come first.
if [ ! -s "${report_path}" ] || ! jq empty "${report_path}" 2>/dev/null; then
  echo "check-runs-errored.sh: ${report_path} is not valid JSON (zero-byte or malformed report) -- refusing to report a count" >&2
  exit 3
fi

jq '[.verdicts[].runs[] | select(.error != null)] | length' "${report_path}"
