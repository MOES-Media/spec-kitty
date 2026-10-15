"""Skeleton: the helper is written after its red test (mission event-producer-reroute, WP10 T083)."""

from __future__ import annotations

from pathlib import Path

FLAG = "REQUIRE_NATIVE_PRODUCER"
OVERRIDE = "NATIVE_PRODUCER_BINARY"


def locate_native_binary() -> Path | None:
    return None


def require_native_binary() -> Path:
    return Path("unimplemented")
