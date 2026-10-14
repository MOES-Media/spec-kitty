"""Hatch build hook that turns the wheel into a platform wheel carrying the native event producer (mission event-producer-reroute).

Build glue only: it holds no runtime logic and is pinned thin by ``tests/architectural/test_build_glue_thin.py``. It is OFF by default
(``enable-by-default = false`` in ``pyproject.toml``), so ``uv sync``, editable installs, ``uv build --wheel``, the sdist and the
sdist-to-wheel step produce exactly what they produced before. It acts only when ``HATCH_BUILD_HOOK_ENABLE_CUSTOM=true`` and then needs:

* ``SPEC_KITTY_PRODUCER_BINARY``: path of the native producer binary built for the leg;
* ``SPEC_KITTY_PRODUCER_PLATFORM_TAG``: the full wheel tag for the leg, read by the caller from ``scripts/release/dist-set.json``
  (for example ``py3-none-manylinux_2_28_x86_64``). The tag is never derived from the binary.

It then sets ``pure_python = False`` and the explicit ``tag``, and force-includes the binary at ``specify_cli/_native/<name>`` beside
``specify_cli/_native/PRODUCER.sha256``. A copy staged under ``build/`` is made executable (``0755``) for non-Windows tags, because
artifact transfer between CI jobs drops mode bits.
"""

from __future__ import annotations

import hashlib
import os
from pathlib import Path
from typing import Any

from hatchling.builders.hooks.plugin.interface import BuildHookInterface

ENABLE_VARIABLE = "HATCH_BUILD_HOOK_ENABLE_CUSTOM"
BINARY_VARIABLE = "SPEC_KITTY_PRODUCER_BINARY"
TAG_VARIABLE = "SPEC_KITTY_PRODUCER_PLATFORM_TAG"
NATIVE_PACKAGE_DIR = "specify_cli/_native"
CHECKSUM_NAME = "PRODUCER.sha256"
TAG_PREFIX = "py3-none-"
EXECUTABLE = 0o755


def _is_platform_tag(tag: str) -> bool:
    platform = tag[len(TAG_PREFIX) :]
    return tag.startswith(TAG_PREFIX) and platform.isascii() and platform.replace("_", "").isalnum() and platform != "any"


class ProducerWheelHook(BuildHookInterface[Any]):
    PLUGIN_NAME = "custom"

    def initialize(self, _version: str, build_data: dict[str, Any]) -> None:
        if os.environ.get(ENABLE_VARIABLE) != "true":
            return
        binary_text = os.environ.get(BINARY_VARIABLE, "")
        tag = os.environ.get(TAG_VARIABLE, "")
        if not binary_text:
            raise RuntimeError(f"{BINARY_VARIABLE} is required when the producer hook is enabled")
        if not tag:
            raise RuntimeError(f"{TAG_VARIABLE} is required when the producer hook is enabled")
        if not _is_platform_tag(tag):
            raise RuntimeError(f"{TAG_VARIABLE}={tag!r} is not a py3-none-<platform> tag (py3-none-any is refused)")
        binary = Path(binary_text)
        if not binary.is_file():
            raise RuntimeError(f"{BINARY_VARIABLE}={binary_text!r} is not a file")

        data = binary.read_bytes()
        staging = Path(self.root) / "build" / "producer-stage"
        staging.mkdir(parents=True, exist_ok=True)
        staged = staging / binary.name
        staged.write_bytes(data)
        if not tag.rsplit("-", 1)[-1].startswith("win"):
            staged.chmod(EXECUTABLE)
        checksum = staging / CHECKSUM_NAME
        checksum.write_text(f"{hashlib.sha256(data).hexdigest()}  {binary.name}\n", encoding="utf-8")

        build_data["pure_python"] = False
        build_data["tag"] = tag
        force_include = build_data.setdefault("force_include", {})
        force_include[str(staged)] = f"{NATIVE_PACKAGE_DIR}/{binary.name}"
        force_include[str(checksum)] = f"{NATIVE_PACKAGE_DIR}/{CHECKSUM_NAME}"
