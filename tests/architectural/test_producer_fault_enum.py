"""The producer fault vocabulary is closed and has no ``disabled`` class (FR-022, FR-023, OD-OPTOUT, AC-005).

There is no producer opt-out setting; a missing binary is the degraded state, named
``binary-missing``. The Python enum, the Java vocabulary and the documented list must agree, and
nothing in the client may introduce a switch that turns the producer off.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

from specify_cli.status.producer_faults import FaultClass

pytestmark = [pytest.mark.architectural]

_REPO = Path(__file__).resolve().parents[2]
_EXPECTED = {"binary-missing", "crash", "timeout", "malformed-reply", "clock-overflow", "rejected", "busy"}
_JAVA = _REPO / "services/event-producer/domain/src/main/java/ai/speckitty/producer/domain/fault/FaultReason.java"


def test_the_enum_equals_the_documented_vocabulary() -> None:
    assert {member.value for member in FaultClass} == _EXPECTED


def test_disabled_is_not_a_class() -> None:
    assert "disabled" not in {member.value for member in FaultClass}
    assert not hasattr(FaultClass, "DISABLED")


def test_the_python_enum_equals_the_java_vocabulary() -> None:
    assert _JAVA.is_file(), "floor: the Java vocabulary file is found"
    java = set(re.findall(r'[A-Z_]+\("([a-z-]+)"\)', _JAVA.read_text(encoding="utf-8")))
    assert java == _EXPECTED == {member.value for member in FaultClass}


def test_the_client_modules_read_no_opt_out_setting() -> None:
    for name in ("producer_client.py", "producer_faults.py"):
        text = (_REPO / "src/specify_cli/status" / name).read_text(encoding="utf-8")
        assert "os.environ" not in text and "getenv" not in text, f"{name} must not read an environment switch (no producer opt-out)"
        assert "SPEC_KITTY_NO_PRODUCER" not in text


def test_mutation_an_enum_with_a_disabled_member_fails_the_equality() -> None:
    mutated = _EXPECTED | {"disabled"}
    assert mutated != {member.value for member in FaultClass}
