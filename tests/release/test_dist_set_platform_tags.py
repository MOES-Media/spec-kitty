"""``scripts/release/dist-set.json`` is the single authority for the producer wheel tags (mission event-producer-reroute, plan 2).

It must hold exactly the three legs ``producer-native.yml`` builds, with the pinned platform tags, plus the ``sdist`` and ``pure_wheels``
keys WP08's checker reads. Every rule has a floor (legs found) and mutation cases (remove a leg, add an unexpected one, bend a tag).
"""

from __future__ import annotations

import copy
import json
from pathlib import Path
from typing import Any

import pytest
import yaml

pytestmark = [pytest.mark.fast]

ROOT = Path(__file__).resolve().parents[2]
DIST_SET = ROOT / "scripts" / "release" / "dist-set.json"
PRODUCER_NATIVE = ROOT / ".github" / "workflows" / "producer-native.yml"

EXPECTED_TAGS = {
    "linux-x86_64": "py3-none-manylinux_2_28_x86_64",
    "macos-arm64": "py3-none-macosx_11_0_arm64",
    "windows-x86_64": "py3-none-win_amd64",
}
EXPECTED_KEYS = {"v", "platform_wheels", "sdist", "pure_wheels"}


def _workflow_legs(workflow: Path) -> list[str]:
    document = yaml.safe_load(workflow.read_text(encoding="utf-8"))
    jobs = document["jobs"]
    legs: list[str] = []
    for body in jobs.values():
        matrix = (body.get("strategy") or {}).get("matrix") or {}
        legs.extend(str(entry["leg"]) for entry in matrix.get("include", []) if isinstance(entry, dict) and "leg" in entry)
    return legs


def violations(data: Any, workflow_legs: list[str]) -> list[str]:
    """Everything wrong with a dist-set document. A document of the wrong shape is a violation, never an exception."""
    if not isinstance(data, dict):
        return ["dist-set.json is not an object"]
    found: list[str] = []
    if set(data) != EXPECTED_KEYS:
        found.append(f"keys are {sorted(data)}, expected {sorted(EXPECTED_KEYS)}")
    if data.get("v") != 1:
        found.append("v is not 1")
    if data.get("sdist") != 1:
        found.append("sdist is not 1")
    if data.get("pure_wheels") != ["py3-none-any"]:
        found.append("pure_wheels is not ['py3-none-any']")
    wheels = data.get("platform_wheels")
    if not isinstance(wheels, list) or not all(isinstance(w, dict) and set(w) == {"leg", "tag"} for w in wheels):
        return [*found, "platform_wheels is not a list of {leg, tag} objects"]
    legs = [w["leg"] for w in wheels]
    if len(set(legs)) != len(legs):
        found.append("a leg is listed twice")
    if set(legs) != set(EXPECTED_TAGS):
        found.append(f"legs are {sorted(legs)}, expected {sorted(EXPECTED_TAGS)}")
    for wheel in wheels:
        expected = EXPECTED_TAGS.get(wheel["leg"])
        if expected is not None and wheel["tag"] != expected:
            found.append(f"leg {wheel['leg']} has tag {wheel['tag']}, expected {expected}")
    if sorted(workflow_legs) != sorted(legs):
        found.append(f"producer-native.yml builds {sorted(workflow_legs)} but dist-set.json lists {sorted(legs)}")
    return found


def _real() -> dict[str, Any]:
    return json.loads(DIST_SET.read_text(encoding="utf-8"))


def test_dist_set_holds_exactly_the_three_legs_the_workflow_builds() -> None:
    legs = _workflow_legs(PRODUCER_NATIVE)
    data = _real()
    assert len(legs) == 3, "floor: producer-native.yml must declare three matrix legs"
    assert violations(data, legs) == []
    assert {w["leg"]: w["tag"] for w in data["platform_wheels"]} == EXPECTED_TAGS


def test_dist_set_carries_the_sdist_and_pure_wheel_keys_wp08_reads() -> None:
    data = _real()
    assert data["sdist"] == 1
    assert data["pure_wheels"] == ["py3-none-any"]


def test_the_platform_tags_are_platform_specific_py3_none_tags() -> None:
    for wheel in _real()["platform_wheels"]:
        assert wheel["tag"].startswith("py3-none-")
        assert not wheel["tag"].endswith("-any")


def test_mutation_removing_a_leg_is_red() -> None:
    data = copy.deepcopy(_real())
    data["platform_wheels"].pop()
    found = violations(data, _workflow_legs(PRODUCER_NATIVE))
    assert any("legs are" in v for v in found)
    assert any("producer-native.yml builds" in v for v in found)


def test_mutation_adding_an_unexpected_leg_is_red() -> None:
    data = copy.deepcopy(_real())
    data["platform_wheels"].append({"leg": "linux-aarch64", "tag": "py3-none-manylinux_2_28_aarch64"})
    found = violations(data, _workflow_legs(PRODUCER_NATIVE))
    assert any("legs are" in v for v in found)


def test_mutation_a_workflow_with_an_extra_leg_is_red() -> None:
    found = violations(_real(), [*_workflow_legs(PRODUCER_NATIVE), "linux-aarch64"])
    assert any("producer-native.yml builds" in v for v in found)


def test_mutation_a_bent_tag_is_red() -> None:
    data = copy.deepcopy(_real())
    data["platform_wheels"][0]["tag"] = "py3-none-manylinux_2_17_x86_64"
    assert any("has tag" in v for v in violations(data, _workflow_legs(PRODUCER_NATIVE)))


def test_mutation_missing_sdist_or_pure_wheels_keys_is_red() -> None:
    for key in ("sdist", "pure_wheels"):
        data = copy.deepcopy(_real())
        del data[key]
        assert any("keys are" in v for v in violations(data, _workflow_legs(PRODUCER_NATIVE)))


def test_mutation_a_document_of_the_wrong_shape_is_red_not_an_exception() -> None:
    assert violations([], []) == ["dist-set.json is not an object"]
    assert any("platform_wheels is not" in v for v in violations({"v": 1, "platform_wheels": "x", "sdist": 1, "pure_wheels": ["py3-none-any"]}, []))
