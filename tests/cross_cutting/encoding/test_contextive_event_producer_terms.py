"""The event-producer glossary terms exist and the Contextive files are current (FR-046, AC-033).

No CI job runs ``scripts/generate_contextive_glossaries.py check``; this test is its
only caller, so a term removed from the canonical glossary, or a generated file left
stale, fails here.
"""

from __future__ import annotations

import shutil
from pathlib import Path

import pytest

import scripts.generate_contextive_glossaries as gen

pytestmark = [pytest.mark.integration]

_REPO = Path(__file__).resolve().parents[3]
_TERMS = (
    "Producer",
    "Publish seam",
    "Publish log",
    "Ledger",
    "Fan-out",
    "Clockless row",
    "Clocked row",
    "Fault log",
)


def _terms(context_file: Path) -> dict[str, str]:
    return {term.name: term.definition for term in gen.parse_context_file(context_file).terms}


def test_every_event_producer_term_is_defined_in_the_canonical_glossary() -> None:
    defined = _terms(_REPO / "docs" / "context" / "system-events.md")
    for term in _TERMS:
        assert defined.get(term, "").strip(), f"{term} has no definition in docs/context/system-events.md"


def test_the_fan_out_definition_says_it_is_not_sync() -> None:
    definition = _terms(_REPO / "docs" / "context" / "system-events.md")["Fan-out"]
    assert 'not called "sync"' in definition


def test_every_term_reaches_the_generated_contextive_file() -> None:
    generated = (_REPO / "src" / "specify_cli" / ".contextive" / "system-events.yml").read_text(encoding="utf-8")
    for term in _TERMS:
        assert f'name: "{term}"' in generated, term


def test_the_status_scope_imports_the_system_events_context() -> None:
    scope = (_REPO / "src" / "specify_cli" / "status" / ".contextive.yml").read_text(encoding="utf-8")
    assert "system-events.yml" in scope


def test_the_generator_check_passes() -> None:
    assert gen.main(["check"]) == 0


def test_mutation_a_removed_term_makes_the_check_fail(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    contexts = tmp_path / "context"
    shutil.copytree(_REPO / "docs" / "context", contexts)
    source = contexts / "system-events.md"
    text = source.read_text(encoding="utf-8")
    assert "### Fault log" in text
    source.write_text(text.replace("### Fault log", "### Fault journal"), encoding="utf-8")
    monkeypatch.setattr(gen, "GLOSSARY_CONTEXTS_DIR", contexts)

    assert gen.main(["check"]) == 1
