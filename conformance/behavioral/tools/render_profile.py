#!/usr/bin/env python3
"""Deterministic projector: ``*.agent.yaml`` -> Claude Code named-agent body.

Mission: doctrine-behavioral-suite-01KYW5XK (M4), WP01, FR-009.

DRAFT / INCOMPLETE (RED, per CHTR-011's ATDD-first discipline): this first
committed version loads the source profile and renders it through the real
``ClaudeCodeProfileRenderer``, but does not yet emit the source-file content
hash C-003 requires manifests to cite, and has no clean CLI error contract
for a missing/malformed source file (a bad path raises an unhandled
traceback rather than returning a stable exit code). The GREEN version
lands in a distinct, later commit.
"""

from __future__ import annotations

import sys
from pathlib import Path

# Prepend this checkout's own src/ to sys.path *before* any specify_cli/
# charter/doctrine import -- see the GREEN commit's docstring for why this
# guard is load-bearing, not decorative.
_THIS_FILE = Path(__file__).resolve()
_REPO_SRC = _THIS_FILE.parents[3] / "src"
sys.path.insert(0, str(_REPO_SRC))

from ruamel.yaml import YAML  # noqa: E402

from charter.profiles import AgentProfile  # noqa: E402
from specify_cli.tool_surface.profiles.renderers import (  # noqa: E402
    ClaudeCodeProfileRenderer,
)


def main(argv: list[str]) -> int:
    source_path = Path(argv[1])
    yaml = YAML(typ="safe")
    with source_path.open("r", encoding="utf-8") as handle:
        data = yaml.load(handle)
    profile = AgentProfile.model_validate(data)
    body = ClaudeCodeProfileRenderer().render(profile)
    sys.stdout.write(body)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
