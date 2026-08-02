#!/usr/bin/env bash
# check-runs-errored.sh -- FR-007's runsErrored computation (T010, RED).
#
# RED per CHTR-011: this version counts case-level failures
# (`verdicts[].passed == false`), not per-run errors. That does not
# distinguish "the control correctly fired" from "the endpoint was dead" --
# a genuinely non-compliant-but-reachable case has `passed: false` too, so
# this version reports a nonzero count for BOTH the healthy-endpoint control
# run (which has zero real errors) and the dead-endpoint run, laundering a
# dead endpoint through as if it were a legitimate discrimination proof.
# Replaced by the GREEN version below, which walks the nested
# verdicts[].runs[].error field instead.
#
# Usage: check-runs-errored.sh <report.json>
set -euo pipefail

report_path="${1:-}"
if [ -z "${report_path}" ] || [ ! -f "${report_path}" ]; then
  echo "check-runs-errored.sh: usage: check-runs-errored.sh <report.json>" >&2
  exit 1
fi

jq '[.verdicts[] | select(.passed == false)] | length' "${report_path}"
