# Issue matrix — review-cycle-read-authority-01KYB6Z0

Per FR-037 of the spec-kitty-mission-review skill Gate-4. One row per issue referenced in spec.md.

| Issue | Title | Verdict | Evidence ref |
|-------|-------|---------|--------------|
| #2646 | Approved WP stays stale — review-verdict read is override-blind | fixed | All five WPs approved. WP04 `resolve_materialized_review` seam; WP01 status board (`status.py:177-179` annotation-aware, reduce-once); WP02 tasks-status + legacy-verdict fallback; WP03 transition guard receives the event-sourced override; WP05 two-pass disposition gate prevents recurrence. |
| #2626 | Lane-transition auto-commit fails when lane worktree is missing | deferred-with-followup | Out of scope per spec.md C-006 — "must not claim to close #2626, which remains an independent open defect". Follow-up is the open upstream issue itself. |

Valid `Verdict` values: `fixed`, `verified-already-fixed`, `deferred-with-followup`, `in-mission` (being fixed by a later WP in this mission; must reach a terminal verdict before mission `done`).

## Notes

**#2646 was `in-mission` until all five WPs were approved; now `fixed`.** WP04 is the foundation: it builds
the canonical `resolve_materialized_review` seam and retires the third override-resolution
duplicate. It does not itself change what any display surface or transition guard reports, so
claiming `fixed` here would be false. The symptom is closed by WP01, WP02 and WP03. Per the verdict
vocabulary, `in-mission` passes per-WP approval but is **rejected on `done`**. That promotion has
now happened: all five WPs are approved and the row reads `fixed`.

**#2626 was deliberately not folded in.** #2646 and #2626 both came out of the same stale-workspace
investigation, and closed PR #2641 previously tried to claim both. This mission scopes to the
review-verdict read path only. #2626's surfaces (`coordination/commit_router.py`,
`lanes/lifecycle_sync.py`) have since been rewritten by `coord-commit-integrity-01KY5JS8`, so a fix
designed against the pre-rewrite code would be stale before it started — see the closing rationale
on PR #2641.
