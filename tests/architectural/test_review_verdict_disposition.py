"""FR-005 — two-pass behavioural disposition gate for review-verdict readers.

Mission ``review-cycle-read-authority-01KYB6Z0`` WP05 (T021-T024). Makes the
mission's disposition decision (research.md Decision 1) executable: a newly
added reader that derives a review verdict without honouring the override
must fail this gate, not pass unnoticed.

Two REJECTED designs precede this one (WP05's task prompt / research.md
Decision 1's "Methodology correction"):

- **Attempt 1** — "implementations go from 4 to 1, verified by a search of
  the source tree." No search method, wrong count. Unfalsifiable.
- **Attempt 2** — a location-scoped grep: "a search of ``src/`` for
  review-cycle globbing returns matches only inside the canonical helper
  module." Wrong in both directions — it rejects legitimately-excluded sites
  that live outside the canonical module *by design* (next-cycle-number,
  iterate-all-cycles) and waves through ``ReviewCycleArtifact.latest()``, an
  override-blind verdict-bearing reader that lives *inside* the canonical
  module.

This gate is therefore BEHAVIOURAL (what a site does, never where it lives)
and TWO-PASS:

- **Pass 1 — record readers**: every site calling
  ``<expr>.glob("review-cycle-*.md")`` under ``src/``.
- **Pass 2 — override readers**: every site constructing a
  ``ReviewOverride`` from a ``review`` slot (``ReviewOverride.from_dict(...)``
  or ``<expr>.get("review")``).

Neither pass alone suffices: Pass 1 cannot see override readers (no glob at
all); Pass 2 cannot see record readers. The mission's own history proves the
point — the post-merge duplicate ``_snapshot_review_override`` (retired by
WP04, research.md Decision 6) had no glob anywhere in it and was invisible
to an earlier planning draft that enumerated with only a file-glob query.

**Why AST, not textual grep** (T021 requires justifying the choice):

1. A plain-text ``grep 'glob("review-cycle-\\*\\.md")'`` also matches the
   phrase quoted inside ``cli/commands/agent/workflow.py``'s module
   docstring — a false positive an AST ``Call`` node match does not produce.
2. research.md's own worked Pass-2 grep (``get("review")`` as a literal
   substring) misses every call site that passes a default value:
   ``config.get("review", {})`` does not contain the substring
   ``get("review")`` because of the intervening ``, {}`` before the closing
   paren. Five such config-section reads exist in the live tree (see
   ``_CONFIG_SECTION`` reason below) and a literal grep silently drops all of
   them. AST inspects the *first positional argument* of the call, not the
   full call text, so it finds them — more thoroughly than research.md's own
   documented methodology. That is the correct direction to err: the mission
   exists precisely because a narrower search missed a duplicate once
   already.

**Two `review` keys must not be conflated** (research.md Decision 1 rows
15-16). The *override* slot (``ReviewOverride``: ``at``/``actor``/``wp_id``/
``reason``) lives on the reduced per-WP snapshot. The *done-evidence* block
(``reviewer``/``verdict``/``reference``) lives on a done transition's
evidence payload (``status/validate.py``, ``status/emit.py`` read that one).
Both surface as ``<expr>.get("review")`` textually, so Pass 2 finds both —
the disposition table below discriminates them by what the surrounding code
actually does with the value (shape), not by the key name, and gives
done-evidence sites the ``_DONE_EVIDENCE`` reason rather than silently
treating every ``get("review")`` hit as an override reader.
"""

from __future__ import annotations

import ast
import enum
from dataclasses import dataclass
from pathlib import Path

import pytest

pytestmark = [pytest.mark.architectural]

REPO_ROOT = Path(__file__).resolve().parents[2]
SRC_ROOT = REPO_ROOT / "src"
DECOY_ROOT = Path(__file__).resolve().parent / "_fixtures" / "review_verdict_disposition_decoys"

_GLOB_PATTERN = "review-cycle-*.md"
_REVIEW_KEY = "review"

# ---------------------------------------------------------------------------
# Site model
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class Site:
    """One enumerated call site: a (file, enclosing-function) pair.

    Multiple call-node hits inside the same function collapse to one Site —
    the disposition is a property of what the *function* does, not of how
    many AST nodes happen to match inside it (``status/wp_review.py``'s
    canonical construction body hits both the ``get("review")`` pattern and
    the ``ReviewOverride.from_dict`` pattern in the same four-line function).
    """

    relpath: str
    qualname: str
    lineno: int

    @property
    def key(self) -> tuple[str, str]:
        return (self.relpath, self.qualname)

    def label(self) -> str:
        return f"{self.relpath}:{self.lineno} ({self.qualname})"


class Disposition(enum.Enum):
    CANONICAL = "CANONICAL"
    IN_SCOPE = "IN_SCOPE"
    EXCLUDED = "EXCLUDED"


@dataclass(frozen=True)
class DispositionEntry:
    disposition: Disposition
    reason: str


# ---------------------------------------------------------------------------
# AST scanners
# ---------------------------------------------------------------------------


class _ScopeTrackingVisitor(ast.NodeVisitor):
    """Tracks a dotted qualname stack (``Class.method``) while walking."""

    def __init__(self) -> None:
        self._stack: list[str] = []
        self.hits: list[tuple[str, int]] = []

    def _qualname(self) -> str:
        return ".".join(self._stack) if self._stack else "<module>"

    def visit_ClassDef(self, node: ast.ClassDef) -> None:
        self._stack.append(node.name)
        self.generic_visit(node)
        self._stack.pop()

    def visit_FunctionDef(self, node: ast.FunctionDef) -> None:
        self._stack.append(node.name)
        self.generic_visit(node)
        self._stack.pop()

    visit_AsyncFunctionDef = visit_FunctionDef  # type: ignore[assignment]


class _GlobCallVisitor(_ScopeTrackingVisitor):
    """Pass 1: ``<expr>.glob("review-cycle-*.md")`` call sites."""

    def visit_Call(self, node: ast.Call) -> None:
        func = node.func
        if isinstance(func, ast.Attribute) and func.attr == "glob" and node.args and isinstance(node.args[0], ast.Constant) and node.args[0].value == _GLOB_PATTERN:
            self.hits.append((self._qualname(), node.lineno))
        self.generic_visit(node)


class _OverrideCallVisitor(_ScopeTrackingVisitor):
    """Pass 2: ``ReviewOverride.from_dict(...)`` or ``<expr>.get("review")``."""

    def visit_Call(self, node: ast.Call) -> None:
        func = node.func
        is_from_dict = isinstance(func, ast.Attribute) and func.attr == "from_dict" and isinstance(func.value, ast.Name) and func.value.id == "ReviewOverride"
        is_review_get = (
            isinstance(func, ast.Attribute) and func.attr == "get" and node.args and isinstance(node.args[0], ast.Constant) and node.args[0].value == _REVIEW_KEY
        )
        if is_from_dict or is_review_get:
            self.hits.append((self._qualname(), node.lineno))
        self.generic_visit(node)


def _scan(root: Path, visitor_cls: type[_ScopeTrackingVisitor]) -> list[Site]:
    """Walk every ``*.py`` file under *root*, collapsing hits per (file, fn)."""
    collapsed: dict[tuple[str, str], int] = {}
    for path in sorted(root.rglob("*.py")):
        try:
            source = path.read_text(encoding="utf-8")
            tree = ast.parse(source, filename=str(path))
        except (SyntaxError, UnicodeDecodeError):
            continue
        visitor = visitor_cls()
        visitor.visit(tree)
        try:
            relpath = str(path.relative_to(REPO_ROOT))
        except ValueError:
            relpath = str(path)
        for qualname, lineno in visitor.hits:
            existing = collapsed.get((relpath, qualname))
            if existing is None or lineno < existing:
                collapsed[(relpath, qualname)] = lineno
    return [Site(relpath=relpath, qualname=qualname, lineno=lineno) for (relpath, qualname), lineno in sorted(collapsed.items())]


def scan_glob_sites(root: Path = SRC_ROOT) -> list[Site]:
    """Pass 1 — enumerate record readers."""
    return _scan(root, _GlobCallVisitor)


def scan_override_sites(root: Path = SRC_ROOT) -> list[Site]:
    """Pass 2 — enumerate override readers."""
    return _scan(root, _OverrideCallVisitor)


# ---------------------------------------------------------------------------
# Routing check — does an IN_SCOPE site actually call the canonical read?
# ---------------------------------------------------------------------------

#: Function names that constitute "routes through the canonical read" for
#: either pass. Pass 1's survivor is ``latest_review_artifact_verdict``
#: (``review/artifacts.py``). Pass 2's survivor is the private construction
#: body ``_review_override_from_state`` (``status/wp_review.py``); its three
#: public entry points (``resolve_event_stream_review``,
#: ``resolve_materialized_review``, ``resolve_snapshot_review``) all delegate
#: to it, and a legitimate caller may call any of the four names directly.
CANONICAL_CALL_NAMES = frozenset(
    {
        "latest_review_artifact_verdict",
        "_review_override_from_state",
        "resolve_event_stream_review",
        "resolve_materialized_review",
        "resolve_snapshot_review",
    }
)


def _find_function_node(tree: ast.Module, qualname: str) -> ast.FunctionDef | ast.AsyncFunctionDef | None:
    parts = qualname.split(".")

    def _search(body: list[ast.stmt], remaining: list[str]) -> ast.FunctionDef | ast.AsyncFunctionDef | None:
        name = remaining[0]
        for node in body:
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name == name:
                if len(remaining) == 1:  # golden-count: cardinality-is-contract
                    return node
                return _search(node.body, remaining[1:])
            if isinstance(node, ast.ClassDef) and node.name == name:
                if len(remaining) == 1:  # golden-count: cardinality-is-contract
                    return None  # a class, not a function
                return _search(node.body, remaining[1:])
        return None

    return _search(tree.body, parts)


def function_calls_any(file_path: Path, qualname: str, names: frozenset[str]) -> bool:
    """Return True iff the named function's body calls one of *names*.

    Used to verify an ``IN_SCOPE`` disposition's *routing claim* — that the
    site does not merely appear in a table with the right label, but its
    source actually delegates to the canonical read (T023.3).
    """
    if qualname == "<module>":
        return False
    try:
        tree = ast.parse(file_path.read_text(encoding="utf-8"), filename=str(file_path))
    except (SyntaxError, OSError, UnicodeDecodeError):
        return False
    node = _find_function_node(tree, qualname)
    if node is None:
        return False
    for sub in ast.walk(node):
        if not isinstance(sub, ast.Call):
            continue
        func = sub.func
        name = func.id if isinstance(func, ast.Name) else (func.attr if isinstance(func, ast.Attribute) else None)
        if name in names:
            return True
    return False


# ---------------------------------------------------------------------------
# Disposition tables — research.md Decision 1, with stated reasons only
# ---------------------------------------------------------------------------

# Excluded-category reasons. These are the categories the spec's In-Scope
# Rule and research.md Decision 1 name — T022 forbids inventing new ones.
_NEXT_CYCLE_NUMBER = "next-cycle-number: counts records to compute the next cycle number (C-005)"
_EXISTENCE_PROBE = "existence probe: checks whether any record exists, never reads a verdict"
_ITERATE_ALL_CYCLES = "iterate-all-cycles: walks every record collecting something other than the current verdict (C-005)"
_PATH_FOR_DIAGNOSTICS = "path-for-diagnostics: resolves a file path for a diagnostic/bookkeeping message, not a verdict"
_FEEDBACK_DOCUMENT_SELECTION = (
    "feedback-document-selection: selects which record's *feedback text* "
    "drives the fix-mode prompt — document selection, not verdict "
    "derivation (research.md Decision 2)"
)
_RAW_PROJECTION = "raw-projection: projects the raw slot mapping for display, with no ReviewOverride construction and no completeness rule (research.md row 13)"
_MODEL_DEFINITION = (
    "model-definition: (de)serializes the event-log wire format for the reducer's own write-path plumbing — the definition, not a verdict read (research.md row 14)"
)
_DONE_EVIDENCE = (
    "different-review-key: reads the done-transition evidence block "
    "(reviewer/verdict/reference), not the override slot (research.md rows "
    "15-16) — discriminated by shape, not by key name"
)
_CONFIG_SECTION = "different-review-key: reads .kittify/config.yaml's top-level `review:` section (an unrelated config mapping), not a work-package override slot"

_CANONICAL_PASS1 = "CANONICAL — the survivor: selects the latest record AND applies the override"
_CANONICAL_PASS2 = "CANONICAL — the survivor: the single construction body every entry point in status/wp_review.py delegates to"

# Pass 1 — record readers (9 live sites).
PASS1_DISPOSITIONS: dict[tuple[str, str], DispositionEntry] = {
    ("src/specify_cli/review/artifacts.py", "latest_review_artifact_verdict"): DispositionEntry(Disposition.CANONICAL, _CANONICAL_PASS1),
    ("src/specify_cli/review/artifacts.py", "ReviewCycleArtifact.latest"): DispositionEntry(Disposition.EXCLUDED, _FEEDBACK_DOCUMENT_SELECTION),
    (
        "src/specify_cli/review/artifacts.py",
        "ReviewCycleArtifact.next_cycle_number",
    ): DispositionEntry(Disposition.EXCLUDED, _NEXT_CYCLE_NUMBER),
    ("src/specify_cli/cli/commands/agent/workflow.py", "review"): DispositionEntry(Disposition.EXCLUDED, _NEXT_CYCLE_NUMBER),
    (
        "src/specify_cli/cli/commands/agent/workflow_cores.py",
        "has_prior_rejection",
    ): DispositionEntry(Disposition.EXCLUDED, _EXISTENCE_PROBE),
    (
        "src/specify_cli/cli/commands/agent/tasks_parsing_validation.py",
        "_latest_review_cycle_path",
    ): DispositionEntry(Disposition.EXCLUDED, _PATH_FOR_DIAGNOSTICS),
    (
        "src/specify_cli/post_merge/review_artifact_consistency.py",
        "_latest_review_artifact_path",
    ): DispositionEntry(Disposition.EXCLUDED, _PATH_FOR_DIAGNOSTICS),
    ("src/specify_cli/review/arbiter.py", "_find_review_cycle_artifact"): DispositionEntry(Disposition.EXCLUDED, _PATH_FOR_DIAGNOSTICS),
    ("src/specify_cli/review/arbiter.py", "get_arbiter_overrides_for_wp"): DispositionEntry(Disposition.EXCLUDED, _ITERATE_ALL_CYCLES),
}

# Pass 2 — override readers (13 live sites). Five of these
# (config-section reads) are not named individually in research.md's Pass-2
# table — they surfaced only because AST inspects the first positional
# argument rather than matching a literal `get("review")` substring, so
# research.md's own literal-grep methodology could not see calls that pass a
# default value (e.g. `config.get("review", {})`). All five are trivially
# excluded: a `.kittify/config.yaml` `review:` config section shares nothing
# with the work-package override slot except the string "review". Reported
# here rather than silently folded in, per WP05's own instruction not to
# paper over what the gate finds.
PASS2_DISPOSITIONS: dict[tuple[str, str], DispositionEntry] = {
    ("src/specify_cli/status/wp_review.py", "_review_override_from_state"): DispositionEntry(Disposition.CANONICAL, _CANONICAL_PASS2),
    ("src/specify_cli/status/wp_view.py", "_resolved_group"): DispositionEntry(Disposition.EXCLUDED, _RAW_PROJECTION),
    (
        "src/specify_cli/status/models.py",
        "WPInnerStateDelta.from_dict",
    ): DispositionEntry(Disposition.EXCLUDED, _MODEL_DEFINITION),
    ("src/specify_cli/status/validate.py", "validate_done_evidence"): DispositionEntry(Disposition.EXCLUDED, _DONE_EVIDENCE),
    ("src/specify_cli/status/emit.py", "_build_done_evidence"): DispositionEntry(Disposition.EXCLUDED, _DONE_EVIDENCE),
    ("src/specify_cli/retrospective/generator.py", "_has_review_feedback"): DispositionEntry(Disposition.EXCLUDED, _DONE_EVIDENCE),
    (
        "src/specify_cli/migration/mission_state.py",
        "_historical_teamspace_evidence",
    ): DispositionEntry(Disposition.EXCLUDED, _DONE_EVIDENCE),
    (
        "src/specify_cli/cli/commands/agent/tasks_move_task.py",
        "_mt_hop_review_result",
    ): DispositionEntry(Disposition.EXCLUDED, _DONE_EVIDENCE),
    (
        "src/specify_cli/cli/commands/agent/tasks_move_task.py",
        "_mt_review_config_section",
    ): DispositionEntry(Disposition.EXCLUDED, _CONFIG_SECTION),
    ("src/specify_cli/agent_utils/status.py", "show_kanban_status"): DispositionEntry(Disposition.EXCLUDED, _CONFIG_SECTION),
    (
        "src/specify_cli/cli/commands/agent/tasks_status_cmd.py",
        "_review_stall_threshold_minutes",
    ): DispositionEntry(Disposition.EXCLUDED, _CONFIG_SECTION),
    ("src/specify_cli/review/baseline.py", "_get_test_command"): DispositionEntry(Disposition.EXCLUDED, _CONFIG_SECTION),
    ("src/specify_cli/review/lock.py", "_get_isolation_config"): DispositionEntry(Disposition.EXCLUDED, _CONFIG_SECTION),
}

# ---------------------------------------------------------------------------
# Floors (T024) — per-pass, independently derived and justified.
# ---------------------------------------------------------------------------

#: Pass 1: the live post-WP01-04 tree has exactly 9 glob call sites — 1
#: canonical (`latest_review_artifact_verdict`) + 8 excluded (see
#: PASS1_DISPOSITIONS). A floor on the combined total could pass with this
#: pass silently collapsed to 0 while Pass 2 alone carried the count, so this
#: floor is asserted against Pass 1's own enumeration only. If this number
#: must ever drop, it can only be because a legitimately-excluded call site
#: was deleted outright (not merged into another site) — that requires a
#: comment here explaining which one and why, not a silent decrement.
PASS1_FLOOR = 9

#: Pass 2: the live tree has exactly 13 override/`.get("review")` call
#: sites — 1 canonical (`_review_override_from_state`) + 12 excluded (7
#: matching research.md's documented rows/mentions, plus 5 config-section
#: reads AST found that research.md's literal grep missed — see the comment
#: above PASS2_DISPOSITIONS). Same independence rationale as PASS1_FLOOR:
#: this must not be folded into a combined total.
PASS2_FLOOR = 13


def _assert_floor(count: int, floor: int, *, pass_name: str) -> None:
    if count < floor:
        raise AssertionError(
            f"{pass_name} enumeration returned {count} site(s), below the floor "
            f"of {floor}. Either the enumerator is broken (returning fewer "
            f"results than the live tree has) or a real site disappeared — "
            f"investigate before lowering this floor, and if lowering it is "
            f"correct, say why in a comment next to the floor constant."
        )


# ---------------------------------------------------------------------------
# Disposition-table validator (T022 + T023.3 routing check)
# ---------------------------------------------------------------------------


def validate_disposition(
    sites: list[Site],
    table: dict[tuple[str, str], DispositionEntry],
    *,
    pass_name: str,
    search_root: Path = REPO_ROOT,
) -> None:
    """Fail on any unclassified site, and on any IN_SCOPE site that does not
    route through the canonical read.

    This is the executable form of FR-005 / T022: no site's fate is decided
    by an ungoverned search. An unclassified site fails with a message naming
    it and telling the reader to classify it in research.md — never to bump a
    count, which is exactly the mistake Attempt 1 made.
    """
    unclassified = [site for site in sites if site.key not in table]
    if unclassified:
        offenders = "\n".join(f"  - {site.label()}" for site in unclassified)
        raise AssertionError(
            f"{pass_name}: {len(unclassified)} unclassified verdict-input "
            f"site(s) found — a reader was added or moved without a recorded "
            f"disposition. Classify each in research.md Decision 1 "
            f"(CANONICAL / IN_SCOPE / EXCLUDED-with-reason) and register it "
            f"in this gate's disposition table. Do NOT resolve this by "
            f"bumping a count.\n{offenders}"
        )
    for (relpath, qualname), entry in table.items():
        if entry.disposition is not Disposition.IN_SCOPE:
            continue
        file_path = search_root / relpath
        if not function_calls_any(file_path, qualname, CANONICAL_CALL_NAMES):
            raise AssertionError(
                f"{pass_name}: {relpath}:{qualname} is classified IN_SCOPE "
                f"(must route through the canonical read) but its body calls "
                f"none of {sorted(CANONICAL_CALL_NAMES)}. A table entry alone "
                f"does not satisfy FR-004 — the site must actually delegate."
            )


# ---------------------------------------------------------------------------
# T021 — both passes find their known members
# ---------------------------------------------------------------------------


def test_pass1_finds_the_canonical_survivor() -> None:
    sites = scan_glob_sites()
    assert ("src/specify_cli/review/artifacts.py", "latest_review_artifact_verdict") in {s.key for s in sites}


def test_pass1_finds_excluded_sites_outside_the_canonical_module() -> None:
    """Would a location-scoped rule reject these? Yes — wrongly (Attempt 2)."""
    keys = {s.key for s in scan_glob_sites()}
    assert ("src/specify_cli/cli/commands/agent/workflow.py", "review") in keys  # next-cycle
    assert ("src/specify_cli/review/arbiter.py", "get_arbiter_overrides_for_wp") in keys


def test_pass1_finds_the_excluded_site_inside_the_canonical_module() -> None:
    """Acceptance Q1 — would this design have caught ``ReviewCycleArtifact.latest()``?

    It is found by Pass 1 (module location is irrelevant to enumeration) and
    carries an explicit EXCLUDED disposition with a stated reason
    (research.md Decision 2), rather than being silently waved through
    because it happens to live beside the canonical function.
    """
    sites = {s.key for s in scan_glob_sites()}
    key = ("src/specify_cli/review/artifacts.py", "ReviewCycleArtifact.latest")
    assert key in sites
    assert PASS1_DISPOSITIONS[key].disposition is Disposition.EXCLUDED
    assert "Decision 2" in PASS1_DISPOSITIONS[key].reason


def test_pass2_finds_the_canonical_survivor() -> None:
    sites = {s.key for s in scan_override_sites()}
    assert ("src/specify_cli/status/wp_review.py", "_review_override_from_state") in sites


def test_pass2_finds_done_evidence_sites_and_excludes_them_by_shape() -> None:
    """Rows 15-16: discriminated by shape, not by the literal key ``review``."""
    keys = {s.key for s in scan_override_sites()}
    for key in (
        ("src/specify_cli/status/validate.py", "validate_done_evidence"),
        ("src/specify_cli/status/emit.py", "_build_done_evidence"),
    ):
        assert key in keys
        assert PASS2_DISPOSITIONS[key].disposition is Disposition.EXCLUDED
        assert "different-review-key" in PASS2_DISPOSITIONS[key].reason


def test_a_single_pass_would_miss_the_other_passs_sites() -> None:
    """The load-bearing reason two passes are required, made executable.

    The canonical override-construction site has no glob at all (Pass 1
    cannot see it) and the canonical record-selection site never touches a
    `review` slot (Pass 2 cannot see it).
    """
    glob_keys = {s.key for s in scan_glob_sites()}
    override_keys = {s.key for s in scan_override_sites()}
    assert (
        "src/specify_cli/status/wp_review.py",
        "_review_override_from_state",
    ) not in glob_keys
    assert (
        "src/specify_cli/review/artifacts.py",
        "latest_review_artifact_verdict",
    ) not in override_keys


# ---------------------------------------------------------------------------
# T022 — disposition table matches research.md, every entry has a reason,
# unclassified sites fail with an actionable message.
# ---------------------------------------------------------------------------


def test_pass1_disposition_table_is_exhaustive_and_correct_on_the_real_tree() -> None:
    validate_disposition(scan_glob_sites(), PASS1_DISPOSITIONS, pass_name="Pass 1")


def test_pass2_disposition_table_is_exhaustive_and_correct_on_the_real_tree() -> None:
    validate_disposition(scan_override_sites(), PASS2_DISPOSITIONS, pass_name="Pass 2")


def test_every_table_entry_carries_a_non_empty_reason() -> None:
    for table in (PASS1_DISPOSITIONS, PASS2_DISPOSITIONS):
        for key, entry in table.items():
            assert entry.reason.strip(), f"{key} has an empty reason"


def test_exactly_one_canonical_survivor_per_pass() -> None:
    for table in (PASS1_DISPOSITIONS, PASS2_DISPOSITIONS):
        canonical = [k for k, v in table.items() if v.disposition is Disposition.CANONICAL]
        assert len(canonical) == 1, f"expected exactly one CANONICAL entry, got {canonical}"  # golden-count: cardinality-is-contract


def test_unclassified_site_fails_with_a_message_naming_it_and_pointing_at_research_md() -> None:
    """T022.4 — the failure names the offender and says 'classify', not 'recount'."""
    decoy_sites = scan_glob_sites(DECOY_ROOT)
    unclassified_here = [s for s in decoy_sites if s.key not in PASS1_DISPOSITIONS]
    assert unclassified_here, "fixture setup broken: decoy should be unclassified"

    with pytest.raises(AssertionError) as excinfo:
        validate_disposition(decoy_sites, PASS1_DISPOSITIONS, pass_name="Pass 1")

    message = str(excinfo.value)
    assert "rogue_latest_verdict" in message
    assert "classify" in message.lower() and "research.md" in message
    assert "do not resolve this by bumping" in message.lower()


# ---------------------------------------------------------------------------
# T023 — non-vacuity: decoy -> red, real tree -> green, routing is checked.
# ---------------------------------------------------------------------------


def test_gate_is_red_for_a_decoy_unclassified_pass1_reader() -> None:
    """T023.1 (Pass 1 half). Never shipped under src/ — see DECOY_ROOT."""
    decoy_sites = scan_glob_sites(DECOY_ROOT)
    assert any(s.qualname == "rogue_latest_verdict" for s in decoy_sites)
    with pytest.raises(AssertionError):
        validate_disposition(decoy_sites, PASS1_DISPOSITIONS, pass_name="Pass 1")


def test_gate_is_red_for_a_decoy_unclassified_pass2_reader() -> None:
    """T023.1 (Pass 2 half) + acceptance Q2 — the no-glob override reader.

    Reconstructs the shape of the retired ``_snapshot_review_override``
    (research.md Decision 6): constructs a ``ReviewOverride`` from a
    ``review`` slot directly, with no glob call anywhere in the function.
    """
    decoy_sites = scan_override_sites(DECOY_ROOT)
    assert any(s.qualname == "rogue_snapshot_override" for s in decoy_sites)
    # Confirms the acceptance question executably: Pass 1 cannot see this
    # site at all (no glob in the decoy), so only Pass 2 catches it.
    assert not any(s.qualname == "rogue_snapshot_override" for s in scan_glob_sites(DECOY_ROOT))
    with pytest.raises(AssertionError):
        validate_disposition(decoy_sites, PASS2_DISPOSITIONS, pass_name="Pass 2")


def test_gate_is_green_for_the_real_tree() -> None:
    """T023.2 — both passes pass together against the live post-WP01-04 tree."""
    validate_disposition(scan_glob_sites(), PASS1_DISPOSITIONS, pass_name="Pass 1")
    validate_disposition(scan_override_sites(), PASS2_DISPOSITIONS, pass_name="Pass 2")


def test_gate_is_red_for_an_in_scope_site_that_does_not_route_through_canonical() -> None:
    """T023.3 — checks the routing claim itself, not mere table membership.

    Takes the decoy override-construction site (no glob, direct
    ``ReviewOverride.from_dict`` construction — the retired-duplicate shape)
    and labels it ``IN_SCOPE`` in a local table copy. A table entry alone
    would satisfy a membership-only gate; this one must also fail because the
    decoy's body never calls a canonical name.
    """
    decoy_sites = scan_override_sites(DECOY_ROOT)
    decoy_matches = [s for s in decoy_sites if s.qualname == "rogue_snapshot_override"]
    assert len(decoy_matches) == 1
    decoy_key = decoy_matches[0].key
    assert decoy_key[0] == ("tests/architectural/_fixtures/review_verdict_disposition_decoys/duplicate_override_reader.py")

    local_table = dict(PASS2_DISPOSITIONS)
    local_table[decoy_key] = DispositionEntry(Disposition.IN_SCOPE, "decoy: claims to route through canonical but does not")
    with pytest.raises(AssertionError, match="IN_SCOPE"):
        validate_disposition(decoy_sites, local_table, pass_name="Pass 2")


def test_in_scope_routing_check_does_not_always_fail() -> None:
    """Symmetry check for the above: a genuinely-routing IN_SCOPE site passes.

    Without this, ``function_calls_any`` could be a stub that always returns
    False and the previous test would be vacuously green. Uses a real site —
    ``tasks_parsing_validation._get_latest_review_cycle_verdict`` — which
    delegates to ``latest_review_artifact_verdict`` and does not glob or
    construct a ``ReviewOverride`` itself, so it appears in neither pass's
    disposition table, but is a legitimate case to prove routing detection
    against.
    """
    file_path = SRC_ROOT / "specify_cli" / "cli" / "commands" / "agent" / "tasks_parsing_validation.py"
    assert function_calls_any(file_path, "_get_latest_review_cycle_verdict", CANONICAL_CALL_NAMES)
    assert not function_calls_any(
        DECOY_ROOT / "duplicate_override_reader.py",
        "rogue_snapshot_override",
        CANONICAL_CALL_NAMES,
    )


def test_decoys_do_not_leak_into_src() -> None:
    """DoD: decoy fixtures must never be shipped under src/."""
    assert DECOY_ROOT.is_relative_to(REPO_ROOT / "tests")
    assert not DECOY_ROOT.is_relative_to(SRC_ROOT)
    for site in scan_glob_sites(SRC_ROOT) + scan_override_sites(SRC_ROOT):
        assert "decoy" not in site.relpath.lower()
        assert "rogue_" not in site.qualname


# ---------------------------------------------------------------------------
# T024 — independent per-pass floors.
# ---------------------------------------------------------------------------


def test_pass1_floor_holds_on_the_real_tree() -> None:
    _assert_floor(len(scan_glob_sites()), PASS1_FLOOR, pass_name="Pass 1")


def test_pass2_floor_holds_on_the_real_tree() -> None:
    _assert_floor(len(scan_override_sites()), PASS2_FLOOR, pass_name="Pass 2")


def test_a_collapsed_pass_turns_the_floor_check_red() -> None:
    """T024 validation — breaking either enumerator (empty list) fails.

    A floor on the *combined* total would let one pass collapse to zero while
    the other alone carries the count above the combined floor; asserting
    each pass independently, against an emptied list, proves that cannot
    happen here.
    """
    with pytest.raises(AssertionError, match="Pass 1"):
        _assert_floor(0, PASS1_FLOOR, pass_name="Pass 1")
    with pytest.raises(AssertionError, match="Pass 2"):
        _assert_floor(0, PASS2_FLOOR, pass_name="Pass 2")

    # A combined-total floor is the exact vacuous design T024 forbids: Pass 1
    # collapsing to 0 while Pass 2 alone overshoots the combined floor would
    # pass a total check even though half the gate is silently dead.
    combined_floor = PASS1_FLOOR + PASS2_FLOOR
    pass1_collapsed_pass2_overshoots = 0 + (PASS2_FLOOR + PASS1_FLOOR)
    assert pass1_collapsed_pass2_overshoots >= combined_floor  # a total floor stays green here
    # ...but the independent per-pass check above still catches it, because
    # it is asserted against Pass 1's count alone (0), not the sum.
