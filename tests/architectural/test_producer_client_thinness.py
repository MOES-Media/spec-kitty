"""The event-producer client stays thin (FR-026, FR-027, AC-012; plan 4.2, research R-16).

Python may do exactly one thing for the producer: a thin client that locates the
binary, hands it the seam's persisted row and records faults. It holds no schema,
id, event clock, order, dedupe or type knowledge. This gate enforces that with
five rules (rule (b), the journal stamping, belongs to WP07):

(a) the three new modules (``producer_client.py``, ``producer_faults.py``, the
    doctor sibling): a closed import allowlist, no write-mode open of the
    publish-log path, ``json.loads`` only in the one decode site per module, a
    wall-clock read only inside ``_now_utc`` of ``producer_faults.py``, and the
    exact top-level symbol set of plan 4.1;
(c) the emission-area module census (``status/``, ``events/``, ``retrospective/``,
    ``decisions/`` plus the three new files) equals a pinned list, so a new module
    such as ``status/_mint_id.py`` fails, whether or not git knows it;
(d) the client imports none of the Zeitgeist bridge's envelope code;
(e) every existing Python file of the allowed-surface table (plan 4.2) keeps its
    top-level function, class and method names and its imports within the pinned
    base plus that file's row, so a helper such as ``_mint_id`` or an import of
    ``uuid`` added to ``emit.py`` fails.

The gate's purpose is to catch honest drift, a contributor reaching for a clock or an id by habit,
not to resist an adversary: deliberate evasions (stdlib-internal aliases, string-built keys,
lambda defaults) are out of scope.

Every rule has a floor (the thing scanned is found and counted above zero) and a
self-mutation case that must fail. Rules (c) and (e) walk the filesystem, never a
diff, so an untracked file is seen.

Debt record (charter Burn-down Policy). The pinned lists of rules (c) and (e)
live in ``producer_client_thinness_base.json``. They are debt: owner = the #6193
mission (the event-log write side that retires the Python writers); drain = they
are deleted when #6193 or #6195 lands. They are fixed pins that fail on growth,
not mutable allowlists, so no ``_baselines.yaml`` row exists or is claimed.
"""

from __future__ import annotations

import ast
import json
import shutil
from collections.abc import Callable
from pathlib import Path

import pytest

pytestmark = [pytest.mark.architectural]

_REPO = Path(__file__).resolve().parents[2]
_SRC = _REPO / "src"
_BASE = json.loads((Path(__file__).with_name("producer_client_thinness_base.json")).read_text(encoding="utf-8"))

_CLIENT = "specify_cli/status/producer_client.py"
_FAULTS = "specify_cli/status/producer_faults.py"
_DOCTOR = "specify_cli/cli/commands/_event_producer_doctor.py"
_NEW_MODULES = (_CLIENT, _FAULTS, _DOCTOR)

# --- rule (a): the exact surface of the three new modules (plan 4.1) -----------------------

_SYMBOLS = {
    _CLIENT: {"locate_binary", "effective_client_timeout", "ProducerClient", "produce_status_handler", "produce_lifecycle_handler"},
    _FAULTS: {"FaultClass", "record_fault", "record_notice", "write_stderr_warning", "fault_summary", "_now_utc", "_read_fault_rows"},
    _DOCTOR: {"register", "events_health", "events_retention"},
}

# Closed import allowlists, as (module) or (module:name) prefixes. Everything else fails, and the
# forbidden vocabulary below is named so a failure says why.
_IMPORTS = {
    _CLIENT: {"__future__", "json", "subprocess", "sys", "collections.abc", "pathlib", "typing", "specify_cli.status.producer_faults"},
    _FAULTS: {"__future__", "json", "sys", "collections.abc", "enum", "pathlib", "typing", "kernel.clock", "specify_cli.core.paths"},
    _DOCTOR: {
        "__future__",
        "json",
        "pathlib",
        "typing",
        "typer",
        "ruamel.yaml",
        "specify_cli.core.paths",
        "specify_cli.status",
        ".",
        "._doctor_shared",
    },
}
_FORBIDDEN_ROOTS = {"uuid", "ulid", "hashlib", "jsonschema", "spec_kitty_events", "secrets", "random", "datetime", "time"}
_BRIDGE_ENVELOPE_NAMES = {"_broadcast_status_transition", "_broadcast_lifecycle_envelope", "_envelope_bookkeeping"}
_PUBLISH_MARKER = ".kittify/events/publish"
# The client may import only these names from producer_faults: borrowing its clock helper would stamp time.
_CLIENT_FAULT_NAMES = {"FaultClass", "record_fault", "record_notice"}
_FAULTS_MODULE = "specify_cli.status.producer_faults"
_WALL_CLOCK_CALLS = {
    "_now_utc",
    "now_utc",
    "now_utc_iso",
    "now_utc_stamp",
    "now_utc_compact_stamp",
    "now_utc_seconds",
    "now_epoch",
    "now",
    "utcnow",
    "time",
    "today",
    "time_ns",
    "gmtime",
    "localtime",
    "ctime",
    "fromtimestamp",
    "utcfromtimestamp",
    "monotonic",
    "monotonic_ns",
    "perf_counter",
    "perf_counter_ns",
}
# Dynamic-lookup spellings that reach a clock without naming it: banned outright in the three new modules.
_DYNAMIC_LOOKUP_NAMES = {"__import__", "__globals__", "importlib", "import_module", "getattr", "globals", "vars", "eval", "exec"}
# ``modules`` is a lookup only as ``sys.modules`` (or ``from sys import modules``); a local name is ordinary.
# Modules that hand out a clock: any name bound from one of them is a clock name, under any alias.
_CLOCK_SOURCES = {"kernel.clock", "time", "datetime"}
# Types imported from a clock source are not reads; their ``now()``-style calls are caught by the vocabulary above.
_CLOCK_TYPES = {"date", "datetime", "timedelta", "timezone", "tzinfo", "UTC"}
_DECODE_SITES = {_CLIENT: {"_read_reply"}, _FAULTS: {"_read_fault_rows"}, _DOCTOR: set()}


def _imports(tree: ast.AST) -> list[tuple[str, str]]:
    found: list[tuple[str, str]] = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            found.extend((alias.name, "") for alias in node.names)
        elif isinstance(node, ast.ImportFrom):
            module = ("." * node.level) + (node.module or "")
            found.extend((module, alias.name) for alias in node.names)
    return found


def _top_level_symbols(tree: ast.Module) -> set[str]:
    return {node.name for node in tree.body if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef))}


def _enclosing_function_names(tree: ast.Module) -> dict[int, str]:
    """Map every node id to the name of its enclosing function (innermost), for call-site rules."""
    owner: dict[int, str] = {}

    def visit(node: ast.AST, current: str) -> None:
        for child in ast.iter_child_nodes(node):
            name = child.name if isinstance(child, (ast.FunctionDef, ast.AsyncFunctionDef)) else current
            owner[id(child)] = name
            visit(child, name)

    visit(tree, "")
    return owner


def _is_write_open(call: ast.Call) -> bool:
    func = call.func
    name = func.attr if isinstance(func, ast.Attribute) else getattr(func, "id", "")
    if name in {"write_text", "write_bytes", "touch"}:
        return True
    if name != "open":
        return False
    # ``open(path, mode)`` takes the mode second; ``Path.open(mode)`` takes it first.
    positional = call.args[1:2] if isinstance(func, ast.Name) else call.args[:1]
    modes = [arg.value for arg in positional if isinstance(arg, ast.Constant) and isinstance(arg.value, str)]
    modes += [kw.value.value for kw in call.keywords if kw.arg == "mode" and isinstance(kw.value, ast.Constant) and isinstance(kw.value.value, str)]
    return any(set(mode) & set("wax+") for mode in modes)


def _mentions_publish_log(tree: ast.AST) -> bool:
    literals = [node.value for node in ast.walk(tree) if isinstance(node, ast.Constant) and isinstance(node.value, str)]
    return any(_PUBLISH_MARKER in text for text in literals) or ("events" in literals and "publish" in literals)


def _dynamic_lookup_problems(label: str, node: ast.Name | ast.Attribute) -> list[str]:
    ident = node.id if isinstance(node, ast.Name) else node.attr
    hit = ident in _DYNAMIC_LOOKUP_NAMES or (isinstance(node, ast.Attribute) and ident == "modules" and getattr(node.value, "id", "") == "sys")
    return [f"{label}: dynamic lookup via {ident} can reach the wall clock unseen"] if hit else []


def _dynamic_lookup_import_problems(label: str, module: str, name: str) -> list[str]:
    return [f"{label}: dynamic lookup via modules can reach the wall clock unseen"] if (module, name) == ("sys", "modules") else []


def _clock_bindings(tree: ast.AST) -> tuple[set[str], set[str]]:
    """The local names an import binds to a clock source: (functions, modules), whatever the alias."""
    functions: set[str] = set()
    modules: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.ImportFrom) and node.level == 0 and node.module in _CLOCK_SOURCES:
            functions.update(alias.asname or alias.name for alias in node.names if alias.name not in _CLOCK_TYPES)
        elif isinstance(node, ast.Import):
            modules.update(
                alias.asname or alias.name.split(".")[0] for alias in node.names if alias.name in _CLOCK_SOURCES or alias.name.split(".")[0] in _CLOCK_SOURCES
            )
    return functions, modules


def _call_root(func: ast.expr) -> str:
    while isinstance(func, ast.Attribute):
        func = func.value
    return func.id if isinstance(func, ast.Name) else ""


# Method names that read the clock off a date/time TYPE; as a bare attribute (a value) they are still a read.
_CLOCK_METHODS = {"now", "today", "utcnow", "fromtimestamp", "utcfromtimestamp"}
# Bare names that are clock reads in this codebase whether or not an import binds them (``now`` etc. are ordinary locals).
_CLOCK_VALUE_NAMES = _WALL_CLOCK_CALLS - {"now", "time", "today", "ctime", "gmtime", "localtime", "monotonic", "perf_counter"} | {"_now_utc"}


def _clock_value_problems(label: str, tree: ast.Module, owner: dict[int, str], names: tuple[set[str], set[str]]) -> list[str]:
    """Any clock reference that is not a call's callee is a read waiting to happen (default, alias, argument, return, container).

    Calls are judged by the call rule; a reference in any other position fails unless it sits inside ``_now_utc``.
    """
    clock_functions, clock_modules = names
    callees = {id(node.func) for node in ast.walk(tree) if isinstance(node, ast.Call)}
    problems: list[str] = []
    for node in ast.walk(tree):
        if id(node) in callees or not isinstance(getattr(node, "ctx", None), ast.Load):
            continue
        if label == _FAULTS and owner.get(id(node), "") == "_now_utc":
            continue
        if isinstance(node, ast.Name):
            hit = node.id in clock_functions or node.id in clock_modules or node.id in _CLOCK_VALUE_NAMES
            shown = node.id
        elif isinstance(node, ast.Attribute):
            hit = _call_root(node) in clock_modules or node.attr in _CLOCK_METHODS or node.attr in _CLOCK_VALUE_NAMES
            shown = node.attr
        else:
            continue
        if hit:
            problems.append(f"{label}: clock referenced as a value ({shown}) outside _now_utc ({owner.get(id(node), '') or 'module level'})")
    return problems


def new_module_violations(label: str, source: str) -> list[str]:
    """Rules (a) and (d) for one of the three new modules, given its source text."""
    tree = ast.parse(source)
    problems: list[str] = []
    allowed = _IMPORTS[label]
    for module, name in _imports(tree):
        root = module.lstrip(".").split(".")[0]
        if root in _FORBIDDEN_ROOTS:
            problems.append(f"{label}: forbidden import {module}:{name}")
        elif module not in allowed and not any(module == prefix or module.startswith(prefix + ".") for prefix in allowed if prefix != "."):
            problems.append(f"{label}: import {module}:{name} is outside the closed allowlist")
        problems.extend(_dynamic_lookup_import_problems(label, module, name))
        if name in _BRIDGE_ENVELOPE_NAMES:
            problems.append(f"{label}: imports the bridge envelope code {name}")
        if label == _CLIENT and module == _FAULTS_MODULE and name not in _CLIENT_FAULT_NAMES:
            problems.append(f"{label}: imports producer_faults:{name}, outside {sorted(_CLIENT_FAULT_NAMES)}")
    symbols = _top_level_symbols(tree)
    if symbols != _SYMBOLS[label]:
        problems.append(f"{label}: top-level symbols differ: extra={sorted(symbols - _SYMBOLS[label])} missing={sorted(_SYMBOLS[label] - symbols)}")
    owner = _enclosing_function_names(tree)
    clock_functions, clock_modules = _clock_bindings(tree)
    problems.extend(_clock_value_problems(label, tree, owner, (clock_functions, clock_modules)))
    for node in ast.walk(tree):
        if isinstance(node, (ast.Name, ast.Attribute)):
            ident = node.id if isinstance(node, ast.Name) else node.attr
            if ident in _BRIDGE_ENVELOPE_NAMES:
                problems.append(f"{label}: names the bridge envelope code {ident}")
            problems.extend(_dynamic_lookup_problems(label, node))
        if not isinstance(node, ast.Call):
            continue
        func = node.func
        callee = func.attr if isinstance(func, ast.Attribute) else getattr(func, "id", "")
        where = owner.get(id(node), "")
        if callee == "loads" and where not in _DECODE_SITES[label]:
            problems.append(f"{label}: json.loads outside the decode site ({where or 'module level'})")
        # producer_faults owns the one clock read (_now_utc) and may call it; no other module may.
        if label == _FAULTS and callee == "_now_utc":
            continue
        sanctioned = label == _FAULTS and where == "_now_utc"
        resolved = (isinstance(func, ast.Name) and func.id in clock_functions) or _call_root(func) in clock_modules
        if (callee in _WALL_CLOCK_CALLS or resolved) and not sanctioned:
            problems.append(f"{label}: wall-clock read {callee}() outside _now_utc ({where or 'module level'})")
    if _mentions_publish_log(tree) and any(isinstance(n, ast.Call) and _is_write_open(n) for n in ast.walk(tree)):
        problems.append(f"{label}: opens the publish-log path for write")
    return problems


# --- rules (c) and (e): the emission area and the existing files of the table ---------------

_PACKAGE_DIRS = ("specify_cli/status", "specify_cli/events", "specify_cli/retrospective", "specify_cli/decisions")

# Plan 4.2, per file: the names and imports this mission is allowed to ADD to the pinned base. A row
# lets a later WP's edit land after the gate (WP07 owns the stamping, lane and marker rows). The
# ``fire_*``/``register_*`` import rows are the decision-16 rename of the seam's SaaS/sync names
# (a renamed import is a new import); they add no capability.
_ROWS: dict[str, dict[str, set[str]]] = {
    "specify_cli/status/adapters.py": {
        # The decision-16 rename of the SaaS/sync names is a rename, so the new spellings are row names.
        "names": {
            "_dispatch_handlers",
            "ensure_producer_client",
            "fire_status_fanout",
            "fire_lifecycle_fanout",
            "register_status_fanout_handler",
            "register_lifecycle_fanout_handler",
            "_fanout_timeout_s",
        },
        "imports": {
            "collections.abc:Mapping",
            "specify_cli.events:sanitize_event_for_log",
            "specify_cli.status.producer_client:ProducerClient",
            "specify_cli.status.producer_client:produce_lifecycle_handler",
            "specify_cli.status.producer_client:produce_status_handler",
            "specify_cli.status.producer_faults:FaultClass",
            "specify_cli.status.producer_faults:record_fault",
        },
    },
    "specify_cli/status/zeitgeist_bridge.py": {"names": {"_drain_off"}, "imports": {"specify_cli.core:hosted_posture"}},
    "specify_cli/coordination/outbound.py": {"names": set(), "imports": {"specify_cli.status:fire_status_fanout"}},
    "specify_cli/decisions/emit.py": {"names": set(), "imports": {"specify_cli.status:fire_lifecycle_fanout"}},
    "specify_cli/status/emit.py": {"names": set(), "imports": {"specify_cli.status.adapters:fire_status_fanout"}},
    "specify_cli/status/lifecycle_events.py": {"names": set(), "imports": {"specify_cli.status.adapters:fire_lifecycle_fanout"}},
    "specify_cli/retrospective/lifecycle_events.py": {"names": set(), "imports": {"specify_cli.status:fire_lifecycle_fanout"}},
    "specify_cli/events/runtime_moments.py": {
        "names": {"RuntimeMomentProducer.emit_decision_authority_denied"},
        "imports": {"dataclasses:field", "specify_cli.status:fire_lifecycle_fanout"},
    },
    "runtime/next/_internal_runtime/events.py": {
        "names": {"RuntimeEventEmitter.emit_decision_authority_denied", "NullEmitter.emit_decision_authority_denied"},
        "imports": set(),
    },
    "specify_cli/events/decision_log.py": {"names": {"DecisionGitLog.emit_decision_authority_denied"}, "imports": set()},
    "runtime/next/runtime_bridge_retrospective.py": {"names": {"_BufferingRuntimeEmitter.emit_decision_authority_denied"}, "imports": set()},
    "runtime/next/_internal_runtime/engine.py": {"names": set(), "imports": {"ulid:", "ulid:ULID"}},
    "specify_cli/status/__init__.py": {
        "names": set(),
        "imports": {
            "specify_cli.status.adapters:ensure_producer_client",
            "specify_cli.status.adapters:fire_lifecycle_fanout",
            "specify_cli.status.adapters:fire_status_fanout",
            "specify_cli.status.adapters:register_lifecycle_fanout_handler",
            "specify_cli.status.adapters:register_status_fanout_handler",
            "specify_cli.status.producer_client:ProducerClient",
            "specify_cli.status.producer_client:effective_client_timeout",
            "specify_cli.status.producer_client:locate_binary",
            "specify_cli.status.producer_client:produce_lifecycle_handler",
            "specify_cli.status.producer_client:produce_status_handler",
            "specify_cli.status.producer_faults:FaultClass",
            "specify_cli.status.producer_faults:fault_summary",
            "specify_cli.status.producer_faults:record_fault",
        },
    },
    "specify_cli/migration/rebuild_state.py": {"names": set(), "imports": {"specify_cli.status_lanes:CANONICAL_LANES"}},
    "specify_cli/acceptance/__init__.py": {"names": set(), "imports": {"specify_cli.status:Lane"}},
    "specify_cli/consolidation/done_bookkeeping.py": {"names": set(), "imports": {"specify_cli.status:Lane"}},
}


def _package_of(path: str) -> str:
    return ".".join(path.removesuffix(".py").split("/")[:-1])


def _surface(source: str, path: str) -> tuple[set[str], set[str]]:
    tree = ast.parse(source)
    names: set[str] = set()
    for node in tree.body:
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            names.add(node.name)
        elif isinstance(node, ast.ClassDef):
            names.add(node.name)
            names.update(f"{node.name}.{sub.name}" for sub in node.body if isinstance(sub, (ast.FunctionDef, ast.AsyncFunctionDef)))
    package = _package_of(path).split(".")
    imports: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            imports.update(f"{alias.name}:" for alias in node.names)
        elif isinstance(node, ast.ImportFrom):
            module = node.module or ""
            if node.level:
                module = ".".join([*package[: len(package) - (node.level - 1)], *([module] if module else [])])
            imports.update(f"{module}:{alias.name}" for alias in node.names)
    return names, imports


def existing_file_violations(src_root: Path) -> list[str]:
    """Rule (e): each table file stays within its pinned base plus its row."""
    problems: list[str] = []
    for path, pinned in _BASE["files"].items():
        file = src_root / path
        if not file.is_file():
            problems.append(f"{path}: a table file is missing")
            continue
        names, imports = _surface(file.read_text(encoding="utf-8"), path)
        row = _ROWS.get(path, {"names": set(), "imports": set()})
        extra_names = names - set(pinned["names"]) - row["names"]
        extra_imports = imports - set(pinned["imports"]) - row["imports"]
        if extra_names:
            problems.append(f"{path}: new names outside the allowed surface: {sorted(extra_names)}")
        if extra_imports:
            problems.append(f"{path}: new imports outside the allowed surface: {sorted(extra_imports)}")
    return problems


def census_violations(src_root: Path) -> list[str]:
    """Rule (c): the module census of the emission area equals the pinned list, walking the filesystem."""
    expected = set(_BASE["census_base"]) | {_CLIENT, _FAULTS}
    found: set[str] = set()
    for directory in _PACKAGE_DIRS:
        found.update(path.relative_to(src_root).as_posix() for path in (src_root / directory).rglob("*.py"))
    problems = [f"census: unpinned module {name}" for name in sorted(found - expected)]
    problems += [f"census: pinned module {name} is gone" for name in sorted(expected - found)]
    return problems


# --- the real tree --------------------------------------------------------------------------


@pytest.mark.parametrize("label", _NEW_MODULES)
def test_a_the_new_modules_are_thin(label: str) -> None:
    source = (_SRC / label).read_text(encoding="utf-8")
    assert new_module_violations(label, source) == []


def test_a_floor_all_three_modules_are_found_and_have_call_sites() -> None:
    for label in _NEW_MODULES:
        tree = ast.parse((_SRC / label).read_text(encoding="utf-8"))
        assert _top_level_symbols(tree), label
    client = ast.parse((_SRC / _CLIENT).read_text(encoding="utf-8"))
    assert sum(isinstance(node, ast.Call) for node in ast.walk(client)) > 0


def test_c_the_emission_area_census_equals_the_pinned_list() -> None:
    assert census_violations(_SRC) == []


def test_c_floor_the_pinned_census_is_not_empty_and_the_walk_finds_the_new_modules() -> None:
    assert len(_BASE["census_base"]) > 10
    found = {p.relative_to(_SRC).as_posix() for p in (_SRC / "specify_cli/status").glob("*.py")}
    assert {_CLIENT, _FAULTS} <= found


def test_e_every_existing_file_of_the_table_stays_within_its_row() -> None:
    assert existing_file_violations(_SRC) == []


def test_e_floor_every_table_file_is_found_and_pinned() -> None:
    assert len(_BASE["files"]) >= 20
    assert all((_SRC / path).is_file() for path in _BASE["files"])
    assert set(_ROWS) <= set(_BASE["files"])


def test_d_the_client_names_no_bridge_envelope_code() -> None:
    source = (_SRC / _CLIENT).read_text(encoding="utf-8")
    assert not any(name in source for name in _BRIDGE_ENVELOPE_NAMES)


def test_build_glue_pin_names_each_packaging_file() -> None:
    """The single enumerator of the packaging glue is test_build_glue_thin.py; this gate only pins that it names each file."""
    enumerator = Path(__file__).with_name("test_build_glue_thin.py")
    assert enumerator.is_file()
    text = enumerator.read_text(encoding="utf-8")
    for needle in ("hatch_build_producer", "check_dist_set", "check_exact_install", "gradle_pin_check"):
        assert needle in text, needle


def test_the_allowed_capabilities_fixture_passes() -> None:
    """Stderr line, bound resolution, child termination, breaker, fault-log append and read, opaque identifier copy, root resolution, retention pass-through."""
    client = (_SRC / _CLIENT).read_text(encoding="utf-8")
    faults = (_SRC / _FAULTS).read_text(encoding="utf-8")
    doctor = (_SRC / _DOCTOR).read_text(encoding="utf-8")
    for needle, text in (
        ("sys.stderr", faults),
        ("effective_client_timeout", client),
        ("child.kill()", client),
        ("_tripped", client),
        ("_read_fault_rows", faults),
        ("copied", faults),
        ("resolve_canonical_root", faults),
        ("policy=", doctor),
    ):
        assert needle in text, needle
    assert new_module_violations(_CLIENT, client) == []


# --- self-mutations: each forbidden capability makes the gate fail ----------------------------

_CLIENT_SOURCE = (_SRC / _CLIENT).read_text(encoding="utf-8")
_FAULTS_SOURCE = (_SRC / _FAULTS).read_text(encoding="utf-8")
_DOCTOR_SOURCE = (_SRC / _DOCTOR).read_text(encoding="utf-8")


def _client_with(extra: str, *, head: str = "") -> str:
    return head + _CLIENT_SOURCE + "\n" + extra + "\n"


def _faults_aliased_today() -> str:
    """The cycle-3 P1 mutation: a function reads today through an aliased kernel.clock import."""
    return _faults_with("def _sneak() -> object:\n    return _utc_now().date()", head="from kernel.clock import now_utc as _utc_now\n")


def _faults_with(function: str, *, head: str = "") -> str:
    """The real faults module with one extra function before ``write_stderr_warning`` (and a head after the future import)."""
    source = _FAULTS_SOURCE.replace("def write_stderr_warning", function + "\n\n\ndef write_stderr_warning", 1)
    return source.replace("from __future__ import annotations\n", "from __future__ import annotations\n\n" + head, 1) if head else source


_VALUE = "clock referenced as a value"


@pytest.mark.parametrize(
    ("label", "mutated", "needle"),
    [
        (_CLIENT, _client_with("", head="import uuid\n"), "forbidden import uuid"),
        (_CLIENT, _client_with("def _mint() -> str:\n    return str(uuid4())", head="from uuid import uuid4\n"), "forbidden import uuid"),
        (_CLIENT, _client_with("def _mint() -> str:\n    return 'x'"), "top-level symbols differ"),
        (_CLIENT, _client_with("", head="import jsonschema\n"), "forbidden import jsonschema"),
        (_CLIENT, _client_with("", head="import spec_kitty_events\n"), "forbidden import spec_kitty_events"),
        (_CLIENT, _client_with("", head="import hashlib\n"), "forbidden import hashlib"),
        (_CLIENT, _client_with("", head="import secrets\n"), "forbidden import secrets"),
        (_CLIENT, _client_with("", head="import datetime\n"), "forbidden import datetime"),
        (_CLIENT, _client_with("", head="import tomllib\n"), "outside the closed allowlist"),
        (_CLIENT, _client_with("", head="from specify_cli.status.zeitgeist_bridge import _broadcast_status_transition\n"), "bridge envelope code"),
        (_CLIENT, _client_with("ProducerClient.x = _broadcast_lifecycle_envelope"), "names the bridge envelope code"),
        (
            _CLIENT,
            _client_with("class _Reader:\n    def read(self):\n        return open('.kittify/events/publish/missions/m.jsonl', 'a')"),
            "opens the publish-log path for write",
        ),
        (
            _CLIENT,
            _client_with("class _Writer:\n    def write(self, root):\n        (root / 'events' / 'publish').write_text('x')\n        return 'events', 'publish'"),
            "opens the publish-log path for write",
        ),
        (_CLIENT, _client_with("class _Stamp:\n    def stamp(self):\n        return now_utc()"), "wall-clock read"),
        (_CLIENT, _client_with("class _Stamp:\n    def stamp(self):\n        return datetime.now()"), "wall-clock read"),
        (
            _CLIENT,
            _CLIENT_SOURCE.replace("    record_notice,\n)", "    record_notice,\n    _now_utc,\n)", 1)
            + "\n\ndef _stamp() -> str:\n    return _now_utc().isoformat()\n",
            "producer_faults:_now_utc",
        ),
        (_CLIENT, _client_with("class _Stamp:\n    def stamp(self):\n        return _now_utc()"), "wall-clock read"),
        (_CLIENT, _client_with("class _Stamp:\n    def stamp(self):\n        return record_fault.__globals__['_now_utc']()"), "dynamic lookup"),
        (_CLIENT, _client_with("class _Stamp:\n    def stamp(self):\n        return __import__('time').time_ns()"), "dynamic lookup"),
        (_CLIENT, _client_with("class _Stamp:\n    def stamp(self):\n        return sys.modules['time'].time_ns()"), "dynamic lookup"),
        (_CLIENT, _client_with("class _Stamp:\n    def stamp(self):\n        return getattr(time, 'time_ns')()"), "dynamic lookup"),
        (_CLIENT, _client_with("class _Stamp:\n    def stamp(self):\n        return getattr(sys.modules['time'], 'time')()"), "dynamic lookup"),
        (_CLIENT, _client_with("class _Stamp:\n    def stamp(self):\n        return importlib.import_module('time')"), "dynamic lookup"),
        (_CLIENT, _client_with("class _Stamp:\n    def stamp(self):\n        return clock.time_ns()"), "wall-clock read"),
        (_CLIENT, _client_with("class _Stamp:\n    def stamp(self):\n        return clock.gmtime()"), "wall-clock read"),
        (_CLIENT, _client_with("class _Stamp:\n    def stamp(self):\n        return clock.localtime()"), "wall-clock read"),
        (_CLIENT, _client_with("class _Stamp:\n    def stamp(self):\n        return clock.ctime()"), "wall-clock read"),
        (_CLIENT, _client_with("class _Stamp:\n    def stamp(self):\n        return clock.fromtimestamp(0)"), "wall-clock read"),
        (_CLIENT, _client_with("class _Stamp:\n    def stamp(self):\n        return clock.monotonic_ns()"), "wall-clock read"),
        (_CLIENT, _client_with("class _Stamp:\n    def stamp(self):\n        return clock.perf_counter()"), "wall-clock read"),
        (
            _FAULTS,
            _FAULTS_SOURCE.replace("def write_stderr_warning", "def _sneak() -> object:\n    return __import__('time').time()\n\n\ndef write_stderr_warning", 1),
            "dynamic lookup",
        ),
        (
            _DOCTOR,
            _DOCTOR_SOURCE + "\n\ndef _sneak() -> object:\n    return sys.modules['time'].time_ns()\n",
            "dynamic lookup",
        ),
        (_CLIENT, _client_with("class _Decode:\n    def decode(self, text):\n        return json.loads(text)"), "json.loads outside the decode site"),
        (
            _FAULTS,
            _FAULTS_SOURCE.replace("def write_stderr_warning", "def _stamp() -> str:\n    return datetime.now().isoformat()\n\n\ndef write_stderr_warning", 1),
            "wall-clock read",
        ),
        (
            _FAULTS,
            _FAULTS_SOURCE.replace("def record_notice", "def _clock() -> object:\n    return now_utc()\n\n\ndef record_notice", 1),
            "wall-clock read",
        ),
        (
            _FAULTS,
            _FAULTS_SOURCE.replace("def write_stderr_warning", "def _sneak(text):\n    return json.loads(text)\n\n\ndef write_stderr_warning", 1),
            "json.loads outside the decode site",
        ),
        # A clock used as a VALUE (cycle-4 Q1) is as much a read as a call: any reference outside _now_utc fails.
        (_FAULTS, _faults_with("def _sneak(*, clock: Any = now_utc) -> object:\n    return clock().date()"), _VALUE),
        (_FAULTS, _faults_with("def _sneak() -> object:\n    clock = now_utc\n    return clock().date()"), _VALUE),
        (_FAULTS, _faults_with("def _sneak() -> object:\n    return field(default_factory=now_utc)", head="from dataclasses import field\n"), _VALUE),
        (_FAULTS, _faults_with("def _sneak() -> object:\n    return sorted([], key=time.monotonic)", head="import time\n"), _VALUE),
        (_FAULTS, _faults_with("def _sneak() -> object:\n    return now_utc"), _VALUE),
        (_FAULTS, _faults_with("def _sneak() -> object:\n    return lambda: now_utc()"), "wall-clock read"),
        (_FAULTS, _faults_with("def _sneak() -> object:\n    return {'t': now_utc}"), _VALUE),
        (_FAULTS, _faults_with("def _sneak(*, clock: Any = _now_utc) -> object:\n    return clock()"), _VALUE),
        (_FAULTS, _faults_with("def _sneak() -> object:\n    return datetime.now"), _VALUE),
        (_CLIENT, _client_with("def _sneak(*, clock: object = sys.modules) -> object:\n    return clock"), "dynamic lookup"),
        (_CLIENT, _client_with("", head="from sys import modules\n"), "dynamic lookup via modules"),
        # An aliased clock import is still a clock read (cycle-3 P1): the gate resolves the names an import binds.
        (_FAULTS, _faults_aliased_today(), "wall-clock read"),
        (
            _FAULTS,
            _FAULTS_SOURCE.replace("def write_stderr_warning", "def _sneak() -> object:\n    return kc.current_instant()\n\n\ndef write_stderr_warning", 1).replace(
                "from __future__ import annotations\n", "from __future__ import annotations\n\nimport kernel.clock as kc\n", 1
            ),
            "wall-clock read",
        ),
        (
            _CLIENT,
            _client_with("class _Stamp:\n    def stamp(self):\n        return _utc_now()", head="from kernel.clock import now_utc as _utc_now\n"),
            "wall-clock read",
        ),
        (
            _CLIENT,
            _client_with("class _Stamp:\n    def stamp(self):\n        return kc.current_instant()", head="import kernel.clock as kc\n"),
            "wall-clock read",
        ),
        (
            _DOCTOR,
            "from kernel.clock import now_utc as _utc_now\n" + _DOCTOR_SOURCE + "\n\nclass _Stamp:\n    def stamp(self):\n        return _utc_now()\n",
            "wall-clock read",
        ),
        (
            _DOCTOR,
            "import kernel.clock as kc\n" + _DOCTOR_SOURCE + "\n\nclass _Stamp:\n    def stamp(self):\n        return kc.current_instant()\n",
            "wall-clock read",
        ),
    ],
)
def test_a_self_mutation_of_a_new_module_fails_the_gate(label: str, mutated: str, needle: str) -> None:
    problems = new_module_violations(label, mutated)
    assert any(needle in problem for problem in problems), problems


def test_a_local_variable_named_modules_is_not_a_dynamic_lookup() -> None:
    """Only ``sys.modules`` reaches a clock; a local ``modules`` name is ordinary (cycle-3 P4)."""
    mutated = _client_with("def _count(items):\n    modules = tuple(items)\n    return len(modules)")
    problems = new_module_violations(_CLIENT, mutated)
    assert not any("dynamic lookup" in problem for problem in problems), problems


@pytest.fixture
def temp_tree(tmp_path: Path) -> Path:
    """A temporary copy of the files the rules read, so a mutation never touches the real tree."""
    root = tmp_path / "src"
    for path in _BASE["files"]:
        target = root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(_SRC / path, target)
    for directory in _PACKAGE_DIRS:
        for file in (_SRC / directory).rglob("*.py"):
            target = root / file.relative_to(_SRC)
            target.parent.mkdir(parents=True, exist_ok=True)
            if not target.exists():
                shutil.copy2(file, target)
    return root


def test_the_copy_of_the_tree_passes_both_rules(temp_tree: Path) -> None:
    assert census_violations(temp_tree) == [] and existing_file_violations(temp_tree) == []


def test_c_an_untracked_new_module_in_the_emission_area_fails_the_census(temp_tree: Path) -> None:
    (temp_tree / "specify_cli/status/_mint_id.py").write_text("def mint() -> str:\n    return 'x'\n", encoding="utf-8")
    assert any("_mint_id.py" in problem for problem in census_violations(temp_tree))


@pytest.mark.parametrize("directory", ["events", "retrospective", "decisions"])
def test_c_a_new_module_in_any_of_the_four_directories_fails_the_census(temp_tree: Path, directory: str) -> None:
    (temp_tree / f"specify_cli/{directory}/_extra.py").write_text("X = 1\n", encoding="utf-8")
    assert any("_extra.py" in problem for problem in census_violations(temp_tree))


def _append(temp_tree: Path, path: str, text: str, *, head: str = "") -> None:
    file = temp_tree / path
    file.write_text(head + file.read_text(encoding="utf-8") + "\n" + text + "\n", encoding="utf-8")


def test_e_a_new_function_in_an_existing_table_file_fails(temp_tree: Path) -> None:
    _append(temp_tree, "specify_cli/status/emit.py", "def _mint_id() -> str:\n    return 'x'")
    assert any("_mint_id" in problem for problem in existing_file_violations(temp_tree))


def test_e_a_new_method_in_an_existing_class_fails(temp_tree: Path) -> None:
    _append(temp_tree, "specify_cli/events/runtime_moments.py", "class RuntimeMomentProducer:\n    def mint(self) -> str:\n        return 'x'")
    assert any("RuntimeMomentProducer.mint" in problem for problem in existing_file_violations(temp_tree))


@pytest.mark.parametrize(
    ("path", "head"),
    [
        ("specify_cli/status/emit.py", "import uuid\n"),
        ("specify_cli/status/lifecycle_events.py", "import hashlib\n"),
        ("specify_cli/migration/rebuild_state.py", "import secrets\n"),
        ("runtime/next/_internal_runtime/engine.py", "import secrets\n"),
        ("specify_cli/decisions/emit.py", "import random\n"),
        ("specify_cli/status/emit.py", "from datetime import datetime\n"),
    ],
)
def test_e_an_import_outside_the_row_fails(temp_tree: Path, path: str, head: str) -> None:
    _append(temp_tree, path, "", head=head)
    assert any("new imports" in problem for problem in existing_file_violations(temp_tree))


def test_e_the_engine_may_add_ulid_and_nothing_else(temp_tree: Path) -> None:
    _append(temp_tree, "runtime/next/_internal_runtime/engine.py", "", head="import ulid\n")
    assert existing_file_violations(temp_tree) == []


def test_e_a_missing_table_file_is_reported(temp_tree: Path) -> None:
    (temp_tree / "specify_cli/status/emit.py").unlink()
    assert any("emit.py: a table file is missing" in problem for problem in existing_file_violations(temp_tree))


def test_the_mutations_are_applied_through_the_same_checker_the_real_tree_uses() -> None:
    checker: Callable[[Path], list[str]] = existing_file_violations
    assert checker(_SRC) == []
