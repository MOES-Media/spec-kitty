# Tracer: design decisions

Seeded at plan time; append with date and reason during implementation.

| Decision | Reason |
|---|---|
| Standalone Gradle build at `services/event-producer/`, Kotlin DSL, four subprojects | Mirrors the standalone `contracts/` and `acceptance-jvm/` builds; `:domain` and the clock and log parts lift into #6192/#6193 by moving packages. |
| Client modules live in `status/` beside `zeitgeist_bridge.py` | Routed by the existing router `status` group; a new subpackage would fall into the catch-all. |
| Hatch build hook, off by default, enabled by env var in the assembling job | An unconditional hook breaks `uv sync`, editable and sdist builds (C-007). |
| `build-release` stays the assembling job | Tests pin its name and step names. |
| Events artifact: pinned released wheel fetched at build time | Charter no-vendoring rule; PyPI is the only release channel. |
| Publish log per Mission; fault log per UTC day | Gives retention a countable "run" and whole-file deletion. |
| ADR 2026-10-13-2 amended in place; one new ADR for the delivery decision | One authority per decision. |
| Python build glue (hook, set-check script) accepted as bounded exception, pending fork F1 | The backend dictates Python. |
