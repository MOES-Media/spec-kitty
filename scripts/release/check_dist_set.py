#!/usr/bin/env python3
"""Release-set checks for the producer wheels (mission event-producer-reroute, FR-041, FR-042, AC-024, AC-032, AC-037).

Build glue only: it compares file names with the pinned list in ``dist-set.json``, hashes files and opens archives. It holds no runtime
logic and is pinned thin by ``tests/architectural/test_build_glue_thin.py`` (closed import allowlist, ``hashlib.sha256`` only).

Three checks, each returning every problem it found (an empty list is green):

* ``check_dist_set``: ``dist/`` holds exactly the pinned wheels, the sdist and the pure wheel, all of one version;
* ``verify_binaries`` (``--verify-binaries``): each platform wheel carries one binary whose sha256 equals its ``PRODUCER.sha256``
  member (and, for the Linux and macOS tags, is executable); the pure wheel and the sdist carry no binary;
* ``verify_manifests`` (``--verify-manifests``): checksum continuity from the downloaded ``producer-<leg>`` artifacts to the wheels:
  every manifest names the supplied source commit and the pinned tag, and the recomputed sha256 of the downloaded binary equals both
  the manifest and the wheel member.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import tarfile
import zipfile
from collections.abc import Iterable
from pathlib import Path
from typing import Any

DEFAULT_DIST_SET = Path(__file__).resolve().parent / "dist-set.json"
NATIVE_PREFIX = "specify_cli/_native/"
CHECKSUM_NAME = "PRODUCER.sha256"
MANIFEST_NAME = "producer-manifest.json"
ARTIFACT_PREFIX = "producer-"
SDIST_SUFFIX = ".tar.gz"
WHEEL_SUFFIX = ".whl"
PURE_TAG = "py3-none-any"
EXEC_BITS = 0o111
WINDOWS_MARKER = "win"
UNEXPECTED = "unexpected"


def _file_sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _wheel_parts(name: str) -> tuple[str, str, str] | None:
    """``(distribution, version, tag)`` of ``<dist>-<version>-<py>-<abi>-<platform>.whl``, or ``None`` if the name is not that shape."""
    if not name.endswith(WHEEL_SUFFIX):
        return None
    fields = name[: -len(WHEEL_SUFFIX)].split("-")
    if len(fields) != 5:
        return None
    return fields[0], fields[1], "-".join(fields[2:])


def _sdist_parts(name: str) -> tuple[str, str] | None:
    if not name.endswith(SDIST_SUFFIX):
        return None
    fields = name[: -len(SDIST_SUFFIX)].rsplit("-", 1)
    return (fields[0], fields[1]) if len(fields) == 2 else None


def _expected_tags(dist_set: dict[str, Any]) -> list[str]:
    platform = [str(entry["tag"]) for entry in dist_set.get("platform_wheels", [])]
    return [*platform, *[str(tag) for tag in dist_set.get("pure_wheels", [])]]


def _version_problems(wheels: list[tuple[str, str, str]], sdists: list[tuple[str, str]]) -> list[str]:
    identities = {(distribution, version) for distribution, version, _ in wheels} | set(sdists)
    if len(identities) > 1:
        return [f"files disagree on distribution or version: {sorted(identities)}"]
    return []


def check_dist_set(dist_dir: Path, dist_set: dict[str, Any]) -> list[str]:
    """Every difference between ``dist_dir`` and the pinned list: missing, unexpected, duplicated, or of mixed versions."""
    expected = _expected_tags(dist_set)
    if not expected or not dist_set.get("platform_wheels"):
        return ["floor: the pinned list names no platform wheel, so the check would pass vacuously"]
    if not dist_dir.is_dir():
        return [f"{dist_dir} is not a directory"]
    problems: list[str] = []
    wheels: list[tuple[str, str, str]] = []
    sdists: list[tuple[str, str]] = []
    for path in sorted(dist_dir.iterdir()):
        wheel = _wheel_parts(path.name)
        sdist = _sdist_parts(path.name)
        if wheel is not None:
            wheels.append(wheel)
        elif sdist is not None:
            sdists.append(sdist)
        else:
            problems.append(f"{UNEXPECTED} file {path.name}")
    found = [tag for _, _, tag in wheels]
    for tag in expected:
        if found.count(tag) == 0:
            problems.append(f"missing wheel with tag {tag}")
    for tag in sorted(set(found)):
        if tag not in expected:
            problems.append(f"{UNEXPECTED} wheel with tag {tag}")
        elif found.count(tag) > 1:
            problems.append(f"{found.count(tag)} wheels with tag {tag}, expected exactly one")
    wanted_sdists = int(dist_set.get("sdist", 0))
    if len(sdists) < wanted_sdists:
        problems.append(f"missing sdist: found {len(sdists)}, expected {wanted_sdists}")
    elif len(sdists) > wanted_sdists:
        problems.append(f"{UNEXPECTED} sdist: found {len(sdists)}, expected {wanted_sdists}")
    return [*problems, *_version_problems(wheels, sdists)]


def _wheel_for_tag(dist_dir: Path, tag: str) -> Path | None:
    for path in sorted(dist_dir.glob(f"*{WHEEL_SUFFIX}")):
        parts = _wheel_parts(path.name)
        if parts is not None and parts[2] == tag:
            return path
    return None


def _native_members(archive: zipfile.ZipFile) -> tuple[list[zipfile.ZipInfo], zipfile.ZipInfo | None]:
    """The binary members under ``specify_cli/_native/`` and the checksum member, if present."""
    binaries: list[zipfile.ZipInfo] = []
    checksum: zipfile.ZipInfo | None = None
    for info in archive.infolist():
        if not info.filename.startswith(NATIVE_PREFIX) or info.is_dir():
            continue
        if info.filename == f"{NATIVE_PREFIX}{CHECKSUM_NAME}":
            checksum = info
        else:
            binaries.append(info)
    return binaries, checksum


def _is_windows_tag(tag: str) -> bool:
    return tag.rsplit("-", 1)[-1].startswith(WINDOWS_MARKER)


def _binary_problems(wheel: Path, tag: str) -> list[str]:
    with zipfile.ZipFile(wheel) as archive:
        binaries, checksum = _native_members(archive)
        if len(binaries) != 1:
            return [f"{tag}: expected exactly one binary under {NATIVE_PREFIX}, found {len(binaries)}"]
        if checksum is None:
            return [f"{tag}: no {CHECKSUM_NAME} member"]
        binary = binaries[0]
        fields = archive.read(checksum).decode("utf-8", errors="replace").split()
        name = binary.filename[len(NATIVE_PREFIX) :]
        problems: list[str] = []
        if len(fields) != 2:
            return [f"{tag}: {CHECKSUM_NAME} is not '<sha256>  <name>'"]
        if fields[1] != name:
            problems.append(f"{tag}: {CHECKSUM_NAME} names {fields[1]!r} but the binary is {name!r}")
        actual = _file_sha256(archive.read(binary))
        if fields[0] != actual:
            problems.append(f"{tag}: sha256 of {name} is {actual}, {CHECKSUM_NAME} says {fields[0]}")
        mode = (binary.external_attr >> 16) & 0o777
        if not _is_windows_tag(tag) and not mode & EXEC_BITS:
            problems.append(f"{tag}: {name} is not executable (mode {mode:o})")
        return problems


def _binary_free_problems(dist_dir: Path, dist_set: dict[str, Any]) -> list[str]:
    problems: list[str] = []
    for tag in dist_set.get("pure_wheels", []):
        wheel = _wheel_for_tag(dist_dir, str(tag))
        if wheel is None:
            continue
        with zipfile.ZipFile(wheel) as archive:
            if any(info.filename.startswith(NATIVE_PREFIX) for info in archive.infolist()):
                problems.append(f"{tag}: the pure wheel carries a binary under {NATIVE_PREFIX}")
    for sdist in sorted(dist_dir.glob(f"*{SDIST_SUFFIX}")):
        with tarfile.open(sdist) as archive:
            if any("_native/" in name for name in archive.getnames()):
                problems.append(f"{sdist.name}: the sdist carries a binary under _native/")
    return problems


def verify_binaries(dist_dir: Path, dist_set: dict[str, Any]) -> list[str]:
    """Hash each platform wheel's binary against its ``PRODUCER.sha256``; the pure wheel and the sdist must carry none."""
    entries = list(dist_set.get("platform_wheels", []))
    if not entries:
        return ["floor: the pinned list names no platform wheel, so there is nothing to verify"]
    problems: list[str] = []
    for entry in entries:
        tag = str(entry["tag"])
        wheel = _wheel_for_tag(dist_dir, tag)
        if wheel is None:
            problems.append(f"{tag}: missing wheel")
        else:
            problems.extend(_binary_problems(wheel, tag))
    return [*problems, *_binary_free_problems(dist_dir, dist_set)]


def _read_manifest(directory: Path) -> tuple[dict[str, Any] | None, str | None]:
    path = directory / MANIFEST_NAME
    if not path.is_file():
        return None, "manifest missing"
    try:
        loaded = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None, "manifest unreadable"
    if not isinstance(loaded, dict):
        return None, "manifest is not an object"
    return loaded, None


def _wheel_binary_sha256(wheel: Path, name: str) -> str | None:
    with zipfile.ZipFile(wheel) as archive:
        try:
            return _file_sha256(archive.read(f"{NATIVE_PREFIX}{name}"))
        except KeyError:
            return None


def _leg_problems(entry: dict[str, Any], directory: Path, dist_dir: Path, commit: str) -> list[str]:
    leg = str(entry["leg"])
    manifest, failure = _read_manifest(directory)
    if manifest is None:
        return [f"{leg}: {failure} ({directory.name}/{MANIFEST_NAME})"]
    problems: list[str] = []
    if manifest.get("source_commit") != commit:
        problems.append(f"{leg}: manifest source commit {manifest.get('source_commit')!r} is not {commit!r}")
    if manifest.get("wheel_tag") != entry["tag"]:
        problems.append(f"{leg}: manifest tag {manifest.get('wheel_tag')!r} is not the pinned {entry['tag']!r}")
    name = str(manifest.get("binary", ""))
    downloaded = directory / name
    if not name or not downloaded.is_file():
        return [*problems, f"{leg}: the downloaded binary {name!r} is missing from {directory.name}"]
    recomputed = _file_sha256(downloaded.read_bytes())
    if recomputed != manifest.get("binary_sha256"):
        problems.append(f"{leg}: recomputed sha256 {recomputed} differs from the manifest {manifest.get('binary_sha256')!r}")
    wheel = _wheel_for_tag(dist_dir, str(entry["tag"]))
    member = _wheel_binary_sha256(wheel, name) if wheel is not None else None
    if wheel is not None and member != recomputed:
        problems.append(f"{leg}: sha256 of the wheel member {name} is {member}, the recomputed sha256 is {recomputed}")
    return problems


def verify_manifests(dist_dir: Path, manifests_dir: Path, commit: str, dist_set: dict[str, Any]) -> list[str]:
    """Checksum continuity: manifests, downloaded binaries and wheel members agree, and every leg was built from ``commit``."""
    entries = list(dist_set.get("platform_wheels", []))
    if not entries:
        return ["floor: the pinned list names no platform wheel, so there is nothing to verify"]
    if not commit:
        return ["the expected source commit is empty: pass the checked-out commit"]
    problems = check_dist_set(dist_dir, dist_set)
    pinned = {f"{ARTIFACT_PREFIX}{entry['leg']}" for entry in entries}
    for directory in sorted(manifests_dir.glob(f"{ARTIFACT_PREFIX}*")):
        if directory.name not in pinned:
            problems.append(f"{UNEXPECTED} artifact directory {directory.name}")
    for entry in entries:
        problems.extend(_leg_problems(entry, manifests_dir / f"{ARTIFACT_PREFIX}{entry['leg']}", dist_dir, commit))
    return problems


def _parse_args(argv: Iterable[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dist-dir", default="dist")
    parser.add_argument("--dist-set", default=str(DEFAULT_DIST_SET))
    parser.add_argument("--verify-binaries", action="store_true", help="re-hash each platform wheel's binary against its PRODUCER.sha256")
    parser.add_argument("--verify-manifests", action="store_true", help="prove checksum continuity from the producer-<leg> manifests")
    parser.add_argument("--manifests", help="directory holding the downloaded producer-<leg> artifacts (with --verify-manifests)")
    parser.add_argument("--commit", help="the checked-out source commit every manifest must name (with --verify-manifests)")
    args = parser.parse_args(None if argv is None else list(argv))
    if args.verify_manifests and not (args.manifests and args.commit):
        parser.error("--verify-manifests needs --manifests and --commit")
    return args


def main() -> int:
    args = _parse_args(sys.argv[1:])
    dist_set = json.loads(Path(args.dist_set).read_text(encoding="utf-8"))
    dist_dir = Path(args.dist_dir)
    problems = check_dist_set(dist_dir, dist_set)
    if args.verify_binaries:
        problems.extend(verify_binaries(dist_dir, dist_set))
    if args.verify_manifests:
        problems.extend(verify_manifests(dist_dir, Path(args.manifests), args.commit, dist_set))
    for problem in dict.fromkeys(problems):
        print(f"check_dist_set: {problem}", file=sys.stderr)
    if problems:
        return 1
    print("check_dist_set: the distribution set is complete and consistent")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
