"""Consumer contract for the spec-kitty-events producer contract (FR-014, FR-040, AC-027).

The Java event producer (``services/event-producer``) does not import this package: its build fetches the released wheel and reads the
files of ``spec_kitty_events/producer_contract/``. This module pins, from the Python side, exactly the file surface that build reads, so
an upstream release that removes a schema, a registry key, a fixture family or a total-order case, or that adds a vocabulary the Java
reader does not know, fails here before it fails in a native build:

* ``registry.json``: the top-level keys, the 27 registered types, the keys of every entry and of every lifted member, and the closed
  vocabularies the Java ``ContractRegistry`` maps (scopes, subject rules, legacy shapes and their forms, actor projections);
* ``data/<name>.schema.json`` and ``composed/rows/<name>.row.schema.json`` for every registered type, ``$id`` equal to the entry's
  ``dataschema``;
* the conformance fixtures (``valid`` / ``invalid`` / ``legacy_invalid``) and the thirteen ``total_order`` cases.
"""

from __future__ import annotations

import json
import shutil
from importlib import resources
from pathlib import Path
from typing import Any

import pytest

pytestmark = [pytest.mark.contract, pytest.mark.fast]

_TYPES = (
    "lane.transitioned.v1",
    "wp.innerstate.changed.v1",
    "project.initialized.v1",
    "mission.created.v1",
    "mission.specify.started.v1",
    "mission.plan.started.v1",
    "mission.tasks.started.v1",
    "mission.specify.completed.v1",
    "mission.plan.completed.v1",
    "mission.tasks.completed.v1",
    "wp.created.v1",
    "wp.reviewer.selfapproved.v1",
    "mission.reopened.v1",
    "mission.followup.recorded.v1",
    "decision.opened.v1",
    "decision.resolved.v1",
    "run.started.v1",
    "run.step.issued.v1",
    "run.step.autocompleted.v1",
    "run.decision.requested.v1",
    "run.decision.answered.v1",
    "run.completed.v1",
    "run.significance.evaluated.v1",
    "run.decision.denied.v1",
    "retrospective.captured.v1",
    "retrospective.capturefailed.v1",
    "retrospective.skipped.v1",
)
_REGISTRY_KEYS = {"type_prefix", "dataschema_base", "schemaversion", "legacy_shapes", "types"}
_ENTRY_KEYS = {"type", "name", "dataschema", "schema", "scope", "subject", "required_attributes", "legacy", "lifted"}
_LIFTED_KEYS = {"member", "attribute", "required", "legacy_schema", "projection", "binding_member"}
_SCOPES = {"mission", "project"}
_SUBJECT_RULES = {"wp_id", "none"}
_FORMS = {"flat", "payload_envelope"}
_PROJECTIONS = {"actor_identity", "retrospective_actor_id"}
# Floors of the released fixtures (10.5.0): a release that drops a family of them must not pass unnoticed.
_FIXTURE_FLOORS = {"valid": 31, "invalid": 79, "legacy_invalid": 34}


def _installed_root() -> Path:
    return Path(str(resources.files("spec_kitty_events.producer_contract")))


def _load(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def _entry_problems(root: Path, entry: dict[str, Any], shapes: dict[str, Any]) -> list[str]:
    name = entry.get("name", "?")
    if _ENTRY_KEYS - set(entry):
        return [f"{name}: entry keys missing {sorted(_ENTRY_KEYS - set(entry))}"]
    problems: list[str] = []
    if entry["scope"] not in _SCOPES:
        problems.append(f"{name}: unknown scope {entry['scope']}")
    if entry["subject"] not in _SUBJECT_RULES:
        problems.append(f"{name}: unknown subject rule {entry['subject']}")
    if entry["legacy"].get("shape") not in shapes:
        problems.append(f"{name}: unknown legacy shape {entry['legacy'].get('shape')}")
    for lifted in entry["lifted"]:
        if {"member", "attribute"} - set(lifted) or set(lifted) - _LIFTED_KEYS:
            problems.append(f"{name}: lifted member keys {sorted(lifted)}")
        if lifted.get("projection") is not None and lifted["projection"] not in _PROJECTIONS:
            problems.append(f"{name}: unknown actor projection {lifted['projection']}")
    data = root / "data" / f"{name}.schema.json"
    row = root / "composed" / "rows" / f"{name}.row.schema.json"
    for label, path in (("data schema", data), ("row schema", row)):
        if not path.is_file() or path.stat().st_size < 2:
            problems.append(f"{name}: {label} is missing")
    if data.is_file() and _load(data).get("$id") != entry["dataschema"]:
        problems.append(f"{name}: data schema $id differs from the registry dataschema")
    return problems


def _fixture_problems(root: Path) -> list[str]:
    problems: list[str] = []
    for family, floor in _FIXTURE_FLOORS.items():
        count = len(list((root / "fixtures" / "rows" / family).glob("*.json")))
        if count < floor:
            problems.append(f"fixtures/rows/{family}: {count} files, floor {floor}")
    order = sorted(path.name[:2] for path in (root / "fixtures" / "total_order").glob("*.json"))
    if order != [f"{n:02d}" for n in range(1, 14)]:
        problems.append(f"total_order cases are {order}")
    if not (root / "total-order.md").is_file():
        problems.append("total-order.md is missing")
    return problems


def _surface_problems(root: Path) -> list[str]:
    """Everything the Java build reads that is missing or outside the vocabulary it understands."""
    registry_path = root / "registry.json"
    if not registry_path.is_file():
        return ["registry.json is missing"]
    registry = _load(registry_path)
    problems: list[str] = []
    if _REGISTRY_KEYS - set(registry):
        problems.append(f"registry keys missing: {sorted(_REGISTRY_KEYS - set(registry))}")
    entries = registry.get("types", [])
    names = {entry.get("name") for entry in entries}
    if names != set(_TYPES) or len(entries) != len(_TYPES):
        problems.append(f"registered types differ: {sorted(names ^ set(_TYPES), key=str)}")
    shapes = registry.get("legacy_shapes", {})
    for shape_name, shape in shapes.items():
        if shape.get("form") not in _FORMS:
            problems.append(f"legacy shape {shape_name} has the unknown form {shape.get('form')}")
    for entry in entries:
        problems.extend(_entry_problems(root, entry, shapes))
    return problems + _fixture_problems(root)


def test_the_installed_producer_contract_has_the_surface_the_java_build_reads() -> None:
    root = _installed_root()
    assert (root / "registry.json").is_file()
    assert _surface_problems(root) == []


def test_the_registry_carries_the_strings_the_producer_writes() -> None:
    registry = _load(_installed_root() / "registry.json")
    assert registry["type_prefix"] == "ai.spec-kitty"
    assert registry["schemaversion"] == "4.0.0"
    assert registry["dataschema_base"].startswith("https://schemas.example.invalid/")
    assert {entry["type"] for entry in registry["types"]} == {f"ai.spec-kitty.{name}" for name in _TYPES}


@pytest.fixture
def copied_contract(tmp_path: Path) -> Path:
    destination = tmp_path / "producer_contract"
    shutil.copytree(_installed_root(), destination, ignore=shutil.ignore_patterns("__pycache__", "*.py"))
    assert _surface_problems(destination) == []
    return destination


def test_removing_a_row_schema_breaks_the_surface(copied_contract: Path) -> None:
    (copied_contract / "composed" / "rows" / "lane.transitioned.v1.row.schema.json").unlink()
    assert any("lane.transitioned.v1: row schema is missing" in problem for problem in _surface_problems(copied_contract))


def test_removing_a_data_schema_breaks_the_surface(copied_contract: Path) -> None:
    (copied_contract / "data" / "run.started.v1.schema.json").unlink()
    assert any("run.started.v1: data schema is missing" in problem for problem in _surface_problems(copied_contract))


def test_removing_a_registered_type_breaks_the_surface(copied_contract: Path) -> None:
    registry = _load(copied_contract / "registry.json")
    registry["types"] = [entry for entry in registry["types"] if entry["name"] != "wp.created.v1"]
    (copied_contract / "registry.json").write_text(json.dumps(registry), encoding="utf-8")
    assert any("registered types differ" in problem for problem in _surface_problems(copied_contract))


def test_an_unknown_vocabulary_breaks_the_surface(copied_contract: Path) -> None:
    registry = _load(copied_contract / "registry.json")
    registry["types"][0]["scope"] = "galaxy"
    registry["legacy_shapes"]["flat"]["form"] = "ragged"
    (copied_contract / "registry.json").write_text(json.dumps(registry), encoding="utf-8")
    problems = _surface_problems(copied_contract)
    assert any("unknown scope galaxy" in problem for problem in problems)
    assert any("unknown form ragged" in problem for problem in problems)


def test_dropping_a_fixture_family_or_a_total_order_case_breaks_the_surface(copied_contract: Path) -> None:
    next((copied_contract / "fixtures" / "rows" / "invalid").glob("*.json")).unlink()
    (copied_contract / "fixtures" / "total_order" / "13_rows_that_are_not_objects.json").unlink()
    problems = _surface_problems(copied_contract)
    assert any("fixtures/rows/invalid" in problem for problem in problems)
    assert any("total_order cases" in problem for problem in problems)
