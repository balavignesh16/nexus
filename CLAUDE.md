# NEXUS: instructions for Claude Code

## Start with a small context

1. Read [PROJECT_HANDOFF.md](PROJECT_HANDOFF.md) for the current checkpoint.
2. Read [NEXT_TASK.md](NEXT_TASK.md) for the bounded next assignment.
3. Verify Git branch, HEAD and status. Preserve expected handoff-document changes.
4. Read only the named contract sections, ledger rows and relevant source next.

Do not load the entire repository, chat history, historical handoff or validation
logs at startup. Use targeted searches and bounded file reads. These are ordinary
links, not automatic imports of the large architecture documents.

## Project and authority

NEXUS is a generic physical-intelligence framework, not a three-ESP-only demo.
Capabilities, templates and installed bindings support independently provisioned
devices; legacy ESP profiles remain compatibility paths. Do not claim unlimited
scale or physical validation from software tests.

- Actual implementation: current source, committed migrations and tests.
- Required Phase 3 behavior: `architecture/phase3/rule-engine-contract.md`.
- Dependency order: `architecture/phase3/p3-implementation-ticket.md`.
- Evidence/status: `architecture/phase3/p3-acceptance-ledger.md`.
- Broad goals: `ROADMAP.md`; current agent checkpoint: `PROJECT_HANDOFF.md`.
- `CODEX_HANDOFF.md` is historical; older counts/statuses are not current facts.

The next assignment is read-only planning for frozen evaluation context.
Do not implement it or jump to P3.2 triggers without the user's approval.

## Non-negotiable boundaries

- Preserve `firmware/`, `hardware-tests/`, `results/` and Review III artifacts.
- Do not query, migrate, repair, reset or recreate development PostgreSQL.
- Do not launch `start.bat`, `start_nexus.py` or `seed.ps1` during planning or
  isolated validation; normal startup may attach/migrate development services.
- Use disposable PostgreSQL/Mosquitto for integration evidence. Never change
  existing migration checksums; justify any new migration in the approved plan.
- Preserve user changes. Never reset, restore, stash or discard them implicitly.
- No staging, commit, tag or push unless explicitly requested for that operation.
- Keep source/match persistence and current-owner authorization before acceptance.
- Preserve R1/U1 unit safety, U2 canonical binding identity and U3 winner proof.
- GOOD quality or NULL equality cannot establish missing dimensional provenance.
- Publication, ACK, qualified convergence and physical actuation are separate facts.
- AI explains persisted evidence; it does not authorize activation or dispatch.
- A planned test or passing suite does not close an untested scenario.

## Commands (Windows PowerShell; use the stated working directory)

Repository root: `python validate_nexus.py --fast` / `python validate_nexus.py --full`.
Full validation requires the normal app stopped and Docker available for isolation.
Backend: `backend/`, `./mvnw.cmd test` or `./mvnw.cmd "-Dtest=ClassName" test`.
Frontend: `frontend/`, `npm.cmd test -- --run`, `npm.cmd run lint`, `npm.cmd run build`.
Simulator: `simulator/`, `./.venv/Scripts/python.exe -m pytest -q`.
Whitespace: `git diff --check`; after staging, `git diff --cached --check`.

Run focused regressions first and broad gates only when appropriate to approved
changes. Preserve failures; do not weaken assertions, timeouts, isolation or skips
to obtain green results. Record actual counts, skipped tests and evidence layers.
After an authorized milestone, update the checkpoint/next task concisely without
erasing historical failures or silently marking later requirements complete.
