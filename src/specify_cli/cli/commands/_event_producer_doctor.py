"""Doctor sibling for the event producer: health and explicit, dry-run-first retention (FR-024, FR-030).

Self-registering ``doctor events`` and ``doctor events-retention`` subcommands
(the ``doctor.py`` auto-discovery seam; no edit to ``doctor.py``). Both are a
thin pass-through to the native producer client: ``doctor events`` renders the
client's own fault-log counts (fault rows, not notice rows, inside the
retention window; a success never resets them) and whether the producer binary
is present; ``doctor events-retention`` forwards the raw
``ledger.retention.keep_runs`` / ``max_age_days`` values exactly as read from
``.kittify/config.yaml`` to the producer's ``retain`` operation and renders the
reply. This module decides nothing: the defaults, the validation and the
eligibility rules (an open Mission, a lock, an unfinished step) all live in the
producer. A retain failure is an error to render, never an event fault, so it
writes no fault-log row.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Annotated, Any

import typer

from specify_cli.core.paths import locate_project_root
from specify_cli.status import ProducerClient, fault_summary, locate_binary

from . import _doctor_shared
from ._doctor_shared import console

__all__ = ["register"]

_BINARY_MISSING_NOTE = "producer binary missing: events are NOT produced (degraded; this install carries no native producer)"
_UNREADABLE_NOTE = "fault log unreadable: the fault count below is unknown, not zero"


def events_health(project_root: Path, *, policy: dict[str, Any], json_output: bool) -> None:
    """Report the producer's presence and the fault log's counts; advisory, always exit 0.

    ``policy`` is the raw ``ledger.retention`` mapping; only its ``max_age_days`` (the
    fault-count window) is read, and the client interprets it.
    """
    summary = fault_summary(project_root, max_age_days=policy.get("max_age_days"))
    present = locate_binary() is not None
    if json_output:
        console.print_json(json.dumps({"binary_present": present, **summary}, indent=2))
        raise typer.Exit(0)
    console.print("[bold]Event producer[/bold]")
    console.print(f"  binary: {'present' if present else _BINARY_MISSING_NOTE}")
    if summary["status"] == "unreadable":
        console.print(f"  [yellow]{_UNREADABLE_NOTE}[/yellow]")
    elif summary["status"] == "none_recorded":
        console.print("  faults: none recorded (no fault log)")
    else:
        console.print(f"  faults in the retention window: {summary['faults']} (last: {summary['last_class'] or 'none'})")
        console.print(f"  notices (counted apart): {summary['notices']}")
    raise typer.Exit(0)


def events_retention(project_root: Path, *, policy: dict[str, Any], apply: bool, json_output: bool) -> None:
    """Run the producer's ``retain`` operation (a dry run unless ``apply``) and render its reply.

    ``policy`` is forwarded exactly as read, with no default and no validation: the producer owns both.
    """
    reply = ProducerClient.current().retain(start_path=project_root, dry_run=not apply, policy=policy)
    failed = reply.get("outcome") in ("error", "refused") or reply.get("policy_error") is not None
    if json_output:
        console.print_json(json.dumps(reply, indent=2))
        raise typer.Exit(1 if failed else 0)
    if reply.get("outcome") in ("error", "refused"):
        console.print(f"[red]Error:[/red] retention did not run: {reply.get('reason', 'unknown')}")
        raise typer.Exit(1)
    if reply.get("policy_error") is not None:
        error = reply["policy_error"]
        console.print(f"[red]Error:[/red] ledger.retention.{error.get('key')}: {error.get('message') or error.get('code')}")
        raise typer.Exit(1)
    mode = "deleted" if apply else "eligible (dry run; nothing deleted, pass --apply to delete)"
    items = reply.get("deleted", []) if apply else [entry.get("path") for entry in reply.get("eligible", [])]
    console.print(f"[bold]Retention[/bold]: {len(items)} file(s) {mode}")
    for item in items:
        console.print(f"  - {item}")
    for entry in reply.get("kept", []):
        console.print(f"  kept {entry.get('path')}: {entry.get('reason')}")
    raise typer.Exit(0)


def register(app: typer.Typer) -> None:
    """Register ``events`` and ``events-retention`` onto *app* (doctor.py auto-discovery seam)."""

    def read_policy(project_root: Path) -> dict[str, Any]:
        """The raw ``ledger.retention`` values, exactly as read; absent keys are omitted (the producer defaults them)."""
        from ruamel.yaml import YAML  # noqa: PLC0415 -- lazy: only these commands read the config

        config = project_root / ".kittify" / "config.yaml"
        try:
            data = YAML(typ="safe").load(config) if config.is_file() else None
        except Exception:  # noqa: BLE001 -- an unreadable config forwards nothing; the producer applies its defaults
            return {}
        ledger = data.get("ledger") if isinstance(data, dict) else None
        retention = ledger.get("retention") if isinstance(ledger, dict) else None
        if not isinstance(retention, dict):
            return {}
        return {key: retention[key] for key in ("keep_runs", "max_age_days") if key in retention}

    @app.command(name="events")
    def events(
        json_output: Annotated[bool, typer.Option("--json", help="Machine-readable JSON output")] = False,
    ) -> None:
        """Report the event producer's health: binary presence and the recorded fault count.

        Read-only. A fault log that cannot be read is shown as unreadable, never as zero.

        Examples:
            spec-kitty doctor events
            spec-kitty doctor events --json
        """
        root = _doctor_shared.resolve_project_root_or_exit(locate_project_root, json_output)
        events_health(root, policy=read_policy(root), json_output=json_output)

    @app.command(name="events-retention")
    def events_retention_command(
        apply: Annotated[bool, typer.Option("--apply", help="Delete the eligible files (default: dry run, nothing is deleted)")] = False,
        json_output: Annotated[bool, typer.Option("--json", help="Machine-readable JSON output")] = False,
    ) -> None:
        """List, or with --apply delete, the local event files outside the retention policy.

        Dry run by default. The policy is ``ledger.retention.keep_runs`` and
        ``max_age_days`` in .kittify/config.yaml, applied by the producer.

        Examples:
            spec-kitty doctor events-retention
            spec-kitty doctor events-retention --apply
        """
        root = _doctor_shared.resolve_project_root_or_exit(locate_project_root, json_output)
        events_retention(root, policy=read_policy(root), apply=apply, json_output=json_output)
