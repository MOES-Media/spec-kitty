---
work_package_id: WP08
title: 'Packaging remainder: dist-set check, exact-install check, fixtures and packaging tests'
dependencies:
- WP01
requirement_refs:
- FR-041
- FR-042
- FR-043
- FR-044
planning_base_branch: feat/event-producer-reroute-6185
merge_target_branch: feat/event-producer-reroute-6185
branch_strategy: Planning artifacts for this mission were generated on feat/event-producer-reroute-6185. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into feat/event-producer-reroute-6185 unless the human explicitly redirects the landing branch.
subtasks:
- T052
- T053
- T054
- T055
- T056
- T057
- T082
history: []
agent_profile: python-pedro
authoritative_surface: scripts/release/
create_intent:
- scripts/release/check_dist_set.py
- tests/release/test_check_dist_set.py
- tests/cross_cutting/packaging/test_producer_wheel_contents.py
execution_mode: code_change
model: ''
owned_files:
- scripts/release/check_dist_set.py
- scripts/release/check_exact_install.py
- scripts/release/README.md
- tests/_support/shared_build_artifacts.py
- tests/release/test_check_dist_set.py
- tests/release/test_check_exact_install.py
- tests/cross_cutting/packaging/test_producer_wheel_contents.py
- tests/cross_cutting/packaging/test_packaging_safety.py
- tests/cross_cutting/packaging/test_package_bundling.py
role: implementer
tags: []
tracker_refs: []
---

# WP08: Packaging remainder: dist-set check, exact-install check, fixtures and packaging tests

## ⚡ Do This First: Load Agent Profile

Use the `/spk-charter-profile-load` skill to load the agent profile specified in the frontmatter, and behave according to its guidance before parsing the rest of this prompt.

- **Profile**: `python-pedro`
- **Role**: `implementer`
- **Agent/tool**: `claude`

---

## Objective

Finish the wheel-handling machinery for a multi-wheel dist set: set-check script, exact-install check, shared fixtures and packaging tests.

## Context

Plan sections 3.2 (WP08 row), 3.4; research R-3, R-13; spec G-1, G-2, G-7, G-10, G-12. Python is bounded build glue only (plan 13 item 2): `check_dist_set.py` (including its `--verify-binaries` and `--verify-manifests` modes) and the `check_exact_install.py --wheel-tag` edit; no runtime logic, pinned by `tests/architectural/test_build_glue_thin.py` (created by WP01; this WP removes its two "not yet landed" entries, a recorded edit). **No `pyproject.toml` edit** (WP01 did the hook table, WP11 the floor), so WP08 is **not gated by the events release**. **`scripts/release/dist-set.json` is written complete by WP01** (platform wheels, `sdist`, `pure_wheels`); this WP does not edit it.

**Check open PRs** on the owned files before the first commit (none known to overlap).

## Subtask T052: Dist-set equality tests

**Steps**: red test first against WP01's complete `dist-set.json` (set equality with mutation: remove one platform wheel, add an unexpected one, drop the sdist or pure wheel: each must fail). No edit of `dist-set.json`. **Covers** AC-037 (data), G-12.

## Subtask T053: `check_dist_set.py`

**Steps**: compares `dist/` with the pinned list: three platform wheels, one sdist, the pure wheel; exact tags; floor non-empty; `--verify-binaries` re-hashes each binary member against `PRODUCER.sha256` (consumed by WP09). File sha256 only (the closed import allowlist of the glue gate allows `hashlib` for that and nothing else); in tests a raw `hashlib.sha256` needs an inline `# noqa: TID251` justification. Tests in `tests/release/test_check_dist_set.py` with fixtures built from stub wheels. **Covers** AC-024, AC-037, G-1, G-12.

## Subtask T054: `check_exact_install.py --wheel-tag`

**Steps**: red tests: untagged multi-wheel still refuses; `--wheel-tag` selects the matching wheel by path. **Covers** G-2.

## Subtask T055: Shared fixtures

**Steps**: `tests/_support/shared_build_artifacts.py` gains a platform-wheel fixture (hook on, stub binary) beside the existing pure one; consumers keep working (`test_packaging_safety.py`, `test_package_bundling.py`: the prefix tuple of `test_wheel_contains_only_known_packages` is amended for `_native/` only if needed).

## Subtask T056: Packaging tests

**Steps**: `test_producer_wheel_contents.py`: binary only in platform wheels, executable, platform tag; sdist and pure wheel binary-free (with an untracked stub in `_native/`); a wheel built from the extracted sdist with no binary succeeds; editable install works without it; the CLI reports `binary-missing` (hand the runtime half to WP10); no wheel or sdist contains `packs/internal/` and the `packs` include stays narrowed to `packs/built-in/`. **Covers** FR-041, FR-043, FR-044, AC-024, AC-038, AC-033 (packs part), G-7, G-10.

## Subtask T082: `--verify-manifests` (checksum continuity, executable)
**Steps**: red tests first, then a `--verify-manifests` mode of `check_dist_set.py` (WP09's release job only calls it; the logic is not workflow shell, so AC-032 and G-8 are proven by executable tests and not by reading YAML). It takes the downloaded `producer-<leg>` manifests and the dist directory, and fails unless every manifest `source commit` equals the supplied commit and each recomputed binary sha256 equals both the manifest and the wheel member. Fixtures built from stub wheels. **Mutations, each red:** a wrong source commit; a tampered binary byte; a missing manifest; an extra wheel; a missing wheel. Also the **wheel-composition test**: the three platform wheels differ from the pure wheel only in tag, binary member and `PRODUCER.sha256` member. **Covers** FR-042, AC-032, G-8.

## Subtask T057: README and gate pass

**Steps**: `scripts/release/README.md` documents the set-check, manifest-check and exact-install usage. Remove the two "not yet landed" entries (`check_dist_set.py`, the `check_exact_install.py` edit) from `tests/architectural/test_build_glue_thin.py` and run it with all glue present.  Run `tests/release/test_check_exact_install.py test_release_prep.py test_validate_release.py`, packaging tests, `uv lock --check`.

## Gates

`make lint`; ruff check and format on the glue; mypy where configured; targeted pytest named above; `tests/architectural/test_build_glue_thin.py` (exists since WP01, so the glue is checked automatically) and `test_clock_import_ban.py`, `test_clock_call_ban.py`, `test_fast_tier_marker_completeness.py`, `test_module_shard_registry.py`. Red-first with mutation cases.

## Definition of Done

T052 to T057 and T082 done; every mutation case red; "not yet landed" list empty; no `pyproject.toml` and no `dist-set.json` change.

## Risks

Existing tests assume one wheel (`shared_build_artifacts`); hatchling not installed in the venv (use the uv cache or `--no-isolation`).

## Reviewer Guidance

Check every step iterates the expected set with a non-empty floor, and no script holds logic beyond comparing and hashing.

## Tests, markers, jobs

| New or extended test file | Marker | Runs in |
|---|---|---|
| `tests/release/test_check_dist_set.py`, `test_check_exact_install.py` | `fast` | `tests/release` shard |
| `tests/cross_cutting/packaging/test_producer_wheel_contents.py` | `integration` (wheel-building) | packaging shard (not `make test-fast`); the three-OS binary run of the real wheel is the shared smoke action in `native-smoke` and `smoke-wheels` |
| `tests/cross_cutting/packaging/test_packaging_safety.py`, `test_package_bundling.py` | existing `integration` | packaging shard |

## Recorded out-of-map edits

| File | Edit | Reason and order |
|---|---|---|
| `tests/architectural/test_build_glue_thin.py` (WP01) | empty the "not yet landed" list | the glue exists from this WP |

## Implementation command

`spec-kitty agent action implement WP08 --agent claude --mission event-producer-reroute-01M4VDY9`
