# NEXUS: current project handoff

## Current handoff — 2026-10-26

Canonical folder: `D:\nexus`; branch: `main`. All phase branches through Phase 6 and the latest pending
UI/model/demo/USB changes have been brought together. Read `README.md` for actual startup commands and
`architecture/consolidation-20261026.md` for the inventory and validation record.
Earlier handoff claims about the next Phase 3 slice or uncommitted work describe old checkpoints only.
Use acceptance ledgers for exact completion: Phase 6 extension certification P6-241/P6-242 is still PLANNED.
Real USB devices and simulated devices remain separate; the physical output issue is not certified resolved.

## Historical handoff (preserved)

Checkpoint: 2026-10-14 (Slices 2-5 implemented locally and uncommitted on top of the 2026-10-13 foundation). This is the small current-state entry point for any agent.
Read `NEXT_TASK.md` next; detailed contracts are referenced, not duplicated here.

## Repository checkpoint and expected local changes

- Location: `D:\nexus`; Windows PowerShell; branch `main`.
- Foundation commit: `d936a3046358f195176e84fb3d08592f584393f9`.
- Parent: `33ad13271b95122e8dffdf168e2d19ec3a2f17a3` (committed P2.4 baseline).
- Message: `feat(rule): add versioned lifecycle and guarded execution foundation`.
- Commit contains the reviewed 64 files: 35 modified, 29 added; 4,290 insertions,
  127 deletions. Final targeted review APPROVED; post-commit checks passed.
- Latest committed migration: V25. V1-V24 were not changed. No tag or push. The working tree additionally holds uncommitted V26-V31 (V1-V25 checksums verified unchanged).
- The worktree/index were clean immediately after that commit.

This handoff package is a later documentation-only change and is intentionally
uncommitted unless the user separately requests a commit. Expected paths are:
`CLAUDE.md`, `PROJECT_HANDOFF.md`, `NEXT_TASK.md`, `ROADMAP.md`, `CODEX_HANDOFF.md`.
Do not reset these to obtain a clean tree. Inspect any additional changes before
working. If this package is subsequently committed, verify the new HEAD/history
rather than assuming HEAD must forever equal the foundation checkpoint.

## Slices 2-5 status (uncommitted working tree)

Frozen context, typed trigger routing, durable timers/schedules, FOR_DURATION/COUNT_WITHIN/ABSENCE_FOR/OFFLINE_FOR/TREND, EDGE_TRUE, MANUAL/RULE_MATCHED chains, persisted ActionPlan authority, V26-V31, API/frontend support: implemented and validated locally, **not staged or committed**. Ledger: **55 COVERED / 17 PLANNED** (72 IDs). Validation after the forensic corrections: backend 1,071 tests / 0 failures-errors-skips, frontend 476, simulator 56 + 3 skips, lint 0 errors / 12 warnings, `validate_nexus.py --fast` 8/8 and `--full` 11/11. The targeted forensic review findings (H1-H4, M1, M2, M6) were corrected; see the ledger section "Forensic corrections".

Still open: **C13 is not retired** (legacy DomainEvent matches still dispatch independently; scenario 66), durable cross-instance cooldown (55/56), hysteresis and mutual-exclusion policy (57/58), RELAY command-integration arbitration (53), explanation narrating arbitration (67), dry-run/replay (59-65, 70), approval (69). One temporal condition per version. Read the ledger section "Slices 2-5 implementation evidence" and the ticket closeout before extending this work.

Source map additions (backend `com.nexus.rule.*`): `snapshot/` and `application/RuleEvaluationSnapshotService`; `trigger/` (facts recorder, router, routing disposition, recovery scanner, retention service, source facts, eligibility, manual service, ACK-timeout scanner); `timer/`; `temporal/` (runtime, store, recorded observations); `plan/` (arbiter, store, planner); `persistence/RuleRepeatStateStore`; `api/RuleRuntimeController`; frontend `ruleTriggerConfig.ts`, `RuleTriggerConfigSection.tsx`.

## What the framework does

Java/Spring Boot backend, React/TypeScript frontend, Python simulator,
PostgreSQL/TimescaleDB, Mosquitto MQTT; firmware/hardware are separately protected.
Java compilation targets 21; Node requires >=22. Actual runtime requirements are
in `backend/pom.xml`, `frontend/package.json` and the repository tooling.

The software path is telemetry -> reported Twin -> legacy domain event -> Rule
evaluation -> persisted match -> guarded command acceptance -> MQTT publication.
ACK handling and explicitly mapped qualified readback provide separate evidence.
Capability definitions/templates/bindings, not new enum cases for every device,
are the generic framework extension points. Existing ESP profiles still work.

## Completed and partial work

| Area | Implemented checkpoint |
|---|---|
| Phase 0 | Reproducible orchestration, ownership/readiness checks, fast/full validation. |
| Phase 1 | Typed generic definitions, templates/bindings, runtime resolution, MQTT onboarding and management UI; isolated browser/MQTT acceptance. |
| P2.1 | V21: reported versus desired intent, durable pre-publication acceptance, derived effective presentation. |
| P2.2/P2.3 | V22/V23: canonical state/history, receipt/freshness/quality, GOOD-based Rule eligibility, UI/history. |
| P2.4 | V24: explicit WRITE-to-READ mappings, ACK/deadlines, durable verification and qualified convergence/restart evidence. |
| P3.4 + P3.1 foundation | V25: immutable executable versions, separate workflow/lifecycle/ownership, validation, reconciliation, guarded execution and minimum API/UI. |

Phase 2 core P2.1-P2.4 is committed; full roadmap exit/operational alerting
obligations must not be inferred complete. Phase 3 is IN PROGRESS, not complete:
the ledger has exactly 72 IDs, **20 COVERED / 52 PLANNED**.
Later Phase 4-9 goals are not complete merely because supporting features exist.
Current AI features are grounded explanations, not autonomous control.

## Foundation behavior that must survive further work

- Working drafts do not replace the active immutable version. Logical
  `rowRevision` and execution ownership `executionEpoch` have different purposes.
- Activation/resume use current installed contracts; invalid/ambiguous imports
  remain non-executable. DB ownership, not cache projection, authorizes execution.
- Durable source and attributed RuleMatch persistence precede Rule action handoff.
- Current ACTIVE/version/epoch/reconciliation/target gates run before new command
  acceptance, desired/history writes and verification; stubs have equivalent gates.
- Already durably accepted commands may finish publication after a later pause.
- R1/U1: reported/effective/explicit-condition units must agree. An unknown unit
  in a definition allowing Celsius/Fahrenheit is not dimensionless. NULL is valid
  only for an authoritative unitless contract; no conversion or historical backfill.
- Unitless NUMBER/BOOLEAN/STRING/ENUM remain supported under their typed contracts.
- U2: canonical installed binding/definition identity wins over retained aliases
  or unrelated states. Historical lookup data is not deleted to fix selection.
- U3: concurrent edits prove the exact persisted winning content/version/revision,
  not merely one successful response; immutable version writes are protected.
- P2.4: MQTT publication success is not ACK, convergence or physical proof. ACK
  never manufactures reported state; GOOD mapped qualified readback is required.

The C13 foundation exception permits independent existing legacy-event dispatch
only. It is NOT arbitration, coalescing or persisted ActionPlan authority.
Full frozen state, typed trigger routing, temporal/timer runtime, chains,
multi-instance cooldown, arbitration, dry-run/replay and reliable delivery remain
open. `CREATE_ALERT` and `WEBHOOK` remain stubs; `READ_WRITE` is not implemented.

## Saved validation, not newly rerun results

| Last approved gate | Actual result |
|---|---|
| Complete backend | 937 passed; 0 failures/errors/skips, 110 XML reports. |
| Complete frontend | 452 passed / 55 files; no failures/skips. |
| Simulator | 56 passed / 3 live-stack skips. |
| Lint/build | 0 lint errors, 12 existing warnings; production build PASS. |
| Focused foundation gate | 203 passed; 0 failures/errors/skips. |
| Fast/full validators | 8/8 and 11/11 PASS. |
| V25 / migrated reconciliation | 2/2 and 6/6 PASS using disposable PostgreSQL. |
| P2.4 verification/restart/MQTT | 33/33, 3/3, 11/11; generic real-Mosquitto loop 1/1. |

R1 had two meaningful red assertion failures before its correction, then green
unit/pipeline regressions. An earlier frontend timeout was reproduced; narrow
OverviewTab fixture isolation/cleanup recovered validation, but the original
timing cause remains UNKNOWN (classification E). No zero-leaks guarantee.
The final authorized commit changed only an excess EOF newline after validation;
all 63 non-ticket staged blobs stayed identical. Full suites were not rerun for it.

Evidence summaries are local ignored files under `.nexus/validation/`:
`p31-r1-fast-final.json`, `p31-r1-full-final.json`,
`p31-r1-worktree-manifest.json`, `p31-authorized-commit-before.json` and
`p31-authorized-commit-after.json`. Detailed red/focused/full XML snapshots and
historical failures are also there. They do not transfer through Git automatically.
If absent on a new machine, report evidence availability honestly; use committed
tests/docs and run only the relevant authorized checks, not invented saved results.

## Source map: open relevant entries only

Backend paths below are relative to `backend/src/main/java/com/nexus/`.

| Concern | Entry points |
|---|---|
| Installed capability contracts | `device/binding/runtime/RuntimeCapabilityResolver.java`, `rule/application/RuleCapabilityValidator.java` |
| Rule evaluation/ownership | `rule/application/RuleMatcher.java`, `rule/application/RuleEvaluator.java`, `rule/persistence/RuleVersionStore.java`, `rule/application/RuleLifecycleService.java` |
| Authorization/command boundaries | `rule/application/RuleExecutionAuthorization.java`, `action/application/CommandAcceptanceService.java`, `action/application/CommandDispatchCoordinator.java` |
| Reported state/quality | `twin/domain/DigitalTwinRepository.java`, `twin/persistence/InMemoryDigitalTwinRegistry.java`, `twin/application/TwinStateQualityEvaluator.java`, `twin/application/FreshnessPolicy.java` |
| Persisted connectivity/verification | `device/connectivity/DeviceConnectivityRepository.java`, `twin/domain/CurrentTwinVerificationRepository.java` |

Key regressions under `backend/src/test/java/com/nexus/`: Rule application tests
`RuleGenericUnitSafetyEvaluatorTest`, `RuleLegacyCapabilityEvaluatorTest`,
`RuleCommandAuthorizationIntegrationTest`, `RuleFoundationIntegrationTest`;
Rule persistence tests `RuleV25MigrationTest`, `RuleMigratedReconciliationIntegrationTest`;
Twin verification/quality/restart tests. Do not read every test at startup.

## Next and later order

The immediate next assignment is **Slice 2: frozen evaluation context, planning
first**. Use the implementation ticket's dependency order, not numeric P3 labels:
frozen context -> explicit triggers -> timer foundation -> temporal reasoning ->
candidates/arbitration/C13 retirement -> chains -> backend dry-run/frontend.
Every slice still needs its own approved scope and acceptance evidence.
Phases 4-9 follow their roadmap/contract dependencies; do not begin them here.

Keep this checkpoint concise after each approved milestone. Preserve full
history in the existing documents; do not re-derive it by reading all old notes.

M-A (2026-10-14, uncommitted): delayed trigger recovery is gated by per-trigger currentness and the rule authority recorded with each fact (V31); ineligible facts are durably EXPIRED and never dispatched. M-B is a declared boundary: an ActionPlan persisted before a crash may remain without command acceptance; there is no resend or outbox.

Source currentness (2026-10-14, uncommitted): a superseded trigger source is refused inside the evaluation snapshot and again at command acceptance under a FOR SHARE lock on the source row (see the ledger section "Source currentness protocol").
