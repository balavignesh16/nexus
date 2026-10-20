# Phase 6 handoff (P6.6)

Branch `feature/p6-intelligence` in the worktree `D:\nexus-p6`, from `9ea6c23` (P6.5). Nothing is merged, pushed or tagged; Phase 7 has not begun. `D:\nexus` (main, at `e2c77ec`) and `D:\NEXUS_Review_Prep` were not modified. `firmware/`, `hardware-tests/` and `results/` are unchanged from the starting commit.

## What Phase 6 delivered

| Slice | What exists |
|---|---|
| P6.1 | Layer 1 explanations of rule matches and non-matches, command lifecycle statements, verification, from persisted evidence; GET only |
| P6.2 | Model gateway: offline by default, consent flag `nexus.ai.send-device-evidence`, strict structured output, call guard; no real provider was ever called |
| P6.3 | AI rule drafts (`ai_rule_drafts`, V42), append-only approvals, database activation guard, local approval CLI `com.nexus.rule.approval.DraftApprovalCli` |
| P6.4 | Deterministic anomaly detectors and read-only recommendations (statistics, no ML), extractive-only narrator |
| P6.5 | Evaluation: corpus `p6-eval-corpus-v1` (300 cases), nine required suites, release gate `tests/p6_eval_gate.py`, claims audit |
| P6.6 | P6-043 mutual-exclusion comparison (`DraftConflictAnalyzer`), V43 freshness check and lock (`ConflictStateLock`), platform-rendered rule-match explanations (model text never shown), CLI conflict acknowledgement, Phase 6 gate wired into `validate_nexus.py --full` |

## Trust boundaries (what the AI can and cannot do)

* The AI proposes; it cannot approve, activate, send a command or change a rule. A model-drafted rule exists only as a draft until a local approver (a database login that is a direct member of `nexus_approver`) approves the exact content hash. Approval creates an INACTIVE version. Activation is a separate step through the normal lifecycle, and the database refuses an AI version that is not approved.
* Approval and every activation or resume of an approved AI version require a conflict analysis that is still fresh (V43). `--acknowledge-conflicts yes` acknowledges conflicts that were found; it never bypasses a stale analysis.
* **The platform has no general user authentication.** Every HTTP endpoint, including rule creation and activation, is open to anyone who can reach it. The approver check proves a database login, not a person. The operating-system account recorded with a decision is a claim.
* Explanations shown to users are platform statements; the model only selects and orders grounded evidence. The stored draft rationale is model prose, checked only by a closed word list (it is not semantically grounded) and shown to the approver.

## How to operate

* Approval tool: see `approval-operator-guide.md` (`list`, `show`, `approve`, `reject`, `--acknowledge-conflicts yes`).
* Evaluation: `cd backend && ./mvnw.cmd -o "-Dtest=com.nexus.intelligence.**.*Test,com.nexus.rule.ai.**.*Test,com.nexus.action.lifecycle.FailureRecommendationIntegrationTest" test`, then `python tests/p6_eval_gate.py` (PASS required). Optional real-model suite: `evaluation/README.md`.
* Validation: `python validate_nexus.py --fast`; `python validate_nexus.py --full` (stops if the backend port is open; sequential backend batches, 2,400 s budget).

## Evidence at the P6.6 commit

* Full gate: PASS, 17 checks. Backend 12 of 12 batches, 277 of 277 classes, **2,075 tests, 0 failures, 0 errors, 1 skipped** (the optional real-model suite), 1,796 s of the 2,400 s budget (+255 s against the 1,541 s Phase 4 baseline). Log: `D:/nexus-p5-evidence/p66_full_gate3.log`.
* Fast gate: PASS.
* Deterministic evaluation gate: PASS, 110 measures (107 PASS, 3 ungated FAIL, 0 NOT_EVALUATED); real-model suite NOT_EVALUATED.
* Phase 3 suite: outbox OFF 776 of 776, outbox ON 776 of 776, on the final V43 build (`p66e-p3-*` logs).
* Mutations: P6.6 set X01 to X26 and P01 to P05, 31 of 31 killed; P6.5 set 40 of 40 killed (rerun); P6.4 set 26 of 28 killed, the other two (N01, N03) are obsolete because the narrator code they patched was replaced in P6.5 (reported as NOT_APPLIED, not as killed).
* Ledger: 95 scenarios, 95 COVERED, 0 PLANNED, 0 DEFERRED.

## Ungated adverse-distribution results (kept visible)

Heavy-tailed noise: outlier false-positive rate 0.96, spike 0.26; a weak 5-sigma spike is found with recall 0.04. Figures are for synthetic series and do not describe real sensors.

## Environment notes for the next person

* **The full gate needs a stopped application and, in practice, no development Mosquitto on port 1883.** With the dev broker up, `AdapterWiringIntegrationTest.p5_142` fails (the test application connects to the real broker and is "eligible"). The accepted P6.6 run used `MQTT_PORT=1` in the environment of `validate_nexus.py` (the same isolation the Phase 3 batch runner uses) instead of stopping the development infrastructure; no test was changed. The first attempt without it failed at that test; the second attempt failed at the known timing test below; the third passed.
* Timing-sensitive tests, not fixed: `UncertainDeliveryBarrierTest`, `CommandDispatchMqttOutboxModeIntegrationTest.concurrentSameTargetUsesDurableAcceptanceOrder` (measured failing 1 of 15 isolated runs on the starting commit and 5 of 15 on this tree; the test touches no code changed in P6.6), `CommandOutboxFlowDispatcherTest.twoDispatchersOfTwoInstancesShareOneLimit`.
* Test databases are one per test class; the draft catalogue shown to the model is capped at 50 devices by id, so a test class that creates many devices can push its own device out of the catalogue (the conflict-approval tests live in their own class for that reason).
* Running the gate or Maven jobs together exhausts the 16 GB development machine; run them one at a time. Background jobs were killed once by the host under low memory.

## Limitations carried forward (none fixed here)

* F1: the connection pool can be exhausted by AI and analysis callers; no fix.
* F2: retry of MQTT messages for unknown devices.
* L1: command deduplication retention is 168 h.
* MQTT protocol version enforcement is not implemented (`D:/nexus-p5-evidence/followups/mqtt-version-enforcement-followup.md`).
* Deferred HTTP pull transport.
* Conflict analysis is exact only for rules and groups the database can see at check time; writers that bypass database triggers (a superuser, TRUNCATE) are outside the lock. See `forensic-review-p66.md` row 4 for the residual list.
* No real model was evaluated; no ML model exists; anomaly detection is statistics on synthetic evaluation data.
* Not built: a free-form question box, energy and usage analysis, trend detector. A proposed P6.7 to P6.12 extension (assistant with read-only lookups, trend detector, real-model connection, usage analysis) was discussed but is NOT approved and is not part of Phase 6 as authorised.
