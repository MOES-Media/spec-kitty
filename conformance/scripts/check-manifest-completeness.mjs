#!/usr/bin/env node
// Verifies that conformance/skills/manifest.yaml's type:static case count
// and name set exactly match the real src/doctrine/skills/* directory tree,
// offset by the one deliberately-broken FR-005 control case (see
// conformance/skills/manifest.yaml's control-name-mismatch entry).
//
// FR-007. Node stdlib only (fs, path) -- no npm dependency, no YAML parser.
// Contract: kitty-specs/sk-skills-static-conformance-01KYG7GE/contracts/
//           completeness-check-cli-contract.md
//
// Invocation: node conformance/scripts/check-manifest-completeness.mjs
// Working directory: repository root. No arguments, no env vars, no network.

import { readdirSync, readFileSync } from "node:fs";
import { dirname, join, relative } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = join(__dirname, "..", "..");
const SKILLS_DIR = join(REPO_ROOT, "src", "doctrine", "skills");
const MANIFEST_PATH = join(REPO_ROOT, "conformance", "skills", "manifest.yaml");

// The manifest carries exactly one non-skill-tree case: the FR-005
// discrimination control (control-name-mismatch, skillDir: control/...).
// It is deliberately excluded from the src/doctrine/skills/* comparison
// below and accounted for here as a named constant, never an inline magic
// number, per WP01's hard rule 5 / data-model.md's CompletenessCheckResult
// invariant.
const CONTROL_CASE_COUNT = 1;

// Step 1: read the real skill set. Filter by directory-entry TYPE, never by
// excluding a literal filename -- src/doctrine/skills/ also contains a
// plain README.md file, and excluding it by name (instead of by type) would
// silently regress if a second stray non-skill file ever landed there.
function readActualSkillNames() {
  const entries = readdirSync(SKILLS_DIR, { withFileTypes: true });
  return entries.filter((e) => e.isDirectory()).map((e) => e.name);
}

// Step 2: parse the manifest as plain text (no YAML parser -- see the
// schema's "$comment"). Cases are block-style YAML, one key per line, with
// `- id:` immediately followed by `type:`, `skillDir:`, `profile:`, and
// `expectations:` inside the same list item at a fixed indent. We only need
// the (id, skillDir) pairs, which appear as an `- id:` line followed a few
// lines later by a `skillDir:` line, before the next `- id:` line starts a
// new case.
function readManifestCases() {
  const text = readFileSync(MANIFEST_PATH, "utf8");
  const lines = text.split("\n");
  const cases = [];
  let current = null;
  for (const line of lines) {
    const idMatch = line.match(/^\s*- id:\s*(\S+)\s*$/);
    if (idMatch) {
      if (current) cases.push(current);
      current = { id: idMatch[1], skillDir: null };
      continue;
    }
    const skillDirMatch = line.match(/^\s*skillDir:\s*(\S+)\s*$/);
    if (skillDirMatch && current && current.skillDir === null) {
      current.skillDir = skillDirMatch[1];
    }
  }
  if (current) cases.push(current);
  return cases;
}

function main() {
  const actualSkillNames = readActualSkillNames();
  const actualSkillSet = new Set(actualSkillNames);

  const manifestCases = readManifestCases();
  // Every case this mission emits is `type: static` (behavioral cases are
  // out of scope), so the total case count IS the manifest's static-case
  // count -- this includes the one FR-005 ControlCase, per
  // data-model.md's CompletenessCheckResult (manifestStaticCaseCount ==
  // actualSkillCount + CONTROL_CASE_COUNT).
  const manifestStaticCaseCount = manifestCases.length;

  // For the name-set comparison (missing/extra) only, filter to cases whose
  // skillDir resolves under src/doctrine/skills/ -- this excludes the one
  // ControlCase (skillDir: control/name-mismatch, which resolves under
  // conformance/skills/control/, not src/doctrine/skills/).
  const manifestSkillCases = manifestCases.filter((c) => {
    if (!c.skillDir) return false;
    const resolved = join(dirname(MANIFEST_PATH), c.skillDir);
    const rel = relative(SKILLS_DIR, resolved);
    return rel !== "" && !rel.startsWith("..") && !rel.includes("/");
  });
  const manifestSkillSet = new Set(manifestSkillCases.map((c) => c.id));

  const missing = actualSkillNames
    .filter((name) => !manifestSkillSet.has(name))
    .sort();
  const extra = manifestSkillCases
    .map((c) => c.id)
    .filter((id) => !actualSkillSet.has(id))
    .sort();

  const countMatches =
    manifestStaticCaseCount === actualSkillNames.length + CONTROL_CASE_COUNT;
  const ok = countMatches && missing.length === 0 && extra.length === 0;

  if (ok) {
    console.log(
      `manifest completeness: OK (${actualSkillNames.length} skills + ${CONTROL_CASE_COUNT} control = ${actualSkillNames.length + CONTROL_CASE_COUNT} cases)`,
    );
    process.exit(0);
  }

  console.log("manifest completeness: MISMATCH");
  console.log(
    `  missing from manifest (present under src/doctrine/skills/, no case found): ${missing.length ? missing.join(", ") : "(none)"}`,
  );
  console.log(
    `  extra in manifest (case present, no matching src/doctrine/skills/<name> directory): ${extra.length ? extra.join(", ") : "(none)"}`,
  );
  if (missing.length === 0 && extra.length === 0 && !countMatches) {
    console.log(
      `  count mismatch with no name-level difference detected: expected ${actualSkillNames.length + CONTROL_CASE_COUNT} static+control cases (${actualSkillNames.length} skills + ${CONTROL_CASE_COUNT} control), found ${manifestStaticCaseCount} static cases in manifest`,
    );
  }
  process.exit(1);
}

main();
