#!/usr/bin/env node
// Verifies FR-004's discrimination-control assertion against the RAW
// `muster skills run <manifest> --json` report (the `SkillsRunResult`
// shape muster's CLI itself writes -- `results: SkillsCaseResult[]`,
// src/cli/index.ts:1293, muster commit 16f0d34c3126fab5df2ee0b6e1e304a4d9bcb8e3,
// tag v1.2.1). This script reads that raw report directly, never the
// summarized evidence artifact this mission's cadence workflow produces
// separately, because it must run BEFORE the evidence-summarization step
// exists in a given invocation.
//
// The central correctness question this script exists to answer:
// `passed: false` is true in BOTH a healthy, well-discriminating run AND a
// fully dead-endpoint run. The rigged-impossible control's queries are
// ordinary, unrelated requests that a healthy model never routes to the
// rigged tool, so a HEALTHY run also drives `runsTriggered=0` for both
// axes: should-trigger fails (`triggerRate 0 < threshold`, needs `>=` to
// pass) and near-miss passes (`triggerRate 0 < threshold`, needs `<` to
// pass), so `TriggerVerdict.passed` (both axes must pass) is false. A DEAD
// endpoint drives `runsTriggered=0` for the exact same reason (every call
// throws before it can trigger anything), producing the identical
// `passed: false` shape by construction (garrison-hq/muster#76) -- the
// only field that tells the two conditions apart is the DERIVED sum of
// `runsErrored` across every query in both axes (zero for the healthy
// case; equal to `runsPerQuery * totalQueries` for the fully dead case) --
// NOT a field muster's JSON output carries directly. `SkillsCaseResult`
// (src/cli/index.ts:1268)
// has only a case-level `errored?: boolean` for STRUCTURAL failures (a bad
// skillDir/querySetPath) -- never set by a network failure, because
// `runSingleQuery`'s own per-call try/catch (trigger.ts:280-291) swallows
// that first and increments `runsErrored` on the query itself. The real
// counts live two levels deep:
//
//   runsErrored(case) =
//     sum(case.shouldTriggerAxis.queryBreakdown[].runsErrored) +
//     sum(case.nearMissAxis.queryBreakdown[].runsErrored)
//
// (`QueryRunResult`/`AxisVerdict`, src/adapters/skills/types.ts:165/:177,
// same SHA). Any implementation that reads `passed` alone, without this
// derived sum, would incorrectly accept a dead-endpoint report as a valid
// healthy-mode proof -- this is the exact bug this script exists to
// prevent (data-model.md "Trigger Verdict").
//
// Scope note this script's own README/handoff text must not overstate:
// `runBehavioralSkillCase` (src/cli/index.ts:1414-1465) builds exactly ONE
// tool per case -- the target skill only (a fixed-length-1 array literal,
// confirmed against research.md §3 and WP01's own review of the live
// pipeline). The near-miss axis therefore asks "does the model decline to
// call the one tool it was given?", never "does tool A win over tool B in
// a multi-tool choice?" -- this script grades an aggregate rate over that
// single-tool axis, never per-query correctness against a distractor, and
// nothing in this script's output should be read as claiming otherwise.
//
// Node stdlib only (fs). No YAML/JSON schema library dependency.
//
// Contract: kitty-specs/skill-trigger-routing-suite-01KYVRB9/contracts/
//           verification-scripts-cli-contract.md, section 4.
//
// Invocation:
//   node conformance/scripts/check-control-discrimination.mjs <report.json> --mode healthy
//   node conformance/scripts/check-control-discrimination.mjs <report.json> --mode dead-endpoint
//
// Exit 0: the report's sole isControl:true case matches the given mode's
//   expectation.
// Exit 1: the report was read and the control case was found, but its
//   observed shape does not match the given mode's expectation -- naming
//   which condition failed (this is the FR-004 both-condition sequencing
//   itself: cross-wiring the mode against the wrong condition's data must
//   land here, never exit 0).
// Exit 2: the script could not run its check at all -- missing/invalid CLI
//   arguments (including an omitted --mode: NEVER a silent default to
//   either mode), an unreadable/unparseable report file, zero or more than
//   one isControl:true case, or a control case with no axis data to sum
//   (e.g. still `skipped: true` -- MUSTER_ENDPOINT was never configured for
//   this run, so there is no discrimination proof to grade at all).

import { readFileSync } from "node:fs";

const VALID_MODES = new Set(["healthy", "dead-endpoint"]);

function usageError(message) {
  console.log("control-discrimination: ERROR");
  console.log(`  ${message}`);
  console.log(
    "  usage: check-control-discrimination.mjs <report.json> --mode healthy|dead-endpoint",
  );
  process.exit(2);
}

/** Splits argv into the positional report path and the --mode flag's value (if given). */
function parseArgs(argv) {
  const positional = [];
  let mode;
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === "--mode") {
      mode = argv[i + 1];
      i++;
    } else {
      positional.push(argv[i]);
    }
  }
  return { reportPath: positional[0], mode };
}

/** Reads and JSON-parses the raw muster --json report, exiting 2 on any failure. */
function readReport(reportPath) {
  let text;
  try {
    text = readFileSync(reportPath, "utf8");
  } catch (error) {
    usageError(`cannot read report file '${reportPath}' (${error.message})`);
  }
  try {
    return JSON.parse(text);
  } catch (error) {
    usageError(`report file '${reportPath}' is not valid JSON (${error.message})`);
    return undefined;
  }
}

/** Finds the sole isControl:true case in the report's results array, exiting 2 if not exactly one. */
function findControlCase(report) {
  if (!report || !Array.isArray(report.results)) {
    usageError("report JSON has no 'results' array (not a muster skills-run --json report?)");
    return undefined;
  }
  const controls = report.results.filter((r) => r.isControl === true);
  if (controls.length !== 1) {
    usageError(
      `expected exactly 1 isControl:true case in the report, found ${controls.length} ` +
        `(this is a manifest-authoring bug, not a discrimination-control finding)`,
    );
    return undefined;
  }
  return controls[0];
}

/** Sums runsErrored across one axis's queryBreakdown; returns null if the axis shape is absent/invalid. */
function sumAxisRunsErrored(axis) {
  if (!axis || !Array.isArray(axis.queryBreakdown)) return null;
  return axis.queryBreakdown.reduce((sum, q) => sum + (q.runsErrored ?? 0), 0);
}

/** Computes the derived runsErrored(case) sum, exiting 2 if either axis is structurally absent. */
function computeRunsErrored(controlCase) {
  const shouldTriggerErrored = sumAxisRunsErrored(controlCase.shouldTriggerAxis);
  const nearMissErrored = sumAxisRunsErrored(controlCase.nearMissAxis);
  if (shouldTriggerErrored === null || nearMissErrored === null) {
    usageError(
      `control case '${controlCase.id}' has no shouldTriggerAxis/nearMissAxis queryBreakdown ` +
        `(skipped:${controlCase.skipped === true} -- likely no MUSTER_ENDPOINT configured for this ` +
        `run) -- cannot compute runsErrored; this report is not a valid discrimination proof for either mode`,
    );
    return undefined;
  }
  return shouldTriggerErrored + nearMissErrored;
}

/** Applies the --mode healthy|dead-endpoint assertion. Returns an error string, or null if satisfied. */
function checkMode(mode, controlCase, runsErrored) {
  if (controlCase.passed === true) {
    return "passed=true -- the model unexpectedly invoked the rigged tool";
  }
  if (mode === "healthy" && runsErrored !== 0) {
    return `runsErrored=${runsErrored}, expected 0 -- endpoint may be unhealthy`;
  }
  if (mode === "dead-endpoint" && runsErrored === 0) {
    return "runsErrored=0 -- endpoint did not actually fail, this is not a valid dead-endpoint proof run";
  }
  return null;
}

function main() {
  const { reportPath, mode } = parseArgs(process.argv.slice(2));

  if (!reportPath) {
    usageError("missing required <report.json> argument");
  }
  if (mode === undefined) {
    usageError("missing required --mode flag (never silently defaulted to either mode)");
  }
  if (!VALID_MODES.has(mode)) {
    usageError(`invalid --mode '${mode}' (must be 'healthy' or 'dead-endpoint')`);
  }

  const report = readReport(reportPath);
  const controlCase = findControlCase(report);
  const runsErrored = computeRunsErrored(controlCase);

  const failure = checkMode(mode, controlCase, runsErrored);
  if (failure) {
    console.log(`control-discrimination (--mode ${mode}): FAIL`);
    console.log(`  ${failure}`);
    process.exit(1);
  }

  console.log(
    `control-discrimination (--mode ${mode}): OK (case '${controlCase.id}', ` +
      `passed=${controlCase.passed}, runsErrored=${runsErrored})`,
  );
  process.exit(0);
}

main();
