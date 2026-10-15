"""The lane vocabulary is defined once (FR-034, AC-020; plan 4.4, research R-14).

``Lane`` (``status/models.py``) is the authority. Every other list of lanes in the
census is either derived from it by name, or a reviewed exception with a stated
reason. This gate:

* finds the census (floor: all six sets are located, so a scan that finds nothing
  fails instead of passing vacuously);
* checks each derived list is built from ``Lane`` / ``CANONICAL_LANES`` by
  reference (an AST check) and that its value and order equal the pre-change
  literal (a characterisation pin);
* fails when a new full literal lane list (eight or more lane names in one literal
  collection, at module level) appears anywhere under ``src/``.

``uninitialized`` is a read sentinel and is never part of a persisted list.
"""

from __future__ import annotations

import ast
from pathlib import Path

import pytest

pytestmark = [pytest.mark.architectural]

_SRC = Path(__file__).resolve().parents[2] / "src"

# Every lane name the repository knows, including the legacy alias ``doing``.
_VOCABULARY = frozenset(
    {
        "genesis",
        "uninitialized",
        "planned",
        "claimed",
        "in_progress",
        "for_review",
        "in_review",
        "approved",
        "done",
        "blocked",
        "canceled",
        "doing",
    }
)
_FULL_LIST_FLOOR = 8

# Census items that are not enumerations of the authority: name -> (file, reason).
_REVIEWED_LITERALS: dict[str, tuple[str, str]] = {
    "CANONICAL_LANES": (
        "specify_cli/status_lanes.py",
        "an import-light module that must not import status orchestration; the tuple is the source and is value-pinned to Lane",
    ),
    "_LANE_ORDER": (
        "specify_cli/migration/rebuild_state.py",
        "a path, not a vocabulary: the ordered history a legacy rebuild walks",
    ),
}

# Census items derived by reference: name -> (file, names the right-hand side must mention).
_DERIVED: dict[str, tuple[str, frozenset[str]]] = {
    "_VALID_LANES": ("specify_cli/migration/rebuild_state.py", frozenset({"CANONICAL_LANES"})),
    "_LEGACY_NOT_DONE_LANES": ("specify_cli/acceptance/__init__.py", frozenset({"Lane"})),
    "_PRE_APPROVED_LANE_VALUES": ("specify_cli/consolidation/done_bookkeeping.py", frozenset({"Lane"})),
}


def _string_members(node: ast.expr) -> list[str] | None:
    """Members of a literal collection of strings (also inside ``frozenset(...)``), else None."""
    if isinstance(node, (ast.List, ast.Tuple, ast.Set)):
        if node.elts and all(isinstance(e, ast.Constant) and isinstance(e.value, str) for e in node.elts):
            return [str(e.value) for e in node.elts if isinstance(e, ast.Constant)]
        return None
    if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id in {"frozenset", "set", "tuple", "list"} and len(node.args) == 1:
        return _string_members(node.args[0])
    return None


def _module_assignments(source: str) -> dict[str, ast.expr]:
    found: dict[str, ast.expr] = {}
    for node in ast.parse(source).body:
        if isinstance(node, ast.Assign) and node.value is not None:
            for target in node.targets:
                if isinstance(target, ast.Name):
                    found[target.id] = node.value
        elif isinstance(node, ast.AnnAssign) and node.value is not None and isinstance(node.target, ast.Name):
            found[node.target.id] = node.value
    return found


def full_literal_lane_lists(source: str) -> list[str]:
    """Names of module-level literal collections that enumerate the full lane vocabulary."""
    hits = []
    for name, value in _module_assignments(source).items():
        members = _string_members(value)
        if members is not None and set(members) <= _VOCABULARY and len(set(members)) >= _FULL_LIST_FLOOR:
            hits.append(name)
    return hits


def _census_violations(src_root: Path) -> list[str]:
    problems: list[str] = []
    allowed = {(path, name) for name, (path, _reason) in _REVIEWED_LITERALS.items()}
    for path in sorted(src_root.rglob("*.py")):
        rel = path.relative_to(src_root).as_posix()
        for name in full_literal_lane_lists(path.read_text(encoding="utf-8")):
            if (rel, name) not in allowed:
                problems.append(f"{rel}: {name} is a new full literal lane list; derive it from Lane or CANONICAL_LANES")
    return problems


def _names_in(node: ast.expr) -> set[str]:
    return {n.id for n in ast.walk(node) if isinstance(n, ast.Name)}


def test_floor_the_whole_census_is_found() -> None:
    models = _module_assignments((_SRC / "specify_cli/status/models.py").read_text(encoding="utf-8"))
    assert "NON_DISPLAY_LANES" in models  # the authority module is parsed and holds the lane constants
    assert "class Lane(" in (_SRC / "specify_cli/status/models.py").read_text(encoding="utf-8")
    for name, (path, _reason) in _REVIEWED_LITERALS.items():
        assert name in _module_assignments((_SRC / path).read_text(encoding="utf-8")), f"{name} not found in {path}"
    for name, (path, _needed) in _DERIVED.items():
        assert name in _module_assignments((_SRC / path).read_text(encoding="utf-8")), f"{name} not found in {path}"
    assert len(_REVIEWED_LITERALS) + len(_DERIVED) + 1 == 6  # the floor of six sets, with Lane itself


@pytest.mark.parametrize("name", sorted(_DERIVED))
def test_a_derived_list_is_built_by_reference_not_by_literal(name: str) -> None:
    path, needed = _DERIVED[name]
    value = _module_assignments((_SRC / path).read_text(encoding="utf-8"))[name]
    assert _string_members(value) is None, f"{name} is still a literal list"
    assert needed <= _names_in(value), f"{name} does not reference {sorted(needed)}"


def test_no_new_full_literal_lane_list_exists() -> None:
    assert _census_violations(_SRC) == []


def test_floor_the_scan_finds_the_reviewed_literal() -> None:
    source = (_SRC / "specify_cli/status_lanes.py").read_text(encoding="utf-8")
    assert full_literal_lane_lists(source) == ["CANONICAL_LANES"]


def test_mutation_an_injected_full_lane_list_fails_the_scan(tmp_path: Path) -> None:
    module = tmp_path / "specify_cli" / "injected.py"
    module.parent.mkdir(parents=True)
    module.write_text(
        'MY_LANES = frozenset({"planned", "claimed", "in_progress", "for_review", "in_review", "approved", "done", "blocked"})\n',
        encoding="utf-8",
    )
    assert _census_violations(tmp_path) == [
        "specify_cli/injected.py: MY_LANES is a new full literal lane list; derive it from Lane or CANONICAL_LANES"
    ]


def test_a_short_subset_is_not_a_full_list() -> None:
    assert full_literal_lane_lists('SUBSET = frozenset({"planned", "claimed"})\n') == []


# --- characterisation: value and order equal the pre-change literals --------------------------


def test_valid_lanes_value_is_unchanged() -> None:
    from specify_cli.migration.rebuild_state import _VALID_LANES

    assert _VALID_LANES == frozenset({"planned", "claimed", "in_progress", "for_review", "done", "blocked", "canceled", "approved"})


def test_lane_order_value_and_order_are_unchanged() -> None:
    from specify_cli.migration.rebuild_state import _LANE_ORDER

    assert _LANE_ORDER == ["planned", "claimed", "in_progress", "for_review", "done"]


def test_legacy_not_done_lanes_value_and_order_are_unchanged() -> None:
    from specify_cli.acceptance import _LEGACY_NOT_DONE_LANES

    assert _LEGACY_NOT_DONE_LANES == ("planned", "claimed", "doing", "in_progress", "for_review")


def test_pre_approved_lane_values_are_unchanged() -> None:
    from specify_cli.consolidation.done_bookkeeping import _PRE_APPROVED_LANE_VALUES

    assert _PRE_APPROVED_LANE_VALUES == frozenset({"planned", "claimed", "in_progress", "for_review"})


def test_canonical_lanes_equal_the_post_genesis_members_of_lane() -> None:
    from specify_cli.status import Lane
    from specify_cli.status_lanes import CANONICAL_LANES

    assert CANONICAL_LANES == tuple(m.value for m in Lane if m not in {Lane.GENESIS, Lane.UNINITIALIZED})


def test_the_persisted_lists_exclude_uninitialized() -> None:
    from specify_cli.acceptance import _LEGACY_NOT_DONE_LANES
    from specify_cli.consolidation.done_bookkeeping import _PRE_APPROVED_LANE_VALUES
    from specify_cli.migration.rebuild_state import _LANE_ORDER, _VALID_LANES
    from specify_cli.status_lanes import CANONICAL_LANES

    for lanes in (_VALID_LANES, _LANE_ORDER, _LEGACY_NOT_DONE_LANES, _PRE_APPROVED_LANE_VALUES, CANONICAL_LANES):
        assert "uninitialized" not in lanes
        assert "genesis" not in lanes
