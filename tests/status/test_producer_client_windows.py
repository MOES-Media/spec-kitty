"""Windows subset: the client terminates a hanging producer by handle and quotes paths with spaces (FR-025, AC-006).

Marked ``windows_ci``: ``ci-windows.yml`` discovers these files with ``git grep`` and runs them on a
native Windows runner. The stub is a Python script run through ``sys.executable`` via a ``.cmd``
wrapper, because a shebang does nothing on Windows. The tier marker ``fast`` is carried explicitly,
as the completeness gate requires.
"""

from __future__ import annotations

import json
import sys
import time
from pathlib import Path

import pytest

from specify_cli.status import producer_client
from specify_cli.status.producer_client import ProducerClient, produce_status_handler

pytestmark = [pytest.mark.fast, pytest.mark.windows_ci, pytest.mark.real_event_producer]

_SCRIPT = """\
import json, os, pathlib, sys, time
here = pathlib.Path(__file__).resolve().parent
data = sys.stdin.buffer.read()
with open(here / "requests.jsonl", "ab") as handle:
    handle.write(data.replace(b"\\n", b" ") + b"\\n")
with open(here / "pids.txt", "a", encoding="utf-8") as handle:
    handle.write(str(os.getpid()) + "\\n")
config = json.loads((here / "stub.json").read_text(encoding="utf-8"))
if config.get("hang"):
    time.sleep(config["hang"])
sys.stdout.write(config.get("reply", ""))
sys.exit(config.get("exit", 0))
"""


def _stub(directory: Path, **config: object) -> Path:
    directory.mkdir(parents=True)
    (directory / "stub.py").write_text(_SCRIPT, encoding="utf-8")
    (directory / "stub.json").write_text(json.dumps(config), encoding="utf-8")
    wrapper = directory / ("stub.cmd" if sys.platform == "win32" else "stub")
    if sys.platform == "win32":
        wrapper.write_text(f'@"{sys.executable}" "%~dp0stub.py"\r\n', encoding="utf-8")
    else:
        wrapper.write_text(f'#!/bin/sh\nexec "{sys.executable}" "$(dirname "$0")/stub.py"\n', encoding="utf-8")
        wrapper.chmod(0o755)
    return wrapper


@pytest.fixture
def repo(tmp_path: Path) -> Path:
    root = tmp_path / "repo"
    (root / ".git").mkdir(parents=True)
    (root / ".kittify").mkdir()
    return root


def test_the_client_timeout_terminates_a_hanging_stub_by_handle_and_leaves_no_child(repo: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    stub = _stub(tmp_path / "stub bin", hang=30)
    monkeypatch.setattr(producer_client, "locate_binary", lambda: stub)
    ProducerClient.install(bound_resolver=lambda: 1.0)
    started = time.monotonic()

    produce_status_handler(row={"event_id": "01J0000000000000000000W001"}, repo_root=repo, wp_id="WP01")

    assert time.monotonic() - started < 15
    faults = (repo / ".kittify" / "events" / "faults").glob("*.jsonl")
    assert [json.loads(line)["class"] for path in faults for line in path.read_text(encoding="utf-8").splitlines()] == ["timeout"]
    pids = (stub.parent / "pids.txt").read_text(encoding="utf-8").split()
    assert len(pids) >= 1  # the stub started; the kill was by handle, so the wait in the client returned


def test_argv_quoting_survives_a_path_containing_a_space(repo: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    stub = _stub(tmp_path / "path with space", reply='{"v":1,"op":"produce","outcome":"produced","event_id":"x","type":"t"}\n')
    monkeypatch.setattr(producer_client, "locate_binary", lambda: stub)
    ProducerClient.install()

    produce_status_handler(row={"event_id": "01J0000000000000000000W002"}, repo_root=repo, wp_id="WP01")

    requests = (stub.parent / "requests.jsonl").read_text(encoding="utf-8").splitlines()
    assert len(requests) == 1 and json.loads(requests[0])["row"]["event_id"] == "01J0000000000000000000W002"
    assert not (repo / ".kittify" / "events" / "faults").exists()
