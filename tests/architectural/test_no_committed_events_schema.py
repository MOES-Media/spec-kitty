"""Nothing of the spec-kitty-events producer contract is committed here (FR-014, FR-040, C-008, AC-027).

The Java producer reads the schemas, the registry and the conformance fixtures from the RELEASED spec-kitty-events wheel, fetched by
``services/event-producer/gradle/events-fetch.gradle.kts`` against the checksum pinned in ``events-pin.properties``. A copy committed
here would be a second schema set, which spec section "one schema set, one writer" forbids. This gate scans every tracked and every
untracked, non-ignored file (a diff-based scan would not see a file that is new), keeps a floor on what it scanned, proves itself
against planted copies, and pins the fetch to the floor of ``pyproject.toml`` and to ``uv.lock``.
"""

from __future__ import annotations

import json
import re
import subprocess
import tomllib
from pathlib import Path

import pytest
from packaging.requirements import Requirement
from packaging.version import Version

pytestmark = pytest.mark.architectural

_REPO_ROOT = Path(__file__).resolve().parents[2]
_PRODUCER = Path("services/event-producer")
_PIN = _PRODUCER / "gradle" / "events-pin.properties"
_SCRIPT = _PRODUCER / "gradle" / "events-fetch.gradle.kts"
_ADAPTER_BUILD = _PRODUCER / "adapter" / "build.gradle.kts"
_DATASCHEMA_BASE = "schemas.example.invalid/spec-kitty/"
_FIXTURE_CLASSES = frozenset({"producer_row_valid", "producer_row_invalid", "producer_row_legacy_invalid", "total_order"})
_WHEEL_URL = re.compile(
    r"https://files\.pythonhosted\.org/packages/[0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f]{60}/spec_kitty_events-(?P<version>[0-9][0-9A-Za-z.]*)-py3-none-any\.whl"
)


def _listed(root: Path) -> list[Path]:
    """Every tracked file and every untracked file that is not ignored, relative to ``root``."""
    out = subprocess.run(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
        cwd=root,
        check=True,
        capture_output=True,
    ).stdout
    return [Path(name.decode("utf-8")) for name in out.split(b"\0") if name]


def _verdict(relative: Path, text: str) -> str | None:
    """Why this file is a copy of the events producer contract, or ``None``."""
    if "producer_contract" in relative.parts:
        return "inside a producer_contract directory"
    if relative.name.endswith((".row.schema.json", ".legacy.schema.json")):
        return "a composed events row schema"
    if relative.suffix != ".json":
        return None
    try:
        document = json.loads(text)
    except ValueError:
        return None
    if isinstance(document, dict):
        if document.get("class") in _FIXTURE_CLASSES:
            return "an events conformance fixture"
        if {"type_prefix", "dataschema_base", "legacy_shapes", "types"} <= document.keys():
            return "the events producer registry"
        schema_id = document.get("$id")
        if isinstance(schema_id, str) and _DATASCHEMA_BASE in schema_id:
            return "a schema of the events dataschema base"
    return None


def _offenders(root: Path, files: list[Path]) -> list[str]:
    found: list[str] = []
    for relative in files:
        path = root / relative
        if not path.is_file():
            continue
        reason = _verdict(relative, path.read_text(encoding="utf-8", errors="replace") if path.suffix == ".json" else "")
        if reason:
            found.append(f"{relative.as_posix()}: {reason}")
    return sorted(found)


def test_no_events_schema_registry_or_fixture_is_committed() -> None:
    files = _listed(_REPO_ROOT)
    json_files = [f for f in files if f.suffix == ".json"]
    # Floor: the scan saw the repository, including the Java tree where a vendored copy would go.
    assert len(files) > 1000
    assert len(json_files) > 50
    assert any(f.parts[:2] == ("services", "event-producer") and f.suffix == ".json" for f in files)
    assert _offenders(_REPO_ROOT, files) == []


def test_scan_sees_untracked_files_and_ignores_ignored_ones(tmp_path: Path) -> None:
    subprocess.run(["git", "init", "--quiet", str(tmp_path)], check=True)
    (tmp_path / ".gitignore").write_text("ignored/\n", encoding="utf-8")
    (tmp_path / "ignored").mkdir()
    (tmp_path / "ignored" / "a.json").write_text("{}", encoding="utf-8")
    (tmp_path / "new.json").write_text("{}", encoding="utf-8")

    listed = _listed(tmp_path)

    assert Path("new.json") in listed
    assert Path("ignored/a.json") not in listed


@pytest.mark.parametrize(
    ("name", "content", "reason"),
    [
        ("services/event-producer/adapter/src/main/resources/registry.json", '{"type_prefix":"x","dataschema_base":"y","legacy_shapes":{},"types":[]}', "registry"),
        ("services/event-producer/x/lane.transitioned.v1.row.schema.json", "{}", "row schema"),
        ("anywhere/composed/legacy/a.v1.legacy.schema.json", "{}", "row schema"),
        ("services/event-producer/harmless.json", '{"$id":"https://schemas.example.invalid/spec-kitty/lane.transitioned.v1"}', "dataschema base"),
        ("services/event-producer/adapter/src/test/resources/f.json", '{"class":"producer_row_valid","input":{}}', "fixture"),
        ("services/event-producer/adapter/src/test/resources/o.json", '{"class":"total_order","rows":[]}', "fixture"),
        ("docs/producer_contract/notes.md", "", "producer_contract"),
    ],
)
def test_a_planted_copy_of_the_contract_is_found(tmp_path: Path, name: str, content: str, reason: str) -> None:
    planted = tmp_path / name
    planted.parent.mkdir(parents=True)
    planted.write_text(content, encoding="utf-8")

    offenders = _offenders(tmp_path, [Path(name)])

    assert len(offenders) == 1
    assert reason in offenders[0]


def test_an_unrelated_schema_is_not_mistaken_for_a_copy(tmp_path: Path) -> None:
    unrelated = tmp_path / "contracts" / "evidence.schema.json"
    unrelated.parent.mkdir(parents=True)
    unrelated.write_text('{"$id":"https://example.org/spec-kitty/evidence","type":"object"}', encoding="utf-8")

    assert _offenders(tmp_path, [Path("contracts/evidence.schema.json")]) == []


# ----- the fetch is mandatory, pinned, and the pin follows the floor -----------------------------------------------------------


def _pin(text: str) -> dict[str, str]:
    values = {}
    for line in text.splitlines():
        if line.strip() and not line.startswith("#"):
            key, _, value = line.partition("=")
            values[key.strip()] = value.strip()
    return values


def _pin_problems(pin: dict[str, str]) -> list[str]:
    problems = []
    if not re.fullmatch(r"[0-9a-f]{64}", pin.get("sha256", "")):
        problems.append("sha256 is not 64 lower-case hex characters")
    match = _WHEEL_URL.fullmatch(pin.get("url", ""))
    if match is None:
        problems.append("url is not a files.pythonhosted.org https URL of a spec_kitty_events py3-none-any wheel")
    elif match.group("version") != pin.get("version"):
        problems.append("url names another version than version")
    return problems


def test_the_pin_names_one_released_wheel_with_its_checksum() -> None:
    pin = _pin((_REPO_ROOT / _PIN).read_text(encoding="utf-8"))
    assert set(pin) == {"version", "url", "sha256"}
    assert _pin_problems(pin) == []


@pytest.mark.parametrize(
    "damage",
    [
        {"sha256": "0" * 63},
        {"sha256": "G" * 64},
        {"url": "http://files.pythonhosted.org/packages/aa/bb/" + "0" * 60 + "/spec_kitty_events-10.5.0-py3-none-any.whl"},
        {"url": "https://example.org/spec_kitty_events-10.5.0-py3-none-any.whl"},
        {"version": "10.5.1"},
    ],
)
def test_a_damaged_pin_is_found(damage: dict[str, str]) -> None:
    pin = _pin((_REPO_ROOT / _PIN).read_text(encoding="utf-8"))
    pin.update(damage)
    assert _pin_problems(pin)


def test_the_fetch_task_is_mandatory_and_wired() -> None:
    script = (_REPO_ROOT / _SCRIPT).read_text(encoding="utf-8")
    adapter = (_REPO_ROOT / _ADAPTER_BUILD).read_text(encoding="utf-8")

    assert 'register<FetchEventsContract>("fetchEventsContract")' in script
    assert "dependsOn(fetchEventsContract)" in script
    assert "events-fetch.gradle.kts" in adapter, "the adapter build applies the fetch script"
    # The checksum is compared before the archive is opened.
    assert script.index("sha256 does not match") < script.index("ZipFile(wheel)")
    assert 'startsWith("https://")' in script


def test_the_pin_is_the_floor_of_pyproject_and_the_locked_version() -> None:
    pin = _pin((_REPO_ROOT / _PIN).read_text(encoding="utf-8"))
    pyproject = tomllib.loads((_REPO_ROOT / "pyproject.toml").read_text(encoding="utf-8"))
    requirement = next(Requirement(e) for e in pyproject["project"]["dependencies"] if e.startswith("spec-kitty-events"))
    lower_bounds = [Version(s.version) for s in requirement.specifier if s.operator == ">="]
    lock = tomllib.loads((_REPO_ROOT / "uv.lock").read_text(encoding="utf-8"))
    locked = next(p["version"] for p in lock["package"] if p["name"] == "spec-kitty-events")

    assert lower_bounds == [Version(pin["version"])], "the CLI floor names the released wheel the Java build consumes"
    assert locked == pin["version"], "uv.lock resolves the same release"
