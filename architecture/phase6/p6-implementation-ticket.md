# Phase 6 implementation ticket

Status: P6.0 recorded 2026-10-18 on `feature/p6-intelligence` (worktree `D:\nexus-p6`, from the certified Phase 5 commit `1eb3f80`). Authority: `intelligence-contract.md`. Scenarios: `p6-acceptance-ledger.md` (95).

Rules of work: one local commit per milestone; focused tests, affected regressions, architecture checks and evaluation per slice; the full gate only at integration boundaries and at P6.6 (backend budget 2,400 s; if the suite outgrows it, report measured evidence); no merge, push or tag; `D:\nexus`, `D:\NEXUS_Review_Prep`, `firmware/`, `hardware-tests/` and `results/` untouched; heavyweight test jobs strictly sequential; failures are preserved, never weakened; no Phase 7.

## Slices

| Slice | Content | Main deliverables | Scenarios |
|---|---|---|---|
| P6.0 | Contract and acceptance foundation | contract, ticket, ledger, protected-path and ancestry checks | 3 |
| P6.1 | Layer 1 completion | evidence references and presence model; non-match explainer (dry-run based); command explainers (queued, not published, uncertain, ACK timeout, no convergence, failed stage); read-only API; determinism, abstention, contradiction | 21 |
| P6.2 | AI isolation | provider-independent interfaces, opt-in configuration, timeout, retry, breaker, bulkhead, minimised bounded context, structured output validation, architecture rules, fault injection, injection delimiting, origin and model identity | 13 |
| P6.3 | Draft-rule assistance | V42 (`ai_rule_drafts`, append-only `ai_rule_draft_decisions`, approver-role check, activation guard, expiry/supersede/purge functions), `intelligence.draft` (service, policy, store, retention job, propose/read controller), local approval command in `rule.approval` | 19 |
| P6.4 | Anomalies and recommendations | deterministic detectors, read-only recommendation services, evidence windows, retention | 15 |
| P6.5 | Evaluation suite | corpus, safety regressions, metrics, thresholds in the gate, injection corpus, reproducibility | 16 |
| P6.6 | Integrated validation | Phase 3 OFF/ON, full gate, mutation campaign (at least 10), claims audit, forensic review, operator guide, handoff | 8 |

Order: P6.0, P6.1, P6.2, then P6.3 and P6.4 (independent after P6.2), P6.5, P6.6. P6.2 precedes any model-produced content reaching a user or the database.

## Dependency facts used (verified in source at `1eb3f80`)

* Rule versions: `rule_versions(provenance varchar(32))`, `rule_version_workflow(DRAFT, VALIDATED)`, `rule_validation_attempts`, `rules.lifecycle` and `active_version_id`; `RuleLifecycleService.createDraft/draft/transition(validate, activate, pause, resume, archive)/legacySave` with row-revision checks. The API controllers carry no authentication, which is why approval cannot be an HTTP action (D1).
* Dry-run: `DryRunService/Engine` return per-condition evidence, ownership, cooldown and arbitration for a stored version against persisted state; everything hypothetical.
* Command lifecycle: `CommandLifecycleReader/View/Statements/TraceProjector` expose delivery, ACK (including the persisted timeout), convergence, latency and ordered facts with the Phase 4 exposure rules.
* Migrations: V41 is the latest; Phase 6 uses V42 and later.

## Acceptance of the plan

Every scenario has a layer and a pass criterion in the ledger. A slice is complete when its scenarios are COVERED with named tests that ran and passed; deferred items need a recorded reason.

## P6.3 delivered (2026-10-18)

* Migration V42 only (V41 was the latest and no historical migration changed). Two existing tests that assumed the latest version (`TransportSchemaV41Test`) were relaxed to assert what they mean (V41 installed after V40 with a checksum).
* Decision: the draft is stored outside `rules` (see contract section 7). The ledger text for P6-039 was corrected accordingly.
* Deviation noted: no separate "generation log" table; the draft row carries the provenance and the append-only decision table carries the audit trail.

## P6.4 delivered (2026-10-19)

* No migration (V42 is the latest). Nothing is persisted: findings and recommendations are recomputed with stable ids from existing tables and indexes, which satisfies "no duplicate durable recommendations" by having none. The contract's earlier idea of a retention policy for transient recommendations therefore applies to no rows.
* Packages `com.nexus.intelligence.anomaly` (cleaner, four detectors, read-only reader, service, settings) and `com.nexus.intelligence.recommend` (facts reader, service, optional narrator); `AnomalyController` (GET only).
* Findings during the slice (recorded, not hidden): the default minimum baseline of 20 produced false outlier flags on ordinary noise and was raised to 100 from a measurement; command history is stamped by the database clock, so its window must use the database clock (a backend-clock window missed the newest fact).

## P6.5 delivered (2026-10-19)

* Evaluation system: corpus `p6-eval-corpus-v1` (300 cases, hashed manifest), nine required suites and one optional real-model suite under `com.nexus.intelligence.eval`, the gate `tests/p6_eval_gate.py` and its baseline, the manifest tool `tests/p6_eval_manifest.py`, and a Phase 6 claims audit. See `evaluation/README.md`.
* Behaviour changes made because the evaluation found defects (recorded, not hidden): a deterministic wording guard on the rule-match explanation path (claims, summary) and on draft rationales; the recommendation narrator accepts only sentences copied from platform statements; the outlier detector's reference is the most recent quarter of the baseline; the gap detector's irregularity cut-off is 0.3; the rule-match explanation response carries `provenance` (closes P6-016).
* Whole-system failure isolation (P6-011) now has an integration test; it is no longer inferred from structure.
* The release gate was INCOMPLETE at P6.5 solely because mutual-exclusion constraints (P6-043) were not implemented; P6.6 implemented and evaluated them (see below).

## P6.6 delivered (2026-10-20)

* Migration V43 (justified: the freshness functions, the activation-guard change for resume, the writer triggers and the re-issued `ai_draft_decide` cannot live outside the database; V1 to V42 checksums are unchanged). New code: `DraftConflictAnalyzer` (P6-043), `ConflictStateLock` (taken first by `RuleLifecycleService` and `RuleExclusionGroupService`), CLI `--acknowledge-conflicts`, platform-rendered rule-match explanations. `validate_nexus.py --full` runs the Phase 6 evaluation gate after the backend batches; the new tests join the existing batch inventory (277 classes, 2,075 tests, 12 batches, 1,796 s against the 2,400 s budget, which was not changed).
* Defects found while closing the work (recorded, not hidden): a shared-to-exclusive lock upgrade inside the activation guard deadlocked a concurrent-activation test (fixed by ordering the writer trigger after the guard); a check-to-commit window for the stale-analysis check (closed with the lock above); the freshness check had to be VOLATILE to read a snapshot taken after the lock; the P6.5 mutant D06 patched a function V43 re-issues (retargeted).
* Ledger: 95 of 95 COVERED, recalculated from unique IDs. The deterministic evaluation gate is PASS. Open items remain limits, listed in p6-handoff.md.
* Not done: no merge, push or tag; no Phase 7 work; no real model call.
