"""No Python code opens the publish log for write (FR-020, C-004, AC-030).

The producer (Java) is the single writer of the gitignored publish log. The path is read from the
publish-log ``StateSurface`` entry of ``state/contract.py``, not retyped here. Every Python module
under ``src/`` is scanned; a module that names the publish-log path and opens, writes or touches a
file is a violation. The scan walks the filesystem, so an untracked file is seen.
"""

from __future__ import annotations

import ast
from pathlib import Path

import pytest

from specify_cli.state.contract import STATE_SURFACES

pytestmark = [pytest.mark.architectural]

_SRC = Path(__file__).resolve().parents[2] / "src"
_DECLARATION = "specify_cli/state/contract.py"
_WRITE_CALLS = {"write_text", "write_bytes", "touch", "mkdir", "append_raw_rows_atomic", "replace", "rename", "unlink", "truncate"}


def _publish_marker() -> str:
    (surface,) = [s for s in STATE_SURFACES if s.name == "event_producer_publish_log"]
    return surface.path_pattern.split("/missions/")[0]  # ".kittify/events/publish"


def _segments(marker: str) -> tuple[str, str]:
    parts = [part for part in marker.split("/") if part and part != ".kittify"]
    return parts[0], parts[1]


def _write_call(call: ast.Call) -> bool:
    func = call.func
    name = func.attr if isinstance(func, ast.Attribute) else getattr(func, "id", "")
    if name in _WRITE_CALLS:
        return True
    if name != "open":
        return False
    # ``open(path, mode)`` takes the mode second; ``Path.open(mode)`` takes it first.
    positional = call.args[1:2] if isinstance(func, ast.Name) else call.args[:1]
    modes = [arg.value for arg in positional if isinstance(arg, ast.Constant) and isinstance(arg.value, str)]
    modes += [kw.value.value for kw in call.keywords if kw.arg == "mode" and isinstance(kw.value, ast.Constant) and isinstance(kw.value.value, str)]
    return any(set(mode) & set("wax+") for mode in modes)


def violations(source: str, label: str, marker: str) -> list[str]:
    tree = ast.parse(source)
    literals = [node.value for node in ast.walk(tree) if isinstance(node, ast.Constant) and isinstance(node.value, str)]
    first, second = _segments(marker)
    names_the_path = any(marker in text for text in literals) or (first in literals and second in literals)
    if not names_the_path:
        return []
    return [f"{label}: names the publish-log path and writes" for node in ast.walk(tree) if isinstance(node, ast.Call) and _write_call(node)][:1]


def scan(src_root: Path, marker: str) -> tuple[int, list[str]]:
    scanned, found = 0, []
    for file in sorted(src_root.rglob("*.py")):
        label = file.relative_to(src_root).as_posix()
        if label == _DECLARATION:
            continue
        scanned += 1
        found += violations(file.read_text(encoding="utf-8"), label, marker)
    return scanned, found


def test_no_python_module_writes_the_publish_log() -> None:
    scanned, found = scan(_SRC, _publish_marker())
    assert found == []
    assert scanned > 500, "floor: the scan found the source tree"


def test_floor_the_path_comes_from_the_state_surface_entry() -> None:
    assert _publish_marker() == ".kittify/events/publish"


@pytest.mark.parametrize(
    "source",
    [
        "from pathlib import Path\ndef f(root):\n    open('.kittify/events/publish/missions/m.jsonl', 'a').write('x')\n",
        "def f(root):\n    (root / '.kittify' / 'events' / 'publish' / 'm.jsonl').write_text('x')\n",
        "def f(root):\n    with open(str(root) + '/.kittify/events/publish/p.jsonl', mode='w') as h:\n        h.write('x')\n",
    ],
)
def test_mutation_a_module_that_writes_the_publish_log_fails(source: str) -> None:
    assert violations(source, "mutant.py", _publish_marker()) != []


def test_mutation_an_untracked_module_that_writes_the_publish_log_is_seen(tmp_path: Path) -> None:
    root = tmp_path / "src" / "specify_cli" / "status"
    root.mkdir(parents=True)
    (root / "_untracked.py").write_text("def f():\n    open('.kittify/events/publish/x.jsonl', 'w')\n", encoding="utf-8")
    scanned, found = scan(tmp_path / "src", _publish_marker())
    assert scanned == 1 and found != []


def test_a_read_only_module_naming_the_path_passes() -> None:
    assert violations("def f():\n    return open('.kittify/events/publish/x.jsonl').read()\n", "reader.py", _publish_marker()) == []
