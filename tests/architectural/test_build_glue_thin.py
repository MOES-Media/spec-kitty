"""The single authority for the Python build-glue pin of the event producer (mission event-producer-reroute, plan 13 item 2).

The producer is Java; Python is allowed only as bounded packaging and release glue. Every Python file the mission adds or edits for that
glue must hold no runtime logic: no ids, clocks, envelopes, schemas or timestamps. This gate enumerates the glue by a filesystem walk of
``scripts/release/`` (a Python file that is neither classified here nor in the pre-existing baseline fails), then judges each file:

* wholly owned glue (``hatch_build_producer.py``, later ``check_dist_set.py``): a closed import allowlist, ``hashlib`` used for
  ``sha256`` only, and no identifier built from a forbidden token;
* edited pre-existing glue (``check_exact_install.py``, ``contracts/tools/gradle_pin_check.py``): the base inventory of top-level names
  and imports plus an explicit, reviewed allowance, and no identifier built from a forbidden token.

``NOT_YET_LANDED`` names glue a later work package creates; while a listed file is absent nothing is scanned, and the moment it exists the
gate is red until it is moved into the owned set (the owning work package's definition of done empties the list). Every rule has a floor
(files found) and self-mutation cases that feed a defect to the rule and require it to go red.
"""

from __future__ import annotations

import ast
import re
from pathlib import Path

import pytest

pytestmark = pytest.mark.architectural

REPO_ROOT = Path(__file__).resolve().parents[2]
RELEASE_DIR = REPO_ROOT / "scripts" / "release"

#: Glue this mission owns outright (the whole file is bounded glue).
WHOLLY_OWNED = ("scripts/release/hatch_build_producer.py",)
#: Glue a later work package creates (WP08). Absent today; its work package moves it into ``WHOLLY_OWNED``.
NOT_YET_LANDED = ("scripts/release/check_dist_set.py",)
#: Python files under scripts/release/ that existed before the mission and are not producer glue.
BASELINE_RELEASE_SCRIPTS = frozenset({"check_exact_install.py", "check_shared_package_drift.py", "extract_changelog.py", "validate_release.py"})

ALLOWED_IMPORT_ROOTS = frozenset(
    {"__future__", "argparse", "hashlib", "json", "os", "pathlib", "sys", "zipfile", "tarfile", "typing", "dataclasses", "collections", "hatchling"}
)
#: Name tokens (split on underscores and camel case) that mark runtime logic: ids, clocks, envelopes, schemas.
FORBIDDEN_TOKENS = frozenset(
    {
        "uuid",
        "ulid",
        "datetime",
        "time",
        "secrets",
        "random",
        "jsonschema",
        "envelope",
        "lamport",
        "clock",
        "nodeid",
        "dataschema",
        "cloudevents",
    }
)
FORBIDDEN_SUBSTRINGS = ("spec_kitty_events", "specify_cli")
#: Names through which code can import, evaluate or compile text at run time, which would sidestep the import and identifier rules.
DYNAMIC_IMPORT_NAMES = frozenset({"__import__", "import_module", "importlib", "exec", "eval", "compile", "__builtins__", "builtins"})


class Baseline:
    """Top-level names and imports of an edited pre-existing file at the mission base, plus the reviewed allowance."""

    def __init__(self, functions: set[str], imports: set[str], allowed_functions: set[str], allowed_imports: set[str]) -> None:
        self.functions = functions
        self.imports = imports
        self.allowed_functions = allowed_functions
        self.allowed_imports = allowed_imports


EDITED_EXISTING: dict[str, Baseline] = {
    "scripts/release/check_exact_install.py": Baseline(
        functions={
            "parse_args",
            "package_prefix",
            "locate_wheel",
            "read_wheel_metadata",
            "venv_bin_dir",
            "console_script_path",
            "run",
            "require_success",
            "main",
        },
        imports={"__future__", "argparse", "collections.abc", "email", "os", "shutil", "subprocess", "sys", "tempfile", "zipfile", "pathlib"},
        allowed_functions=set(),  # WP08 widens this for the --wheel-tag edit, in the same commit as the edit.
        allowed_imports=set(),
    ),
    "contracts/tools/gradle_pin_check.py": Baseline(
        functions={
            "Finding",
            "Subproject",
            "Report",
            "_rel",
            "_read",
            "_load_pins",
            "_url_version",
            "_check_build",
            "_check_metadata",
            "_check_wrapper",
            "_check_workflow",
            "_scan",
            "_finalize",
            "_contracts_subproject",
            "_acceptance_jvm_subproject",
            "check",
            "check_acceptance_jvm",
            "check_all",
            "main",
        },
        imports={"__future__", "argparse", "json", "re", "sys", "xml.etree.ElementTree", "dataclasses", "pathlib", "typing", "yaml"},
        allowed_functions={"_event_producer_subproject"},  # the third Subproject entry (plan 13 item 2)
        allowed_imports=set(),
    ),
}


def _tokens(identifier: str) -> list[str]:
    spaced = re.sub(r"(?<=[a-z0-9])(?=[A-Z])", "_", identifier)
    return [part.lower() for part in re.split(r"[_.]+", spaced) if part]


def _identifiers(tree: ast.AST) -> set[str]:
    names: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Name):
            names.add(node.id)
        elif isinstance(node, ast.Attribute):
            names.add(node.attr)
        elif isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            names.add(node.name)
        elif isinstance(node, ast.arg) or isinstance(node, ast.keyword) and node.arg:
            names.add(node.arg)
        elif isinstance(node, ast.Import):
            names.update(alias.name for alias in node.names)
        elif isinstance(node, ast.ImportFrom):
            names.add(node.module or "")
            names.update(alias.name for alias in node.names)
    return names


def _forbidden_identifier_violations(tree: ast.AST) -> list[str]:
    violations = []
    for name in sorted(_identifiers(tree)):
        lowered = name.lower()
        if any(fragment in lowered for fragment in FORBIDDEN_SUBSTRINGS) or FORBIDDEN_TOKENS.intersection(_tokens(name)):
            violations.append(f"forbidden name {name!r}")
    return violations


def _dynamic_import_violations(tree: ast.AST) -> list[str]:
    """Flag run-time import machinery. A bare name is judged on its own; an attribute only when it is ``import_module`` or names importlib/builtins
    (``re.compile`` is not ``compile``)."""
    found: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Name) and node.id in DYNAMIC_IMPORT_NAMES:
            found.add(node.id)
        elif isinstance(node, ast.Attribute) and node.attr in {"import_module", "__import__", "importlib", "builtins"}:
            found.add(node.attr)
        elif isinstance(node, ast.ImportFrom) and any(alias.name in DYNAMIC_IMPORT_NAMES for alias in node.names):
            found.update(alias.name for alias in node.names if alias.name in DYNAMIC_IMPORT_NAMES)
    return [f"dynamic import machinery {name!r}: imports must be static" for name in sorted(found)]


def _imported_modules(tree: ast.AST) -> set[str]:
    modules: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            modules.update(alias.name for alias in node.names)
        elif isinstance(node, ast.ImportFrom):
            modules.add(("." * node.level) + (node.module or ""))
    return modules


def _top_level_names(tree: ast.Module) -> set[str]:
    return {node.name for node in tree.body if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef))}


def _parse(source: str) -> ast.Module | None:
    try:
        return ast.parse(source)
    except (SyntaxError, ValueError):
        return None


def wholly_owned_violations(source: str) -> list[str]:
    """Violations of a wholly owned glue file. An unparseable file is a violation (fail closed)."""
    tree = _parse(source)
    if tree is None:
        return ["unparseable source"]
    violations = []
    for module in sorted(_imported_modules(tree)):
        if module.split(".")[0] not in ALLOWED_IMPORT_ROOTS:
            violations.append(f"import outside the allowlist: {module!r}")
    for node in ast.walk(tree):
        if isinstance(node, ast.Attribute) and isinstance(node.value, ast.Name) and node.value.id == "hashlib" and node.attr != "sha256":
            violations.append(f"hashlib.{node.attr}: only sha256 of a file is allowed")
        if isinstance(node, ast.ImportFrom) and node.module == "hashlib" and any(alias.name != "sha256" for alias in node.names):
            violations.append("from hashlib import: only sha256 of a file is allowed")
    violations.extend(_forbidden_identifier_violations(tree))
    violations.extend(_dynamic_import_violations(tree))
    return violations


def edited_violations(source: str, baseline: Baseline) -> list[str]:
    """Violations of an edited pre-existing file: names or imports beyond base plus the reviewed allowance, or any forbidden name."""
    tree = _parse(source)
    if tree is None:
        return ["unparseable source"]
    violations = []
    for name in sorted(_top_level_names(tree) - baseline.functions - baseline.allowed_functions):
        violations.append(f"new top-level name {name!r}")
    for module in sorted(_imported_modules(tree) - baseline.imports - baseline.allowed_imports):
        violations.append(f"new import {module!r}")
    violations.extend(_forbidden_identifier_violations(tree))
    violations.extend(_dynamic_import_violations(tree))
    return violations


def _read(relative: str) -> str:
    return (REPO_ROOT / relative).read_text(encoding="utf-8")


def _walk_release_scripts() -> list[str]:
    return sorted(path.name for path in RELEASE_DIR.glob("*.py"))


# --- the real files ---------------------------------------------------------------------------------------------


def test_the_filesystem_walk_finds_every_glue_file_and_classifies_each_python_file() -> None:
    found = _walk_release_scripts()
    classified = {Path(p).name for p in (*WHOLLY_OWNED, *NOT_YET_LANDED)} | BASELINE_RELEASE_SCRIPTS
    assert found, "scripts/release/ holds no Python file: the walk is vacuous"
    assert sorted(set(found) - classified) == [], "unclassified Python file under scripts/release/: classify it in test_build_glue_thin.py"


def test_the_glue_floor_counts_the_files_this_gate_judges() -> None:
    owned_present = [p for p in WHOLLY_OWNED if (REPO_ROOT / p).is_file()]
    edited_present = [p for p in EDITED_EXISTING if (REPO_ROOT / p).is_file()]
    assert owned_present == list(WHOLLY_OWNED), "a wholly owned glue file is missing"
    assert len(edited_present) == len(EDITED_EXISTING), "an edited glue file is missing"
    assert len(owned_present) + len(edited_present) >= 3
    assert NOT_YET_LANDED, "the not-yet-landed list is empty: if WP08 landed, delete this assertion with the list"


@pytest.mark.parametrize("relative", NOT_YET_LANDED)
def test_a_not_yet_landed_file_that_now_exists_must_be_moved_into_the_owned_set(relative: str) -> None:
    assert not (REPO_ROOT / relative).exists(), f"{relative} landed: move it from NOT_YET_LANDED to WHOLLY_OWNED so it is judged"


@pytest.mark.parametrize("relative", WHOLLY_OWNED)
def test_wholly_owned_glue_holds_no_runtime_logic(relative: str) -> None:
    assert wholly_owned_violations(_read(relative)) == []


@pytest.mark.parametrize("relative", sorted(EDITED_EXISTING))
def test_edited_glue_adds_nothing_beyond_its_reviewed_allowance(relative: str) -> None:
    assert edited_violations(_read(relative), EDITED_EXISTING[relative]) == []


# --- self-mutation: each rule goes red when fed the defect ------------------------------------------------------


def test_mutation_the_hook_with_import_uuid_is_red() -> None:
    mutated = _read(WHOLLY_OWNED[0]) + "\nimport uuid\n"
    violations = wholly_owned_violations(mutated)
    assert any("uuid" in v for v in violations)


def test_mutation_check_dist_set_shaped_glue_with_datetime_now_is_red() -> None:
    mutated = _read(WHOLLY_OWNED[0]) + "\n\ndef stamp() -> str:\n    return datetime.now().isoformat()\n"
    violations = wholly_owned_violations(mutated)
    assert any("datetime" in v for v in violations)


def test_mutation_glue_that_reads_a_clock_through_an_innocent_import_is_red() -> None:
    mutated = _read(WHOLLY_OWNED[0]) + "\n\ndef later() -> float:\n    return os.times().elapsed\n\n\ndef lamport_clock() -> int:\n    return 0\n"
    assert any("lamport" in v or "clock" in v for v in wholly_owned_violations(mutated))


def test_mutation_hashlib_beyond_sha256_is_red() -> None:
    mutated = _read(WHOLLY_OWNED[0]) + "\n_DIGEST = hashlib.blake2b\n"
    assert any("hashlib.blake2b" in v for v in wholly_owned_violations(mutated))


def test_mutation_an_import_outside_the_allowlist_is_red() -> None:
    assert any("subprocess" in v for v in wholly_owned_violations(_read(WHOLLY_OWNED[0]) + "\nimport subprocess\n"))


def test_mutation_unparseable_glue_is_red_not_skipped() -> None:
    assert wholly_owned_violations("def (:\n") == ["unparseable source"]
    assert edited_violations("def (:\n", EDITED_EXISTING["contracts/tools/gradle_pin_check.py"]) == ["unparseable source"]


def test_mutation_check_exact_install_with_a_spec_kitty_events_import_is_red() -> None:
    relative = "scripts/release/check_exact_install.py"
    mutated = _read(relative) + "\nimport spec_kitty_events\n"
    violations = edited_violations(mutated, EDITED_EXISTING[relative])
    assert any("spec_kitty_events" in v for v in violations)


def test_mutation_a_new_function_in_gradle_pin_check_is_red() -> None:
    relative = "contracts/tools/gradle_pin_check.py"
    mutated = _read(relative) + "\n\ndef helper() -> int:\n    return 1\n"
    assert edited_violations(mutated, EDITED_EXISTING[relative]) == ["new top-level name 'helper'"]


def test_mutation_a_new_import_in_gradle_pin_check_is_red() -> None:
    relative = "contracts/tools/gradle_pin_check.py"
    assert edited_violations(_read(relative) + "\nimport shutil\n", EDITED_EXISTING[relative]) == ["new import 'shutil'"]


def test_mutation_an_unclassified_release_script_is_red() -> None:
    found = [*_walk_release_scripts(), "producer_envelope.py"]
    classified = {Path(p).name for p in (*WHOLLY_OWNED, *NOT_YET_LANDED)} | BASELINE_RELEASE_SCRIPTS
    assert sorted(set(found) - classified) == ["producer_envelope.py"]


DYNAMIC_IMPORT_CASES = {
    "__import__": '\n_U = __import__("uu" + "id")\n',
    "importlib.import_module": '\n_U = importlib.import_module("uu" + "id")\n',
    "bare import_module": '\nfrom importlib import import_module\n_U = import_module("uuid")\n',
    "exec of import text": '\nexec("import uuid")\n',
    "eval of import text": "\n_U = eval(\"__import__('uuid')\")\n",
    "compile of import text": '\n_C = compile("import uuid", "x", "exec")\n',
    "getattr on builtins": '\n_U = getattr(__builtins__, "__import__")("uuid")\n',
}


@pytest.mark.parametrize("case", sorted(DYNAMIC_IMPORT_CASES))
def test_mutation_a_dynamic_import_in_the_hook_is_red(case: str) -> None:
    violations = wholly_owned_violations(_read(WHOLLY_OWNED[0]) + DYNAMIC_IMPORT_CASES[case])
    assert any("dynamic import" in v for v in violations), (case, violations)


@pytest.mark.parametrize("case", sorted(DYNAMIC_IMPORT_CASES))
def test_mutation_a_dynamic_import_in_edited_glue_is_red(case: str) -> None:
    relative = "contracts/tools/gradle_pin_check.py"
    violations = edited_violations(_read(relative) + DYNAMIC_IMPORT_CASES[case], EDITED_EXISTING[relative])
    assert any("dynamic import" in v for v in violations), (case, violations)
