"""The event-producer ADR set (FR-045, AC-033; plan 9, research R-19).

ADR 2026-10-13-2 is amended in place for D-1, D-2 and D-3; a new ADR records the
Java-native-binary-behind-a-thin-client decision with its deliberate gate choices;
the index lists it; the old ``fire_saas_*`` rename debt is marked discharged; and any
ADR a producer docstring names either exists here or is marked as upstream.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

pytestmark = [pytest.mark.fast]

_REPO = Path(__file__).resolve().parents[2]
_ADR_DIR = _REPO / "docs" / "adr" / "4.x"
_LEDGER_ADR = _ADR_DIR / "2026-10-13-2-the-event-ledger-is-the-publication-seam-default-on-with-local-retention.md"
_NEW_ADR = _ADR_DIR / "2026-10-15-1-event-producer-java-native-binary-behind-thin-python-client.md"
_PRODUCER_MODULES = (
    _REPO / "src" / "specify_cli" / "status" / "producer_client.py",
    _REPO / "src" / "specify_cli" / "status" / "adapters.py",
    _REPO / "src" / "specify_cli" / "status" / "producer_faults.py",
)


def _amendment(text: str) -> str:
    match = re.search(r"^## Amendment 2026-10-15[^\n]*\n(.*?)(?=^## |\Z)", text, re.MULTILINE | re.DOTALL)
    assert match, "ADR 2026-10-13-2 has no 'Amendment 2026-10-15' section"
    return match.group(1)


def test_the_amendment_names_d1_d2_and_d3() -> None:
    body = _amendment(_LEDGER_ADR.read_text(encoding="utf-8"))
    for decision in ("D-1", "D-2", "D-3"):
        assert decision in body, f"the amendment does not name {decision}"


def test_the_amendment_states_each_departure() -> None:
    body = _amendment(_LEDGER_ADR.read_text(encoding="utf-8"))
    assert "CloudEvents" in body and "flat rows" in body  # D-1
    assert "publish log" in body and "fault log" in body  # D-2 read path and D-3 files
    assert "clock state" in body  # D-3 closed list gains the clock state


def test_the_rename_debt_is_marked_discharged() -> None:
    text = _LEDGER_ADR.read_text(encoding="utf-8")
    debt = text.split("### Follow-up debt", 1)[1].split("### Consequences", 1)[0]
    assert "discharged" in debt.lower()
    assert "fire_status_fanout" in debt and "SPEC_KITTY_FANOUT_TIMEOUT" in debt


def test_the_new_adr_exists_with_its_recorded_choices() -> None:
    text = _NEW_ADR.read_text(encoding="utf-8")
    assert text.startswith("---\n")
    lowered = text.lower()
    for needle in ("jacoco", "80 percent", "90 percent", "bounded build glue", "#6193", "thinness"):
        assert needle in lowered, f"the new ADR does not record {needle!r}"


def test_the_index_lists_the_new_adr() -> None:
    index = (_ADR_DIR / "index.md").read_text(encoding="utf-8")
    assert _NEW_ADR.name in index


def test_no_adr_number_is_used_twice_on_a_date() -> None:
    seen: dict[str, str] = {}
    for path in sorted(_ADR_DIR.glob("20*.md")):
        key = "-".join(path.name.split("-")[:4])
        assert key not in seen, f"{path.name} collides with {seen[key]}"
        seen[key] = path.name


def test_every_adr_a_producer_docstring_names_resolves_or_is_marked_upstream() -> None:
    pattern = re.compile(r"``(20\d\d-\d\d-\d\d-\d-[a-z0-9-]+)``")
    named = 0
    for module in _PRODUCER_MODULES:
        lines = module.read_text(encoding="utf-8").splitlines()
        for number, line in enumerate(lines):
            for slug in pattern.findall(line):
                named += 1
                window = " ".join(lines[max(0, number - 1) : number + 2])
                assert (_ADR_DIR / f"{slug}.md").is_file() or "upstream" in window.lower(), f"{module.name}: {slug}"
    assert named >= 1  # the scan finds the references it is guarding
