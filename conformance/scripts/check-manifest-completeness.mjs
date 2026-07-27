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
//
// Post-merge mission review (M1) findings remediated here:
// - HIGH-1: this script used to say nothing about whether a case's skillDir
//   actually resolves to a real, well-formed skill directory. Deleting
//   conformance/skills/control/name-mismatch/ entirely, or "fixing" its
//   frontmatter name to match its directory basename, both used to leave
//   this script (and muster's own harness -- see the catch-block defect
//   described below) at exit 0. This script now asserts, for every case,
//   that skillDir resolves to an existing directory containing a SKILL.md,
//   and, for the control case specifically, that its frontmatter `name`
//   still differs from its directory basename (the discriminating property
//   the control exists to exercise).
// - HIGH-2: this script used to classify tree membership by skillDir but
//   build its name set from `id`, never requiring the two to agree.
//   Repointing one case's skillDir at a different real skill directory left
//   this script at exit 0 while one real skill was validated twice and
//   another zero times. This script now asserts basename(skillDir) === id
//   for every skill-tree case, and derives the manifest-side name set from
//   the resolved skillDir's basename, never from the id field directly.
// - MEDIUM-5: readActualSkillNames() used to filter only on isDirectory(),
//   so an empty probe directory under src/doctrine/skills/ (no SKILL.md)
//   would count as a skill. FR-007's wording is "skill directories matching
//   src/doctrine/skills/*/SKILL.md" -- the directory scan now also requires
//   a SKILL.md to be present, coherent with (not duplicating) HIGH-1's
//   per-case SKILL.md assertion above.
//
// Independent verification of the above M1 fix found six further findings
// (PR #29 review round 2), three fixed here:
// - F-1 [BLOCKING]: checkControlCasesDiscriminate()'s HIGH-1 fix above only
//   compared frontmatterName === actualBasename. Since readFrontmatterName()
//   returns null for a hollowed-out control (no frontmatter, no `name:`
//   key, or an empty value), `null === actualBasename` was false, so a
//   control with NO name at all was scored the same as a control whose
//   name still genuinely differs -- the same failure class the HIGH-1 fix
//   exists to remediate. Null/empty is now its own error, checked first.
// - F-5: readFrontmatterName()'s regex captured a quoted value's quotes
//   along with the text, so a quoted-and-aligned name (`name:
//   "name-mismatch"`) did not trip the alignment check. Quotes are now
//   stripped before comparison.
// - F-6 [cosmetic]: checkSkillDirsResolve() never confirmed a resolved
//   skillDir was actually a directory; a skillDir pointing at a file was
//   misreported as "has no SKILL.md". Now reported accurately.
//
// Muster itself is out of scope for this fix (C-001, no muster change):
// this script cannot and does not change how `npx @garrison-hq/muster
// skills run` scores a missing/corrupt fixture -- it only adds an
// independent, fork-side assertion that catches what muster's own harness
// cannot distinguish.

import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { basename, dirname, join, relative } from "node:path";
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
// silently regress if a second stray non-skill file ever landed there. A
// directory only counts as a skill if it also contains a SKILL.md
// (MEDIUM-5) -- an empty probe directory is not a skill.
function readActualSkillNames() {
  const entries = readdirSync(SKILLS_DIR, { withFileTypes: true });
  return entries
    .filter((e) => e.isDirectory())
    .filter((e) => existsSync(join(SKILLS_DIR, e.name, "SKILL.md")))
    .map((e) => e.name);
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

// Extracts the YAML frontmatter `name:` value from a SKILL.md file. Plain
// text parse (same house style as readManifestCases above) -- no YAML
// parser, no npm dependency. Returns null if the file has no frontmatter or
// no `name:` key.
//
// F-5 (independent verification of this mission's own M1 fix): the previous
// version of this regex captured a quoted value's quotes along with the
// text, so `name: "name-mismatch"` was read as the literal string
// `"name-mismatch"` (quotes included), which never equals the bare
// directory basename `name-mismatch` -- the alignment check in
// checkControlCasesDiscriminate() would silently fail to fire for a quoted,
// aligned name. Strip one layer of matching surrounding single or double
// quotes before returning, same as real YAML scalar parsing would.
function readFrontmatterName(skillMdPath) {
  const text = readFileSync(skillMdPath, "utf8");
  const frontmatterMatch = text.match(/^---\r?\n([\s\S]*?)\r?\n---/);
  if (!frontmatterMatch) return null;
  const nameMatch = frontmatterMatch[1].match(/^name:\s*(\S+)\s*$/m);
  if (!nameMatch) return null;
  return nameMatch[1].replace(/^(['"])(.*)\1$/, "$2");
}

// Resolves every manifest case's skillDir to an absolute path and classifies
// it as either a skill-tree case (resolves one level directly under
// SKILLS_DIR) or a control case (everything else) -- purely structural,
// never inferred from the case's `id` string.
function resolveAndClassify(manifestCases) {
  const skillCases = [];
  const controlCases = [];
  for (const c of manifestCases) {
    if (!c.skillDir) {
      controlCases.push({ ...c, resolved: null });
      continue;
    }
    const resolved = join(dirname(MANIFEST_PATH), c.skillDir);
    const rel = relative(SKILLS_DIR, resolved);
    const isSkillTree = rel !== "" && !rel.startsWith("..") && !rel.includes("/");
    const entry = { ...c, resolved };
    if (isSkillTree) skillCases.push(entry);
    else controlCases.push(entry);
  }
  return { skillCases, controlCases };
}

// HIGH-1 (first assertion): every case's skillDir must resolve to an
// existing directory containing a SKILL.md. Runs for every case, skill-tree
// or control -- a missing/corrupt control fixture is exactly the failure
// mode muster's own harness cannot distinguish from "correctly detected a
// name mismatch" (see conformance/README.md).
function checkSkillDirsResolve(allCases) {
  const errors = [];
  const structurallyOk = new Set();
  for (const c of allCases) {
    if (!c.skillDir || !c.resolved) {
      errors.push(`case '${c.id}': manifest entry has no skillDir`);
      continue;
    }
    if (!existsSync(c.resolved)) {
      errors.push(
        `case '${c.id}': skillDir '${c.skillDir}' does not resolve to an existing directory`,
      );
      continue;
    }
    // F-6 (independent verification of this mission's own M1 fix): this
    // check never confirmed c.resolved was actually a directory. A skillDir
    // pointing at a file was previously caught by the SKILL.md-existence
    // check below and misreported as "has no SKILL.md" -- true in a
    // vacuous sense (a file has no children at all) but misleading about
    // the actual fault. Report the real problem directly.
    if (!statSync(c.resolved).isDirectory()) {
      errors.push(
        `case '${c.id}': skillDir '${c.skillDir}' resolves to a file, not a directory`,
      );
      continue;
    }
    const skillMdPath = join(c.resolved, "SKILL.md");
    if (!existsSync(skillMdPath)) {
      errors.push(`case '${c.id}': skillDir '${c.skillDir}' has no SKILL.md`);
      continue;
    }
    structurallyOk.add(c.id);
  }
  return { errors, structurallyOk };
}

// HIGH-2: every skill-tree case's id must equal the basename of the
// directory its skillDir resolves to. Without this, membership is
// classified by skillDir while the name set is built from id, and the two
// can silently diverge (e.g. one case's skillDir repointed at another
// skill's directory).
function checkSkillCaseIdsMatchBasename(skillCases) {
  const errors = [];
  for (const c of skillCases) {
    const actualBasename = basename(c.resolved);
    if (c.id !== actualBasename) {
      errors.push(
        `case '${c.id}': skillDir '${c.skillDir}' resolves to directory ` +
          `'${actualBasename}', which does not match the case id '${c.id}'`,
      );
    }
  }
  return errors;
}

// HIGH-1 (second assertion): for the control case(s) -- structurally, every
// case outside the skill tree -- the frontmatter `name` must still differ
// from the directory basename. If it doesn't, the control has been "fixed"
// (aligned) and no longer discriminates a genuine failure from "not there".
//
// F-1 (independent verification of this mission's own HIGH-1 fix -- the
// same failure class the fix exists to remediate): readFrontmatterName()
// returns null when the fixture has no frontmatter, no `name:` key, or an
// empty `name:` value. The equality test below is `frontmatterName ===
// actualBasename`; `null === 'name-mismatch'` is false, so a control that
// has been HOLLOWED OUT (frontmatter deleted, name key removed, or emptied)
// was previously scored as "still discriminating" -- i.e. treated exactly
// like a genuine, intact name mismatch, when in fact it can no longer
// discriminate anything at all. A null/empty frontmatter name is a strictly
// worse failure than a mismatched-but-present one: it must be reported as
// its own, explicit error, and reported BEFORE the equality test runs so
// the vacuous `null === basename` comparison is never reached.
function checkControlCasesDiscriminate(controlCases, structurallyOk) {
  const errors = [];
  if (controlCases.length !== CONTROL_CASE_COUNT) {
    errors.push(
      `expected exactly ${CONTROL_CASE_COUNT} control case(s) (skillDir outside src/doctrine/skills/), found ${controlCases.length}: ` +
        `${controlCases.map((c) => c.id).join(", ") || "(none)"}`,
    );
  }
  for (const c of controlCases) {
    if (!structurallyOk.has(c.id)) continue; // already reported above
    const actualBasename = basename(c.resolved);
    const skillMdPath = join(c.resolved, "SKILL.md");
    const frontmatterName = readFrontmatterName(skillMdPath);
    if (frontmatterName === null || frontmatterName.trim() === "") {
      errors.push(
        `control case '${c.id}': SKILL.md at '${skillMdPath}' has no parseable frontmatter ` +
          `'name:' value (missing frontmatter, missing 'name:' key, or an empty value) -- ` +
          `a control with no name cannot discriminate a genuine name-mismatch failure from ` +
          `a missing/corrupt fixture, which is a worse failure than a mismatched-but-present name`,
      );
      continue;
    }
    if (frontmatterName === actualBasename) {
      errors.push(
        `control case '${c.id}': frontmatter name '${frontmatterName}' now matches ` +
          `its directory basename '${actualBasename}' -- this control no longer ` +
          `discriminates a genuine name-mismatch failure from a missing/corrupt fixture`,
      );
    }
  }
  return errors;
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

  const { skillCases, controlCases } = resolveAndClassify(manifestCases);
  const allCases = [...skillCases, ...controlCases];

  const errors = [];
  const { errors: resolveErrors, structurallyOk } = checkSkillDirsResolve(allCases);
  errors.push(...resolveErrors);
  errors.push(...checkSkillCaseIdsMatchBasename(skillCases.filter((c) => structurallyOk.has(c.id))));
  errors.push(...checkControlCasesDiscriminate(controlCases, structurallyOk));

  if (errors.length > 0) {
    console.log("manifest completeness: MISMATCH");
    for (const e of errors) console.log(`  ${e}`);
    process.exit(1);
  }

  // HIGH-2: the name set is derived from the resolved skillDir's basename,
  // never from `id` directly -- id/basename agreement was already asserted
  // above, but the derivation itself must not silently fall back to id.
  const manifestSkillNames = skillCases.map((c) => basename(c.resolved));
  const manifestSkillSet = new Set(manifestSkillNames);

  const missing = actualSkillNames
    .filter((name) => !manifestSkillSet.has(name))
    .sort();
  const extra = manifestSkillNames
    .filter((name) => !actualSkillSet.has(name))
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
