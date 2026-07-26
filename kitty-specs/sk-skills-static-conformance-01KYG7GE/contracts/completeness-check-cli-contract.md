# Contract: `conformance/scripts/check-manifest-completeness.mjs` (FR-007)

**Mission**: `sk-skills-static-conformance-01KYG7GE` | **Date**: 2026-07-27

This is a CLI contract for a script this mission authors, not a muster
contract — it exists so the CI step in `.github/workflows/conformance.yml`
(lane-b) and the script's implementation (lane-a) can be built independently
without either lane reading the other's source, resolving the lane
straddle flagged when FR-007 was added (research.md §6).

---

## Invocation

```sh
node conformance/scripts/check-manifest-completeness.mjs
```

- **Working directory**: repository root. GitHub Actions' default
  `working-directory` after `actions/checkout` already satisfies this; local
  developers run it from the repo root per `conformance/README.md`.
- **Arguments**: none. All paths (`src/doctrine/skills/`,
  `conformance/skills/manifest.yaml`) are hardcoded relative to the current
  working directory — deliberately, since this script has exactly one job in
  exactly one repository layout, and an argument surface would be unused
  generality.
- **Environment variables**: none required. No network access, no
  credentials (matches C-002's offline-and-secret-free posture even though
  C-002 is written about the muster step specifically).

## Output

- **stdout, success** (exit `0`): a single confirmation line stating the
  matched count, e.g. `manifest completeness: OK (53 skills + 1 control = 54 cases)`.
- **stdout or stderr, failure** (exit `1`): a message that names every
  offending skill explicitly, in this shape (exact wording is an
  implementation choice; the *content* obligation below is the contract):
  ```
  manifest completeness: MISMATCH
    missing from manifest (present under src/doctrine/skills/, no case found): <name>[, <name>...]
    extra in manifest (case present, no matching src/doctrine/skills/<name> directory): <name>[, <name>...]
  ```
  If the mismatch is a pure count divergence with no name-level difference
  detectable (should not occur given the algorithm in research.md §6, but
  guarded defensively), the message still states the expected vs. actual
  counts rather than only "count" with no further detail.

## Exit codes

| Code | Meaning |
|---|---|
| `0` | Manifest is complete: static-case count == skill directory count + 1, and the two name sets match exactly. |
| `1` | Manifest is incomplete or over-complete: names the specific missing/extra skill(s). |
| (never `2`) | Reserved by muster's own CLI convention for internal/tooling errors (`src/cli/index.ts` exit-code contract, per `briefings/muster-github-action.md` §1) — this script deliberately does not reuse `2` for its own failures, so a `2` in this job's logs always means "muster itself errored," never "the completeness check found a problem." A genuine script bug (e.g. `src/doctrine/skills/` missing entirely) should still surface as a non-zero exit, but the script SHOULD attempt to distinguish "structural error reading the tree/manifest" from "counted mismatch" in its message text even where both currently exit `1`. |

## CI wiring (the lane-b side of this contract)

`.github/workflows/conformance.yml` adds exactly one step:

```yaml
- name: Verify manifest completeness (FR-007)
  run: node conformance/scripts/check-manifest-completeness.mjs
```

placed after the `garrison-hq/muster-action@v1` step. Lane-b's WP (workflow
authoring) needs nothing about the script's internals beyond this file:
invocation, working directory, and exit-code meaning. Lane-a's WP (script
authoring) needs nothing about the workflow beyond the same three facts, in
reverse. Neither lane's `write_scope` includes a file the other lane writes
(see `plan.md`'s Work-Package Outline).

## Non-goals

- This script does not validate the manifest's YAML shape against
  `skills-manifest-case.schema.json` — that is a distinct, deferred concern
  (a real schema-validation step would be a muster-side fix per FR-006's
  scope guard, not this script's job).
- This script does not inspect `expectations.ok`/`violations` values, skill
  frontmatter content, or anything muster's own `skills run` already checks
  — it checks exactly one thing: does the manifest's case *count and name
  set* match the actual skill directory tree, offset by the one known
  control case.
