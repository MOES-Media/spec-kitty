"""Exemption ledger: the ``canonical-producer-exempt`` markers against the RELEASED schemas (FR-032, FR-033, AC-019).

A marker on an authoritative Python writer is cleared only when the persisted lines that writer wrote validate against the
legacy-row form of the schema released in ``spec_kitty_events`` (``producer_contract``): the single ``data`` schema plus the lifted
members, never a second authored schema. This gate

* scans ``src/`` for markers that cite #1198 or #1248 and names the residuals that remain (each with its reason and issue reference),
  and keeps the eight #1200 markers of ``glossary/events.py`` where they are;
* drives the REAL writer of every cleared marker once and validates the persisted line through the persisted-line mode of
  ``scripts/lint_canonical_producers.py`` (which reads the released schemas from the installed package);
* drives the real writer of every residual marker and pins WHY it stays: its line does not validate, so the day it does, this gate
  fails and the marker must go;
* validates a publish-log line of every in-scope type (the ``input`` of the released valid fixtures) and the run-journal kinds
  written by ``engine._append_event`` (an additional case, not a replacement for the per-marker rows);
* proves itself: a schema-invalid line fails, an empty schema set fails, and the floors hold.
"""

from __future__ import annotations

import importlib.util
import json
import re
import sys
import tokenize
from collections.abc import Callable
from importlib import resources
from pathlib import Path
from types import ModuleType
from typing import Any

import pytest

from tests.architectural.test_seam_census import _DECISION_WRITERS, _LIFECYCLE_WRITERS, World

pytestmark = [pytest.mark.architectural]

_REPO_ROOT = Path(__file__).resolve().parents[2]
_SRC = _REPO_ROOT / "src"
_SCRIPT = _REPO_ROOT / "scripts" / "lint_canonical_producers.py"
_MARKER = "canonical-producer-exempt:"
_TRACKED = re.compile(r"#(1198|1248)\b")
_GLOSSARY = "src/glossary/events.py"

#: Markers per file before this mission (data-model section 8), keyed by the posix path under the repo root.
_MARKERS_BEFORE = {
    "src/specify_cli/decisions/emit.py": 2,  # markers 1 and 2
    "src/specify_cli/status/lifecycle_events.py": 3,  # markers 5, 6 and 7
    "src/runtime/next/_internal_runtime/engine.py": 1,  # marker 8
    "src/specify_cli/events/decision_log.py": 1,  # marker 3
    "src/specify_cli/migration/mission_state.py": 1,  # marker 4
}

#: The named residuals: file -> (markers that stay, the reason, the issue reference). A residual line is not a persisted ledger row of any
#: registered type, so it cannot be validated; the tests below pin that fact for each.
_RESIDUALS = {
    "src/specify_cli/events/decision_log.py": (
        1,
        "marker 3: the decisions.events.jsonl sidecar row (at, event_id, event_type, mission_id, payload) is neither a decision envelope nor a journal row",
        "#1198",
    ),
    "src/specify_cli/migration/mission_state.py": (
        1,
        "marker 4: a historical migration-replay envelope (WPStatusChanged, a dry-run value) that is persisted in no ledger and has no registered type",
        "#1198",
    ),
    "src/specify_cli/status/lifecycle_events.py": (
        1,
        "marker 7: the lifecycle-to-SaaS queue wire envelope (schema 3.0.0, node and clock members) is an outbound value, never a persisted ledger line",
        "#1198",
    ),
}


# --- the lint script, loaded by path (it is not a package) --------------------------------------------------------------------------


@pytest.fixture()
def lint(monkeypatch: pytest.MonkeyPatch) -> ModuleType:
    spec = importlib.util.spec_from_file_location("lint_canonical_producers", _SCRIPT)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    monkeypatch.setitem(sys.modules, "lint_canonical_producers", module)
    spec.loader.exec_module(module)
    assert hasattr(module, "lint_persisted_lines"), "the lint script has no persisted-line mode"
    assert hasattr(module, "released_schemas"), "the lint script cannot read the released schemas"
    return module


# --- markers in src ------------------------------------------------------------------------------------------------------------------


def _marker_lines(path: Path) -> list[str]:
    """The text of every ``canonical-producer-exempt:`` comment in *path* (a real comment token, not a string)."""
    found: list[str] = []
    with path.open("rb") as handle:
        for token in tokenize.tokenize(handle.readline):
            if token.type == tokenize.COMMENT and _MARKER in token.string:
                found.append(token.string)
    return found


def _tracked_markers_by_file() -> dict[str, list[str]]:
    per_file: dict[str, list[str]] = {}
    for path in sorted(_SRC.rglob("*.py")):
        tracked = [text for text in _marker_lines(path) if _TRACKED.search(text) or "#1200" in text]
        if tracked:
            per_file[path.relative_to(_REPO_ROOT).as_posix()] = tracked
    return per_file


def _cleared_per_file(per_file: dict[str, list[str]]) -> dict[str, int]:
    return {name: before - len(per_file.get(name, [])) for name, before in _MARKERS_BEFORE.items()}


def test_no_marker_cites_1198_or_1248_except_named_residuals() -> None:
    per_file = _tracked_markers_by_file()
    left = {name: len([t for t in texts if _TRACKED.search(t)]) for name, texts in per_file.items() if name != _GLOSSARY}
    left = {name: count for name, count in left.items() if count}
    expected = {name: stays for name, (stays, _reason, _issue) in _RESIDUALS.items()}
    assert left == expected, f"markers citing #1198 or #1248 outside the named residuals: {left} (expected {expected})"


def test_every_residual_names_a_reason_and_an_issue_reference() -> None:
    assert _RESIDUALS, "the ledger names no residual: the check below would be vacuous"
    per_file = _tracked_markers_by_file()
    for name, (stays, reason, issue) in _RESIDUALS.items():
        assert reason and re.fullmatch(r"#\d+", issue), f"{name}: a residual needs a reason and an issue reference"
        assert all(issue in text for text in per_file[name] if _TRACKED.search(text)), f"{name}: the marker does not cite {issue}"
        assert len(per_file[name]) == stays


def test_the_eight_1200_markers_of_the_glossary_stay() -> None:
    glossary = [text for text in _tracked_markers_by_file().get(_GLOSSARY, []) if "#1200" in text]
    assert len(glossary) == 8, f"{_GLOSSARY} must keep exactly its eight #1200 markers, found {len(glossary)}"


# --- the real writers -----------------------------------------------------------------------------------------------------------------


def _ledger_lines(path: Path) -> list[str]:
    return [line for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def _driven(driver: Callable[[World, pytest.MonkeyPatch], Path], root: Path, monkeypatch: pytest.MonkeyPatch) -> str:
    """Run a census driver (the real writer, once) and return the single persisted line it wrote."""
    world = World(root)
    world.record_seam()
    lines = _ledger_lines(driver(world, monkeypatch))
    assert len(lines) == 1, f"the writer was forced once and must have persisted exactly one line, found {len(lines)}"
    return lines[0]


def _journal_line(root: Path, event_type: str, payload: dict[str, Any]) -> str:
    """Run the real ``engine._append_event`` once and return the line it appended."""
    from runtime.next._internal_runtime.engine import _append_event

    root.mkdir(parents=True, exist_ok=True)
    _append_event(root, event_type, payload)
    lines = _ledger_lines(root / "run.events.jsonl")
    assert len(lines) == 1
    return lines[0]


def _fixture_dir(kind: str) -> Path:
    return Path(str(resources.files("spec_kitty_events.producer_contract"))) / "fixtures" / "rows" / kind


def _valid_fixtures() -> dict[str, dict[str, Any]]:
    """Fixture name -> document, for the released valid fixtures."""
    return {path.name: json.loads(path.read_text(encoding="utf-8")) for path in sorted(_fixture_dir("valid").glob("*.json"))}


def _name_of(lint: ModuleType, row: dict[str, Any]) -> str:
    """The registered type name a legacy envelope row belongs to, read from the registry's own discriminator (no hand list)."""
    from spec_kitty_events.producer_contract import load_registry

    by_discriminator = {entry["legacy"]["discriminator_value"]: entry["name"] for entry in load_registry()["types"] if "discriminator_value" in entry["legacy"]}
    return by_discriminator[row["event_type"]]


def _fixture_for(type_name: str) -> dict[str, Any]:
    return _valid_fixtures()[f"{type_name}.json"]


def _cleared_rows(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> dict[str, list[tuple[str, str]]]:
    """File -> the (type name, persisted line) the real writers of its cleared markers wrote."""
    from specify_cli.status.lifecycle_events import MISSION_CREATED, WP_CREATED

    started = _fixture_for("run.started.v1")["legacy_row"]
    return {
        # markers 1 and 2: the two decision writers
        "src/specify_cli/decisions/emit.py": [
            ("decision.opened.v1", _driven(_DECISION_WRITERS["DecisionPointOpened"], tmp_path / "m1", monkeypatch)),
            ("decision.resolved.v1", _driven(_DECISION_WRITERS["DecisionPointResolved"], tmp_path / "m2", monkeypatch)),
        ],
        # markers 5 and 6: the one lifecycle envelope builder, driven for a mission-scoped and a work-package-scoped type
        "src/specify_cli/status/lifecycle_events.py": [
            ("mission.created.v1", _driven(_LIFECYCLE_WRITERS[MISSION_CREATED], tmp_path / "m5", monkeypatch)),
            ("wp.created.v1", _driven(_LIFECYCLE_WRITERS[WP_CREATED], tmp_path / "m6", monkeypatch)),
        ],
        # marker 8: the run-journal appender
        "src/runtime/next/_internal_runtime/engine.py": [
            ("run.started.v1", _journal_line(tmp_path / "m8", started["event_type"], started["payload"])),
        ],
    }


def test_one_persisted_row_per_cleared_marker_validates_against_the_released_schemas(lint: ModuleType, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    schemas = lint.released_schemas("legacy")
    assert len(schemas) > 0, "floor: no released schema resolved"
    cleared = _cleared_per_file(_tracked_markers_by_file())
    assert sum(cleared.values()) > 0, "floor: no marker is cleared"
    rows = _cleared_rows(tmp_path, monkeypatch)
    validated = 0
    for name, count in cleared.items():
        if name in rows:
            assert len(rows[name]) == count, f"{name}: {count} cleared marker(s) but {len(rows[name])} real-writer row(s)"
        else:
            assert count == 0, f"{name}: markers cleared with no real-writer row to validate"
    for name, entries in rows.items():
        for type_name, line in entries:
            findings = lint.lint_persisted_lines([(name, type_name, line)], "legacy")
            assert findings == [], [f.format() for f in findings]
            validated += 1
    assert validated == sum(cleared.values()), f"{sum(cleared.values())} cleared marker(s) but {validated} validated row(s)"


def test_the_row_a_writer_persisted_belongs_to_the_type_the_ledger_claims(lint: ModuleType, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    for name, entries in _cleared_rows(tmp_path, monkeypatch).items():
        for type_name, line in entries:
            row = json.loads(line)
            if "decisions/emit.py" in name or "lifecycle_events.py" in name or "engine.py" in name:
                assert _name_of(lint, row) == type_name, f"{name}: a {row['event_type']} row was filed under {type_name}"


def test_the_run_journal_kinds_the_engine_writes_validate_too(lint: ModuleType, tmp_path: Path) -> None:
    kinds = [(name, doc["legacy_row"]) for name, doc in _valid_fixtures().items() if doc["legacy_row"].get("row_version") == 1]
    assert len(kinds) == 8, f"floor: the eight run-journal kinds, found {len(kinds)}"
    for index, (name, legacy) in enumerate(kinds):
        line = _journal_line(tmp_path / f"j{index}", legacy["event_type"], legacy["payload"])
        type_name = name.removesuffix(".json")
        findings = lint.lint_persisted_lines([(name, type_name, line)], "legacy")
        assert findings == [], [f.format() for f in findings]


def test_every_lifecycle_type_the_writer_persists_validates_too(lint: ModuleType, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    assert len(_LIFECYCLE_WRITERS) == 12, "floor: the twelve lifecycle types"
    for index, (event_type, driver) in enumerate(sorted(_LIFECYCLE_WRITERS.items())):
        line = _driven(driver, tmp_path / f"l{index}", monkeypatch)
        type_name = _name_of(lint, json.loads(line))
        findings = lint.lint_persisted_lines([(event_type, type_name, line)], "legacy")
        assert findings == [], [f.format() for f in findings]


# --- the residuals stay because their lines do not validate ----------------------------------------------------------------------------


def test_marker_3_stays_residual_while_the_decision_log_row_does_not_validate(lint: ModuleType, tmp_path: Path) -> None:
    from runtime.next._internal_runtime.events import NullEmitter
    from spec_kitty_events.mission_next import DecisionInputRequestedPayload, RuntimeActorIdentity
    from specify_cli.events.decision_log import DecisionGitLog

    mission_dir = tmp_path / "kitty-specs" / "m3"
    log = DecisionGitLog(tmp_path, tmp_path, "main", "m3", mission_dir=mission_dir, inner=NullEmitter())
    actor = RuntimeActorIdentity(actor_id="ledger", actor_type="llm")
    log.emit_decision_input_requested(
        DecisionInputRequestedPayload(
            run_id="0123456789abcdef0123456789abcdef", decision_id="input:x", step_id="s", question="q?", options=("a",), input_key="x", actor=actor
        )
    )
    (line,) = _ledger_lines(mission_dir / "decisions.events.jsonl")
    for type_name in ("run.decision.requested.v1", "decision.opened.v1", "decision.resolved.v1"):
        assert lint.lint_persisted_lines([("decision_log", type_name, line)], "legacy"), f"marker 3 now validates as {type_name}: clear it"


def test_marker_7_stays_residual_while_the_saas_queue_envelope_does_not_validate(lint: ModuleType) -> None:
    from specify_cli.status.lifecycle_events import build_saas_lifecycle_queue_event

    envelope = {
        "event_id": "01KLEDGER000000000000000A1",
        "event_type": "MissionCreated",
        "aggregate_id": "m7",
        "aggregate_type": "Mission",
        "schema_version": "5.0.0",
        "timestamp": "2026-10-15T00:00:00+00:00",
        "payload": {
            "mission_slug": "m7",
            "mission_number": None,
            "mission_type": "software-dev",
            "target_branch": "main",
            "wp_count": 0,
            "created_at": "2026-10-15T00:00:00+00:00",
            "mission_id": "01KLEDGER000000000000000M1",
        },
        "project_uuid": None,
        "project_slug": None,
    }
    queued = build_saas_lifecycle_queue_event(
        envelope, build_id="b", project_uuid="11111111-1111-4111-8111-111111111111", project_slug="p", node_id="n", lamport_clock=1
    )
    assert queued is not None, "the SaaS queue builder refused the probe envelope: the probe no longer reaches the marker"
    assert lint.lint_persisted_lines([("saas-queue", "mission.created.v1", json.dumps(queued))], "legacy"), "marker 7 now validates: clear it"


def test_marker_4_stays_residual_while_its_event_type_is_not_registered(lint: ModuleType) -> None:
    from specify_cli.migration.mission_state import _status_event_to_teamspace_envelope
    from specify_cli.status.models import Lane, StatusEvent

    event = StatusEvent(
        event_id="01KLEDGER000000000000000E1",
        mission_slug="m4",
        wp_id="WP01",
        from_lane=Lane.PLANNED,
        to_lane=Lane.CLAIMED,
        at="2026-10-15T00:00:00+00:00",
        actor="ledger",
        force=False,
        execution_mode="worktree",
    )
    import uuid

    envelope = _status_event_to_teamspace_envelope(event, project_uuid=uuid.uuid4(), lamport_clock=1, project_slug="p", repo_slug=None)
    registered = {entry["legacy"].get("discriminator_value") for entry in lint.released_registry()["types"]}
    assert envelope["event_type"] not in registered, "marker 4's event type is now registered: validate its line and clear it"


# --- the publish log ----------------------------------------------------------------------------------------------------------------


def test_publish_log_lines_validate_at_least_one_per_in_scope_type(lint: ModuleType) -> None:
    schemas = lint.released_schemas("publish")
    assert len(schemas) > 0, "floor: no released row schema resolved"
    documents = _valid_fixtures()
    covered: set[str] = set()
    for name, document in documents.items():
        type_name = document["type"].removeprefix(lint.released_registry()["type_prefix"] + ".")
        line = json.dumps(document["input"], sort_keys=True)
        findings = lint.lint_persisted_lines([(name, type_name, line)], "publish")
        assert findings == [], [f.format() for f in findings]
        covered.add(type_name)
    assert covered == set(schemas), f"types without a validated publish-log line: {sorted(set(schemas) - covered)}"


# --- the gate proves itself ------------------------------------------------------------------------------------------------------------


def test_mutation_a_schema_invalid_persisted_line_fails_the_gate(lint: ModuleType) -> None:
    valid = json.dumps(_fixture_for("decision.opened.v1")["legacy_row"])
    assert lint.lint_persisted_lines([("probe", "decision.opened.v1", valid)], "legacy") == []
    broken = json.loads(valid)
    broken["unexpected_member"] = 1
    findings = lint.lint_persisted_lines([("probe", "decision.opened.v1", json.dumps(broken))], "legacy")
    assert [f.code for f in findings] == ["CP950"]
    stripped = json.loads(valid)
    del stripped["event_id"]
    assert [f.code for f in lint.lint_persisted_lines([("probe", "decision.opened.v1", json.dumps(stripped))], "legacy")] == ["CP950"]


def test_mutation_a_line_that_is_not_json_fails_the_gate(lint: ModuleType) -> None:
    assert [f.code for f in lint.lint_persisted_lines([("probe", "decision.opened.v1", "{not json")], "legacy")] == ["CP951"]


def test_mutation_an_empty_schema_set_fails_the_gate(lint: ModuleType) -> None:
    valid = json.dumps(_fixture_for("decision.opened.v1")["legacy_row"])
    findings = lint.lint_persisted_lines([("probe", "decision.opened.v1", valid)], "legacy", schemas={})
    assert [f.code for f in findings] == ["CP952"]
    assert [f.code for f in lint.lint_persisted_lines([("probe", "no.such.type.v1", valid)], "legacy")] == ["CP952"]


def test_the_cli_exits_one_on_an_invalid_persisted_line_and_zero_on_a_valid_one(lint: ModuleType, tmp_path: Path) -> None:
    good = tmp_path / "good.jsonl"
    good.write_text(json.dumps(_fixture_for("decision.opened.v1")["legacy_row"]) + "\n", encoding="utf-8")
    bad = tmp_path / "bad.jsonl"
    bad.write_text('{"event_type": "DecisionPointOpened"}\n', encoding="utf-8")
    assert lint.main(["--persisted-lines", f"decision.opened.v1={good}"]) == 0
    assert lint.main(["--persisted-lines", f"decision.opened.v1={bad}"]) == 1
    assert lint.main(["--persisted-lines", f"decision.opened.v1={tmp_path / 'absent.jsonl'}"]) == 2


def test_the_persisted_line_mode_is_gate_glue_with_no_producer_logic() -> None:
    """The mode loops over released schemas and persisted lines; it builds no envelope and holds no id, clock or schema (O-3)."""
    import ast

    tree = ast.parse(_SCRIPT.read_text(encoding="utf-8"))
    mode = {"released_registry", "released_schemas", "lint_persisted_lines", "_persisted_findings", "_read_persisted_lines", "_run_persisted_mode"}
    functions = [node for node in ast.walk(tree) if isinstance(node, ast.FunctionDef) and node.name in mode]
    assert {f.name for f in functions} == mode, "the persisted-line mode functions are not all present"
    forbidden = {"uuid", "ulid", "time", "datetime", "random", "secrets", "now_utc", "now_utc_iso", "uuid4", "uuid5"}
    for function in functions:
        names = {n.id for n in ast.walk(function) if isinstance(n, ast.Name)} | {n.attr for n in ast.walk(function) if isinstance(n, ast.Attribute)}
        assert not (names & forbidden), f"{function.name} reaches {sorted(names & forbidden)}: that is producer logic"
        assert not [
            d for d in ast.walk(function) if isinstance(d, ast.Dict) and {"event_type", "payload"} <= {k.value for k in d.keys if isinstance(k, ast.Constant)}
        ]
        assert not [
            n
            for n in ast.walk(function)
            if isinstance(n, (ast.Import, ast.ImportFrom)) and "spec_kitty_events" not in ast.dump(n) and "jsonschema" not in ast.dump(n)
        ]
