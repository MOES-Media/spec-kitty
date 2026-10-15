"""What the producer wheels, the pure wheel and the sdist contain (mission event-producer-reroute, WP08 T056).

Real ``python -m build`` builds, from a copy of the build inputs so the working tree is never touched, with a STUB binary standing in for
the native image (the real binary is the Java build's; here only the packaging is under test):

* the binary lives only in the platform wheels, with its checksum member, the exec bit on Linux and macOS and the pinned tag (FR-041,
  AC-024, G-1);
* the pure wheel and the sdist are binary-free even with an untracked stub planted in ``src/specify_cli/_native/`` (G-7);
* a wheel built from the extracted sdist without a binary succeeds, and an editable build works without one (AC-025, static half; the
  runtime ``binary-missing`` half is WP10);
* no wheel or sdist contains ``packs/internal/`` and the ``packs`` include stays narrowed to ``packs/built-in/`` (AC-033 packs part, G-10);
* the real wheels satisfy ``check_dist_set.py`` in all three modes, and the platform wheels differ from the pure wheel only in tag, binary
  member and ``PRODUCER.sha256`` member (FR-042, AC-032, AC-037, G-8, G-12).
"""

from __future__ import annotations

import hashlib
import importlib.util
import json
import shutil
import stat
import subprocess
import sys
import tarfile
import tomllib
import zipfile
from pathlib import Path
from types import ModuleType
from typing import Any

import pytest

from tests._support.shared_build_artifacts import (
    PRODUCER_BINARY_NAME,
    SharedBuildError,
    copy_build_inputs,
    ensure_shared_platform_wheel,
    platform_tag_for_leg,
    run_build,
    run_scoped_shared_root,
    stub_binary_bytes,
    stub_binary_name,
)

pytestmark = [pytest.mark.integration]

ROOT = Path(__file__).resolve().parents[3]
CHECKER = ROOT / "scripts" / "release" / "check_dist_set.py"
DIST_SET = ROOT / "scripts" / "release" / "dist-set.json"
NATIVE = "specify_cli/_native"
CHECKSUM = "PRODUCER.sha256"
COMMIT = "abcdef0123456789abcdef0123456789abcdef01"
LEGS = ("linux-x86_64", "macos-arm64", "windows-x86_64")
PLANTED_BYTES = b"\x7fELF-planted-untracked-stub\n" * 32


def sha256_of(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()  # noqa: TID251 -- file-integrity checksum of a stub binary, not the charter hash


def wheel_members(wheel: Path) -> list[str]:
    with zipfile.ZipFile(wheel) as archive:
        return sorted(archive.namelist())


def sdist_members(sdist: Path) -> list[str]:
    with tarfile.open(sdist) as archive:
        return sorted(name.split("/", 1)[1] for name in archive.getnames() if "/" in name)


def only(directory: Path, pattern: str) -> Path:
    (match,) = sorted(directory.glob(pattern))
    return match


def load_checker() -> ModuleType:
    spec = importlib.util.spec_from_file_location("check_dist_set_for_wheels", CHECKER)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


# --- fixtures -----------------------------------------------------------------------------------------------------


@pytest.fixture(scope="session")
def platform_wheels(tmp_path_factory: pytest.TempPathFactory) -> dict[str, Path]:
    """One stub-binary platform wheel per leg, built once per run and shared by every xdist worker."""
    root = run_scoped_shared_root(tmp_path_factory)
    try:
        wheels = {leg: ensure_shared_platform_wheel(root, leg) for leg in LEGS}
    except SharedBuildError as error:
        pytest.fail(f"platform wheel build failed: {error}", pytrace=False)
    assert len(wheels) == 3, "floor: three platform wheels"
    return wheels


@pytest.fixture(scope="module")
def pure_build(tmp_path_factory: pytest.TempPathFactory) -> dict[str, Any]:
    """Hook off, an untracked stub planted in ``src/specify_cli/_native/``: the pure wheel and the sdist."""
    work = tmp_path_factory.mktemp("pure-build")
    project = copy_build_inputs(work / "project")
    native = project / "src" / "specify_cli" / "_native"
    native.mkdir()
    (native / PRODUCER_BINARY_NAME).write_bytes(PLANTED_BYTES)
    (native / CHECKSUM).write_text("0" * 64 + "\n", encoding="utf-8")
    out = work / "dist"
    result = run_build(project, out, "--wheel", "--sdist")
    assert result.returncode == 0, result.stderr[-2000:]
    assert (native / PRODUCER_BINARY_NAME).is_file(), "the stub must be planted for this build to mean anything"
    return {"project": project, "wheel": only(out, "*.whl"), "sdist": only(out, "*.tar.gz")}


@pytest.fixture(scope="module")
def release_dist(platform_wheels: dict[str, Path], pure_build: dict[str, Any], tmp_path_factory: pytest.TempPathFactory) -> Path:
    """The five files a release holds, side by side. All wheels share one version because they build from one tree."""
    dist = tmp_path_factory.mktemp("release-dist")
    for wheel in (*platform_wheels.values(), pure_build["wheel"], pure_build["sdist"]):
        shutil.copy2(wheel, dist / wheel.name)
    assert len(list(dist.iterdir())) == 5, "floor: three platform wheels, the pure wheel and the sdist"
    return dist


# --- the binary lives only in the platform wheels ------------------------------------------------------------------


@pytest.mark.parametrize("leg", LEGS)
def test_a_platform_wheel_carries_the_binary_its_checksum_and_the_pinned_tag(platform_wheels: dict[str, Path], leg: str) -> None:
    wheel = platform_wheels[leg]
    tag = platform_tag_for_leg(leg)
    name = stub_binary_name(leg)
    assert wheel.name.endswith(f"-{tag}.whl")
    with zipfile.ZipFile(wheel) as archive:
        assert archive.read(f"{NATIVE}/{name}") == stub_binary_bytes(leg)
        assert archive.read(f"{NATIVE}/{CHECKSUM}").decode().split() == [sha256_of(stub_binary_bytes(leg)), name]
        wheel_file = archive.read(next(n for n in archive.namelist() if n.endswith(".dist-info/WHEEL"))).decode()
    assert "Root-Is-Purelib: false" in wheel_file
    assert f"Tag: {tag}" in wheel_file


@pytest.mark.parametrize("leg", ["linux-x86_64", "macos-arm64"])
def test_the_unix_binary_is_executable_in_the_wheel(platform_wheels: dict[str, Path], leg: str) -> None:
    with zipfile.ZipFile(platform_wheels[leg]) as archive:
        mode = (archive.getinfo(f"{NATIVE}/{stub_binary_name(leg)}").external_attr >> 16) & 0o777
    assert mode == 0o755, oct(mode)
    assert not stat.S_ISDIR(mode)


def test_the_windows_binary_keeps_its_exe_name(platform_wheels: dict[str, Path]) -> None:
    assert f"{NATIVE}/{PRODUCER_BINARY_NAME}.exe" in wheel_members(platform_wheels["windows-x86_64"])


def test_the_pure_wheel_and_the_sdist_are_binary_free_with_an_untracked_stub_planted(pure_build: dict[str, Any]) -> None:
    assert pure_build["wheel"].name.endswith("-py3-none-any.whl")
    assert [m for m in wheel_members(pure_build["wheel"]) if "_native" in m] == []
    assert [m for m in sdist_members(pure_build["sdist"]) if "_native" in m] == []
    assert len(wheel_members(pure_build["wheel"])) > 1000, "floor: the pure wheel is the real package"


def test_every_wheel_member_but_the_binary_pair_is_in_the_platform_wheel_only_by_design(platform_wheels: dict[str, Path], pure_build: dict[str, Any]) -> None:
    pure = set(wheel_members(pure_build["wheel"]))
    for leg, wheel in platform_wheels.items():
        extra = sorted(set(wheel_members(wheel)) - pure - {m for m in wheel_members(wheel) if m.endswith((".dist-info/WHEEL", ".dist-info/RECORD"))})
        assert extra == [f"{NATIVE}/{CHECKSUM}", f"{NATIVE}/{stub_binary_name(leg)}"], (leg, extra)


def test_the_platform_wheels_differ_from_the_pure_wheel_only_in_tag_binary_and_checksum(platform_wheels: dict[str, Path], pure_build: dict[str, Any]) -> None:
    """Wheel composition (T082): same members, same bytes, except the tag metadata, the record, the binary and its checksum."""
    allowed_to_differ = (".dist-info/WHEEL", ".dist-info/RECORD")
    with zipfile.ZipFile(pure_build["wheel"]) as pure:
        pure_bytes = {name: pure.read(name) for name in pure.namelist() if not name.endswith(allowed_to_differ)}
    assert len(pure_bytes) > 1000, "floor: the comparison covers the real package"
    for leg, wheel in platform_wheels.items():
        with zipfile.ZipFile(wheel) as archive:
            platform_bytes = {name: archive.read(name) for name in archive.namelist() if not name.endswith(allowed_to_differ)}
        added = {f"{NATIVE}/{CHECKSUM}", f"{NATIVE}/{stub_binary_name(leg)}"}
        assert set(platform_bytes) - set(pure_bytes) == added, leg
        assert set(pure_bytes) - set(platform_bytes) == set(), leg
        assert [name for name in pure_bytes if pure_bytes[name] != platform_bytes[name]] == [], leg


# --- sdist to wheel, editable ------------------------------------------------------------------------------------


def test_a_wheel_built_from_the_extracted_sdist_without_a_binary_succeeds(pure_build: dict[str, Any], tmp_path: Path) -> None:
    with tarfile.open(pure_build["sdist"]) as archive:
        archive.extractall(tmp_path / "unpacked", filter="data")
    (project,) = sorted((tmp_path / "unpacked").iterdir())
    result = run_build(project, tmp_path / "rebuilt", "--wheel")
    assert result.returncode == 0, result.stderr[-2000:]
    rebuilt = only(tmp_path / "rebuilt", "*.whl")
    assert rebuilt.name.endswith("-py3-none-any.whl")
    assert [m for m in wheel_members(rebuilt) if "_native" in m] == []
    assert len(wheel_members(rebuilt)) > 1000, "floor: the rebuilt wheel is the real package"


def test_an_editable_install_works_without_a_binary(tmp_path: Path) -> None:
    project = copy_build_inputs(tmp_path / "project")
    # hatchling's default editable mode needs the ``editables`` package, which a ``--no-isolation`` test environment does not carry; the
    # path-file mode (``dev-mode-dirs``) exercises the same build hook and wheel assembly without it.
    pyproject = project / "pyproject.toml"
    header = "[tool.hatch.build.targets.wheel]\n"
    text = pyproject.read_text(encoding="utf-8")
    assert text.count(header) == 1
    pyproject.write_text(text.replace(header, header + 'dev-mode-dirs = ["src"]\n'), encoding="utf-8")
    out = tmp_path / "editable"
    build = subprocess.run(
        [sys.executable, "-c", "import hatchling.build as b, sys; print(b.build_editable(sys.argv[1]))", str(out)],
        cwd=project,
        capture_output=True,
        text=True,
        check=False,
    )
    assert build.returncode == 0, build.stderr[-2000:]
    venv = tmp_path / "venv"
    subprocess.run([sys.executable, "-m", "venv", str(venv)], check=True, capture_output=True)
    python = venv / ("Scripts" if sys.platform == "win32" else "bin") / ("python.exe" if sys.platform == "win32" else "python")
    install = subprocess.run(
        [str(python), "-m", "pip", "install", "--no-deps", "--no-index", "--no-cache-dir", str(only(out, "*.whl"))],
        capture_output=True,
        text=True,
        check=False,
    )
    assert install.returncode == 0, install.stderr[-2000:]
    origin = subprocess.run(
        [str(python), "-c", "import importlib.util as u; print(u.find_spec('specify_cli').origin)"],
        capture_output=True,
        text=True,
        check=True,
    ).stdout.strip()
    assert Path(origin) == project / "src" / "specify_cli" / "__init__.py"
    assert not (project / "src" / "specify_cli" / "_native").exists(), "the editable install needs no binary"


# --- packs/internal never ships; the include stays narrowed -------------------------------------------------------


def test_the_packs_include_is_narrowed_to_built_in_for_the_wheel_and_the_sdist() -> None:
    build = tomllib.loads((ROOT / "pyproject.toml").read_text(encoding="utf-8"))["tool"]["hatch"]["build"]["targets"]
    wheel_entries = [*build["wheel"].get("force-include", {}), *build["wheel"].get("include", [])]
    sdist_entries = [entry for entry in build["sdist"]["include"] if entry.startswith("packs")]
    assert wheel_entries and sdist_entries, "floor: both the wheel and the sdist declare a packs entry"
    assert [e for e in wheel_entries if e.startswith("packs") and not e.startswith("packs/built-in")] == []
    assert [e for e in sdist_entries if not e.startswith("packs/built-in")] == []


def test_the_build_inputs_really_contain_the_internal_pack(pure_build: dict[str, Any]) -> None:
    """Non-vacuity: the copy the builds ran in holds ``packs/internal/``, so its absence from the artifacts is the include's doing."""
    internal = list((pure_build["project"] / "packs" / "internal").rglob("*"))
    assert len(internal) > 10, "floor: the internal pack is present in the build inputs"


def test_no_wheel_contains_the_internal_pack(release_dist: Path) -> None:
    wheels = sorted(release_dist.glob("*.whl"))
    assert len(wheels) == 4, "floor: three platform wheels and the pure wheel"
    for wheel in wheels:
        listing = wheel_members(wheel)
        assert [m for m in listing if "packs/internal" in m] == [], wheel.name
        assert any(m.startswith("packs/built-in/") for m in listing), f"{wheel.name} must carry the built-in packs"


def test_the_sdist_carries_the_built_in_packs_and_no_internal_pack_file(pure_build: dict[str, Any]) -> None:
    listing = sdist_members(pure_build["sdist"])
    assert any(m.startswith("packs/built-in/") for m in listing), "floor: the sdist carries the built-in packs"
    assert [m for m in listing if "packs/internal" in m] == []


# --- the real wheels satisfy check_dist_set.py ---------------------------------------------------------------------


def test_the_real_release_set_passes_the_set_and_binary_checks(release_dist: Path) -> None:
    checker = load_checker()
    dist_set = json.loads(DIST_SET.read_text(encoding="utf-8"))
    assert checker.check_dist_set(release_dist, dist_set) == []
    assert checker.verify_binaries(release_dist, dist_set) == []


def test_the_real_release_set_passes_the_manifest_check_and_a_wrong_commit_fails(release_dist: Path, tmp_path: Path) -> None:
    checker = load_checker()
    dist_set = json.loads(DIST_SET.read_text(encoding="utf-8"))
    manifests = tmp_path / "manifests"
    for leg in LEGS:
        directory = manifests / f"producer-{leg}"
        directory.mkdir(parents=True)
        (directory / stub_binary_name(leg)).write_bytes(stub_binary_bytes(leg))
        manifest = {
            "v": 1,
            "leg": leg,
            "wheel_tag": platform_tag_for_leg(leg),
            "binary": stub_binary_name(leg),
            "binary_sha256": sha256_of(stub_binary_bytes(leg)),
            "source_commit": COMMIT,
        }
        (directory / "producer-manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    assert checker.verify_manifests(release_dist, manifests, COMMIT, dist_set) == []
    assert checker.verify_manifests(release_dist, manifests, "9" * 40, dist_set) != []


def test_the_command_line_accepts_the_real_release_set(release_dist: Path) -> None:
    result = subprocess.run(
        [sys.executable, str(CHECKER), "--dist-dir", str(release_dist), "--verify-binaries"],
        capture_output=True,
        text=True,
        check=False,
    )
    assert result.returncode == 0, result.stderr
