"""The producer build hook and the stub platform wheel (mission event-producer-reroute, WP01 T006; FR-040, FR-041, C-007).

Hook off (the default): the wheel and sdist are today's, and binary-free even with a stub planted, untracked, in
``src/specify_cli/_native/``. Hook on (``HATCH_BUILD_HOOK_ENABLE_CUSTOM=true`` with ``SPEC_KITTY_PRODUCER_BINARY`` and
``SPEC_KITTY_PRODUCER_PLATFORM_TAG``): a platform wheel with the binary at ``specify_cli/_native/<name>``, ``PRODUCER.sha256`` and, on
non-Windows tags, the exec bit ``0755``. The hook's own refusals are exercised in process; the packaging claims run real ``python -m build``
builds from a copy of the build inputs so the working tree is never touched.
"""

from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import shutil
import stat
import subprocess
import sys
import tarfile
import zipfile
from pathlib import Path
from types import ModuleType
from typing import Any

import pytest

pytestmark = [pytest.mark.integration]

ROOT = Path(__file__).resolve().parents[3]
HOOK = ROOT / "scripts" / "release" / "hatch_build_producer.py"
DIST_SET = ROOT / "scripts" / "release" / "dist-set.json"
NATIVE_DIR = "specify_cli/_native"
BINARY_NAME = "spec-kitty-event-producer"
BINARY_BYTES = b"\x7fELF-stub-producer-binary\n" * 64

COPIED_FILES = ("pyproject.toml", "README.md", "LICENSE", "CHANGELOG.md", ".gitignore")
COPIED_TREES = ("src", "packs/built-in")


def _tag_for(leg: str) -> str:
    data = json.loads(DIST_SET.read_text(encoding="utf-8"))
    return next(w["tag"] for w in data["platform_wheels"] if w["leg"] == leg)


def _copy_inputs(destination: Path) -> Path:
    """Copy the files a wheel/sdist build reads. Not a git checkout: hatch reads .gitignore directly."""
    for name in COPIED_FILES:
        shutil.copy2(ROOT / name, destination / name)
    for tree in COPIED_TREES:
        target = destination / tree
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(ROOT / tree, target, ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
    hook_dir = destination / "scripts" / "release"
    hook_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(HOOK, hook_dir / HOOK.name)
    shutil.copy2(DIST_SET, hook_dir / DIST_SET.name)
    return destination


def _build(project: Path, outdir: Path, *targets: str, env_extra: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    env = {k: v for k, v in os.environ.items() if not k.startswith(("HATCH_BUILD_HOOK_ENABLE", "SPEC_KITTY_PRODUCER_"))}
    env.update(env_extra or {})
    return subprocess.run(
        [sys.executable, "-m", "build", "--no-isolation", *targets, "--outdir", str(outdir)],
        cwd=project,
        env=env,
        capture_output=True,
        text=True,
        check=False,
    )


def _wheel_members(wheel: Path) -> list[str]:
    with zipfile.ZipFile(wheel) as archive:
        return sorted(archive.namelist())


def _sdist_members(sdist: Path) -> list[str]:
    with tarfile.open(sdist) as archive:
        return sorted(name.split("/", 1)[1] for name in archive.getnames() if "/" in name)


def _only(directory: Path, pattern: str) -> Path:
    (match,) = sorted(directory.glob(pattern))
    return match


@pytest.fixture(scope="module")
def hook_off_with_stub(tmp_path_factory: pytest.TempPathFactory) -> dict[str, Any]:
    """A hook-off build from a copy that has a stub binary planted, untracked, in src/specify_cli/_native/."""
    work = tmp_path_factory.mktemp("stub-off")
    project = _copy_inputs(work / "project")
    native = project / "src" / "specify_cli" / "_native"
    native.mkdir()
    (native / BINARY_NAME).write_bytes(BINARY_BYTES)
    (native / "PRODUCER.sha256").write_text("0" * 64 + "\n", encoding="utf-8")
    out = work / "dist"
    result = _build(project, out, "--wheel", "--sdist")
    assert result.returncode == 0, result.stderr[-2000:]
    return {"project": project, "wheel": _only(out, "*.whl"), "sdist": _only(out, "*.tar.gz"), "planted": native / BINARY_NAME}


@pytest.fixture(scope="module")
def hook_on_linux(tmp_path_factory: pytest.TempPathFactory) -> dict[str, Any]:
    work = tmp_path_factory.mktemp("stub-on")
    project = _copy_inputs(work / "project")
    binary = work / "inputs" / BINARY_NAME
    binary.parent.mkdir()
    binary.write_bytes(BINARY_BYTES)
    binary.chmod(0o644)  # artifact transfer drops mode bits; the hook must restore the exec bit itself
    out = work / "dist"
    tag = _tag_for("linux-x86_64")
    result = _build(
        project,
        out,
        "--wheel",
        env_extra={
            "HATCH_BUILD_HOOK_ENABLE_CUSTOM": "true",
            "SPEC_KITTY_PRODUCER_BINARY": str(binary),
            "SPEC_KITTY_PRODUCER_PLATFORM_TAG": tag,
        },
    )
    assert result.returncode == 0, result.stderr[-2000:]
    return {"wheel": _only(out, "*.whl"), "tag": tag}


# --- the pyproject table and the ignore entry ------------------------------------------------------------------


def test_pyproject_carries_only_the_off_by_default_hook_table() -> None:
    import tomllib

    pyproject = tomllib.loads((ROOT / "pyproject.toml").read_text(encoding="utf-8"))
    hooks = pyproject["tool"]["hatch"]["build"]["targets"]["wheel"]["hooks"]
    assert hooks == {"custom": {"path": "scripts/release/hatch_build_producer.py", "enable-by-default": False}}
    assert "hooks" not in pyproject["tool"]["hatch"]["build"]["targets"]["sdist"]
    assert "hooks" not in pyproject["tool"]["hatch"]["build"]


def test_the_native_directory_is_gitignored_in_the_repository() -> None:
    result = subprocess.run(["git", "check-ignore", "-q", "src/specify_cli/_native/spec-kitty-event-producer"], cwd=ROOT, capture_output=True, check=False)
    assert result.returncode == 0, "src/specify_cli/_native/ must be ignored: hatch honours .gitignore, so a copied binary must not ship in the pure wheel"


# --- hook off: today's wheel and sdist, binary-free -------------------------------------------------------------


def test_hook_off_wheel_and_sdist_are_binary_free_with_a_stub_planted(hook_off_with_stub: dict[str, Any]) -> None:
    assert hook_off_with_stub["planted"].is_file(), "the stub must be planted for this test to mean anything"
    assert [m for m in _wheel_members(hook_off_with_stub["wheel"]) if "_native" in m] == []
    assert [m for m in _sdist_members(hook_off_with_stub["sdist"]) if "_native" in m] == []
    assert hook_off_with_stub["wheel"].name.endswith("-py3-none-any.whl")


def test_hook_off_wheel_equals_the_shared_build_of_the_repository(hook_off_with_stub: dict[str, Any], build_artifacts: dict[str, Path]) -> None:
    assert _wheel_members(hook_off_with_stub["wheel"]) == _wheel_members(build_artifacts["wheel"])
    assert _sdist_members(hook_off_with_stub["sdist"]) == _sdist_members(build_artifacts["sdist"])


def test_without_the_gitignore_entry_the_stub_would_ship(hook_off_with_stub: dict[str, Any], tmp_path: Path) -> None:
    """Mutation of the ignore pin: drop the entry from the copy and the planted stub reaches the wheel, so the entry is load-bearing."""
    project = hook_off_with_stub["project"]
    ignore = project / ".gitignore"
    original = ignore.read_text(encoding="utf-8")
    assert "src/specify_cli/_native/" in original
    ignore.write_text(original.replace("src/specify_cli/_native/", ""), encoding="utf-8")
    try:
        result = _build(project, tmp_path / "dist", "--wheel")
        assert result.returncode == 0, result.stderr[-2000:]
        assert [m for m in _wheel_members(_only(tmp_path / "dist", "*.whl")) if m.startswith(NATIVE_DIR)], "the stub did not ship: the ignore pin proves nothing"
    finally:
        ignore.write_text(original, encoding="utf-8")


# --- hook on: a platform wheel carrying the binary --------------------------------------------------------------


def test_hook_on_builds_a_platform_wheel_with_the_pinned_tag(hook_on_linux: dict[str, Any]) -> None:
    assert hook_on_linux["wheel"].name.endswith(f"-{hook_on_linux['tag']}.whl")
    with zipfile.ZipFile(hook_on_linux["wheel"]) as archive:
        wheel_file = next(n for n in archive.namelist() if n.endswith(".dist-info/WHEEL"))
        text = archive.read(wheel_file).decode()
    assert "Root-Is-Purelib: false" in text
    assert f"Tag: {hook_on_linux['tag']}" in text


def test_hook_on_wheel_carries_the_binary_its_checksum_and_the_exec_bit(hook_on_linux: dict[str, Any]) -> None:
    digest = hashlib.sha256(BINARY_BYTES).hexdigest()  # noqa: TID251 -- file-integrity checksum of the stub binary, not the charter hash
    with zipfile.ZipFile(hook_on_linux["wheel"]) as archive:
        assert archive.read(f"{NATIVE_DIR}/{BINARY_NAME}") == BINARY_BYTES
        assert archive.read(f"{NATIVE_DIR}/PRODUCER.sha256").decode().split()[0] == digest
        mode = (archive.getinfo(f"{NATIVE_DIR}/{BINARY_NAME}").external_attr >> 16) & 0o777
    assert mode == 0o755, oct(mode)
    assert not stat.S_ISDIR(mode)


def test_hook_on_adds_nothing_else_to_the_wheel(hook_on_linux: dict[str, Any], build_artifacts: dict[str, Path]) -> None:
    on = {m for m in _wheel_members(hook_on_linux["wheel"]) if not m.endswith((".dist-info/WHEEL", ".dist-info/RECORD"))}
    base = {m for m in _wheel_members(build_artifacts["wheel"]) if not m.endswith((".dist-info/WHEEL", ".dist-info/RECORD"))}
    assert sorted(on - base) == [f"{NATIVE_DIR}/PRODUCER.sha256", f"{NATIVE_DIR}/{BINARY_NAME}"]
    assert base - on == set()


# --- the hook's own refusals, in process ------------------------------------------------------------------------


def _load_hook() -> ModuleType:
    spec = importlib.util.spec_from_file_location("hatch_build_producer_under_test", HOOK)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _hook(root: Path) -> Any:
    module = _load_hook()
    (cls,) = [v for v in vars(module).values() if isinstance(v, type) and v.__module__ == module.__name__ and v.__name__.endswith("Hook")]
    return cls(str(root), {}, None, None, str(root / "dist"), "wheel")


@pytest.fixture
def clean_env(monkeypatch: pytest.MonkeyPatch) -> pytest.MonkeyPatch:
    for key in ("HATCH_BUILD_HOOK_ENABLE_CUSTOM", "SPEC_KITTY_PRODUCER_BINARY", "SPEC_KITTY_PRODUCER_PLATFORM_TAG"):
        monkeypatch.delenv(key, raising=False)
    return monkeypatch


def test_the_hook_is_inert_unless_explicitly_enabled(tmp_path: Path, clean_env: pytest.MonkeyPatch) -> None:
    binary = tmp_path / BINARY_NAME
    binary.write_bytes(BINARY_BYTES)
    clean_env.setenv("SPEC_KITTY_PRODUCER_BINARY", str(binary))
    clean_env.setenv("SPEC_KITTY_PRODUCER_PLATFORM_TAG", _tag_for("linux-x86_64"))
    build_data: dict[str, Any] = {"pure_python": True, "infer_tag": False}
    _hook(tmp_path).initialize("0", build_data)
    assert build_data == {"pure_python": True, "infer_tag": False}


@pytest.mark.parametrize("missing", ["SPEC_KITTY_PRODUCER_BINARY", "SPEC_KITTY_PRODUCER_PLATFORM_TAG"])
def test_an_enabled_hook_without_its_inputs_refuses_instead_of_building_a_pure_wheel(tmp_path: Path, clean_env: pytest.MonkeyPatch, missing: str) -> None:
    binary = tmp_path / BINARY_NAME
    binary.write_bytes(BINARY_BYTES)
    clean_env.setenv("HATCH_BUILD_HOOK_ENABLE_CUSTOM", "true")
    clean_env.setenv("SPEC_KITTY_PRODUCER_BINARY", str(binary))
    clean_env.setenv("SPEC_KITTY_PRODUCER_PLATFORM_TAG", _tag_for("linux-x86_64"))
    clean_env.delenv(missing)
    with pytest.raises(RuntimeError, match=missing):
        _hook(tmp_path).initialize("0", {})


@pytest.mark.parametrize("tag", ["py3-none-any", "py3-none-", "cp311-cp311-linux_x86_64", "py3-none-win_amd64; rm", ""])
def test_an_enabled_hook_refuses_a_tag_that_is_not_a_platform_tag(tmp_path: Path, clean_env: pytest.MonkeyPatch, tag: str) -> None:
    binary = tmp_path / BINARY_NAME
    binary.write_bytes(BINARY_BYTES)
    clean_env.setenv("HATCH_BUILD_HOOK_ENABLE_CUSTOM", "true")
    clean_env.setenv("SPEC_KITTY_PRODUCER_BINARY", str(binary))
    clean_env.setenv("SPEC_KITTY_PRODUCER_PLATFORM_TAG", tag)
    with pytest.raises(RuntimeError, match="SPEC_KITTY_PRODUCER_PLATFORM_TAG"):
        _hook(tmp_path).initialize("0", {})


def test_an_enabled_hook_refuses_a_binary_that_does_not_exist(tmp_path: Path, clean_env: pytest.MonkeyPatch) -> None:
    clean_env.setenv("HATCH_BUILD_HOOK_ENABLE_CUSTOM", "true")
    clean_env.setenv("SPEC_KITTY_PRODUCER_BINARY", str(tmp_path / "absent"))
    clean_env.setenv("SPEC_KITTY_PRODUCER_PLATFORM_TAG", _tag_for("linux-x86_64"))
    with pytest.raises(RuntimeError, match="SPEC_KITTY_PRODUCER_BINARY"):
        _hook(tmp_path).initialize("0", {})


def test_the_windows_tag_stages_no_exec_bit_requirement(tmp_path: Path, clean_env: pytest.MonkeyPatch) -> None:
    binary = tmp_path / f"{BINARY_NAME}.exe"
    binary.write_bytes(BINARY_BYTES)
    clean_env.setenv("HATCH_BUILD_HOOK_ENABLE_CUSTOM", "true")
    clean_env.setenv("SPEC_KITTY_PRODUCER_BINARY", str(binary))
    clean_env.setenv("SPEC_KITTY_PRODUCER_PLATFORM_TAG", _tag_for("windows-x86_64"))
    build_data: dict[str, Any] = {}
    _hook(tmp_path).initialize("0", build_data)
    assert build_data["pure_python"] is False
    assert build_data["tag"] == _tag_for("windows-x86_64")
    assert sorted(build_data["force_include"].values()) == [f"{NATIVE_DIR}/PRODUCER.sha256", f"{NATIVE_DIR}/{BINARY_NAME}.exe"]
