# Canonical NEXUS consolidation — 2026-10-26

## Canonical project

Use **D:\nexus**, branch **main**. All application source, frameworks, SDKs, schemas, firmware, demo tools,
operator UI and project reports are available from this folder. No active application dependency is a junction
into an older phase worktree: canonical frontend/node_modules and simulator/.venv are physical local directories.
Runtime scripts derive their root from their own location, not a hard-coded phase-worktree path.

The old worktrees and external evidence folders are retained as recovery copies, NOT additional active projects.
They were not deleted or force-cleaned. NEXUS_Review_Prep was not edited. No remote push or tag was performed.

## Git/source provenance

| Source | Starting HEAD | Treatment |
|---|---|---|
| main | e2c77ec4076375bca8b4d08099ce67f806ff9781 | Preserved existing local materials first |
| feature/p45-telemetry-inbox | 4b73289429212494f37ad32b21975d4349f79d09 | Already an ancestor of Phase6 |
| feature/p46-flow-control | b13acb945b1109d3dfcb2103c996f05f4309e7f0 | Already an ancestor of Phase6 |
| feature/p4-final | e29e215e5138de4460e9891e05859a61a098c3aa | Already an ancestor of Phase6 |
| feature/p5-adapters | 1eb3f80f5ef6f646f5993cdd166310031cbe5af7 | Already an ancestor of Phase6 |
| feature/p6-intelligence | 3ed380a29ac768d147c1c901db5f1d24f169c62b | Latest committed feature baseline plus91 pending paths |

- 18f10ea: preserve the35 existing main-worktree report, sketch and handoff paths before integration.
- 8b94b8c: commit91 latest-feature paths: local intelligence/UI, typed telemetry, infrastructure management,
  navigation, visual rule builder, three-ESP behavioural simulation, USB firmware/bridge, guarded REAL dispatch,
  MQTT startup ordering and focused regressions. It is not a Phase6 certification commit.
- The canonical merge retains both histories rather than flattening, resetting or overwriting them.
- Backup refs: backup/main-before-consolidation-20261026 and backup/p6-before-consolidation-20261026.

The startup test's foreign-worktree fixture was made relative to the tested repository root. Previously it
hard-coded D:\nexus as the foreign process and would wrongly fail when this became the canonical checkout.
No production rule/model/command safety requirement was relaxed for relocation.

## Local state/evidence preservation

- The three SIMULATED UUIDs in .nexus/demo-simulation.json and REAL UUIDs in usb-integration/config.local.json
  were copied byte-for-byte. These files are ignored, not committed; no device re-registration was performed.
- The USB SQLite delivery journal was copied using SQLite's consistent backup API; logical dumps were compared.
  Uncertain command barriers and actual ACK records were preserved, not reset or reinterpreted.
- Isolated USB Python dependencies were copied into .nexus/usb-deps; no old-worktree import is required.
- Phase4.5/4.6/final/5 external evidence plus Phase6 validation/logs were copied into .nexus/archives.
  USB serial diagnostics were copied into ignored usb-integration/diagnostics.761 files were SHA256 verified.
- The original28 report/reference PDFs/DOCX files and ESP1 sketch remain byte-for-byte unchanged at their
  existing paths. Historical handoffs were preserved in18f10ea; current pointers were added above dated notes.
- .env was retained, not copied into Git. Runtime PID/lock manifests from other worktrees were NOT imported.
- Before-state file hashes and archive verification are in ignored .nexus/consolidation-20261026.

No PostgreSQL query, migration, reset, data repair, broker purge, journal deletion or physical actuator test was
performed for consolidation. Existing migration contents match the integrated Phase6 tree. No devices were flashed.

## Focused verification

- Backend110 tests passed, zero failures/errors/skips, both in the source worktree and canonical D:\nexus.
- Frontend130 focused tests across11 files passed in the source worktree and canonical checkout.
- Startup9, USB bridge10 and behavioural model5 Python tests passed from the canonical checkout.
- Canonical frontend TypeScript/Vite production build passed. Existing large-chunk advisory remains.
- C++ local-behaviour checks compiled with warnings-as-errors and exited zero.
- Staged whitespace, exact source inventories, protected local-state exclusions and post-commit checks are required.

These are focused checks, not a new full validation gate or live hardware/model benchmark. No live application
or hardware bridge was started as part of this consolidation. Use README.md/start.bat/start_demo.bat to run it.

## Explicit open work

P6-241/P6-242 remain PLANNED: the final supervised integrated certification has not been rerun for this source.
MQTT intake backlog/latency remains a known operational issue; stored data is not automatically fresh merely
because a connection is Live. Physical LED/servo output and sustained-power/reboot reliability remain unverified.
Real-model quality and deterministic platform safety are separate evaluations; adverse anomaly-quality failures
are retained in their evaluation reports. No Phase7 development, safety bypass or acceptance-ledger closure occurred.
