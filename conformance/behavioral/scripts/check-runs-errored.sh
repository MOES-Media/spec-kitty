#!/usr/bin/env bash
# check-runs-errored.sh -- FR-007's runsErrored computation, packaged once so
# local falsification runs and the cadence workflow (.github/workflows/
# behavioral.yml) never duplicate the inline jq logic and drift apart.
#
# muster's own exit code and `report.passed` are identical between "the
# control correctly fired" and "the endpoint was unreachable" (both are
# exit 1 / passed: false). The only field that distinguishes them is the
# per-run error, nested at verdicts[].runs[].error -- there is no top-level
# SOPSuiteReport.runsErrored convenience field (confirmed against
# src/adapters/openclaw-sop/manifest.ts:156-192, @garrison-hq/muster@1.2.2).
#
# Usage: check-runs-errored.sh <report.json>
# Prints the count of runs across every verdict whose `error` field is set
# (non-null) to stdout. Exit 0 on success; exit 1 if the report path is
# missing or unreadable; exit 2 if `jq` is not installed.
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

jq '[.verdicts[].runs[] | select(.error != null)] | length' "${report_path}"
