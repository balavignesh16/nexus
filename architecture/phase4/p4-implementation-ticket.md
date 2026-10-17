# Phase 4 implementation ticket

Date: 2026-10-15.

Status: **P4.0 APPROVED AND FINALIZED (decisions A1 to A9 applied). P4.1 IMPLEMENTED, TESTED AND COMMITTED (1936c2b), INCLUDING ITS SAFETY CORRECTIONS (V38; forensic findings F1, F2, F7). P4.2 IMPLEMENTED, TESTED AND COMMITTED (e91f2f7: V39, atomic acceptance, bounded dispatcher, status separation, R31 safety requirements). P4.3 IMPLEMENTED AND TESTED (evidence-gated retries, persisted backoff, dead letter, crash-safe failure signals; no migration; see below and contract R33). P4.4 IMPLEMENTED AND TESTED (durable ACK facts, persisted ACK timeout worker, runtime legacy reconciler, database-clock ACK deadline; no migration; see contract R34). P4.5 to P4.8 are not started.** P4.1 scope was narrowed on approval to the durable outbox schema (V37), configuration, model and retention protection; the command state history table and its legacy backfill (V39) moved to P4.2.

Normative authority: [reliability-contract.md](reliability-contract.md). This ticket owns rollout, source ownership, dependencies and verification gates. It does not override contract clauses. Acceptance authority: [p4-acceptance-ledger.md](p4-acceptance-ledger.md); a planned test is not proof.

## Baseline and entry gate

Expected baseline: branch `main`, HEAD `1b3055826d196a7149aa102d5e94d351d5ee3a51` (P3.6), latest migration V36. At authoring time the index was empty and the working tree held only the five user-owned documents, the user-supplied report materials, the hardware handoff and previously generated report drafts as modified or untracked paths; this P4.0 task adds only the three files in `architecture/phase4/`.

Before each implementation slice:

1. Inspect branch, HEAD, status and index; preserve expected handoff-document changes and any user work.
2. Confirm the protected paths are untouched: `firmware/**`, `hardware-tests/**`, `results/**`, Review III artefacts, and the five user-owned documents (`CLAUDE.md`, `NEXT_TASK.md`, `PROJECT_HANDOFF.md`, `CODEX_HANDOFF.md`, `ROADMAP.md`).
3. Do not query, migrate, repair, reset or recreate the development PostgreSQL. Do not run `start.bat`, `start_nexus.py` or `seed.ps1` for planning or isolated validation. Use disposable PostgreSQL and Mosquitto.
4. Never change the checksum of V1 to V36. New migrations start at V37.
5. No staging, commit, tag or push unless explicitly requested for that operation.

The user-owned `PROJECT_HANDOFF.md` and `NEXT_TASK.md` are stale (they still describe Phase 3 as 20 COVERED / 52 PLANNED). Updating them is a separate, explicitly authorised step after a milestone.

## Baseline dependency map

Paths are under `backend/src/main/java/com/nexus/` unless stated.

| Source area | Current role | Planned ownership |
|---|---|---|
| `action/application/CommandAcceptanceService.java` | Device-locked durable acceptance (`REQUIRES_NEW`); writes ActionHistory ACCEPTED_FOR_DISPATCH, desired state, transition, verification init | P4.2 adds outbox and history rows in the same transaction; P4.6 adds cap checks |
| `action/application/CommandDispatchCoordinator.java` | Accept, then publish after commit; conditional publish-result update; derived rule fact on failure | P4.2 branches on the flag: legacy path unchanged when OFF, accept plus nudge when ON |
| `action/application/executor/CommandExecutor.java` | `prepare` (guards, payload inputs), `publishPrepared` (QoS 1, payload built with `Instant.now()`), `buildPayload` | P4.2 builds the payload once at acceptance (outbox mode) and exposes a publish-bytes method; `prepare` rules unchanged |
| `action/domain/ActionHistoryRepository.java`, `ActionHistoryRecord.java`, `ExecutionStatus.java`, `CommandStatus.java`, `AcknowledgementStatus.java` | Publish and ACK facts; computed TIMED_OUT | Existing conditional updates reused; additive `deliveryState` projection (P4.7) |
| `device/command/DeviceCommandAckMessageHandler.java` | First-ACK-wins guarded update, backend receipt time | P4.4 appends ACK history facts in the same transaction |
| `infrastructure/mqtt/TelemetryMQTTListener.java`, `MQTTConfiguration.java`, `MqttReconnectController.java` | Single MQTT client, manual acknowledgement after processing, memory persistence | Unchanged connection model; P4.5 uses the existing acknowledge-after-processing behaviour |
| `telemetry/api/dto/TelemetryRequest.java`, `telemetry/application/TelemetryService.java` | Ingestion contract and service | P4.5 optional `messageId` and dedupe in the same transaction |
| `rule/trigger/RuleAckTimeoutScanner.java`, `RuleFactRecoveryScanner.java`, `rule/trigger/RuleSourceFacts.java` | Existing ACK-timeout and publication-failure rule facts, recovery scanning pattern | Reused; not duplicated (R14, R24) |
| `twin/application/VerificationService.java`, `VerificationDeadlineWorker.java` | Verification state and deadlines | Called on dead letter only; unchanged otherwise |
| `infrastructure/retention/RetentionManager.java` | Daily cleanup of telemetry, traces, events, actions | **P4.1 (done):** purges resolved outbox rows first and never selects action rows that have an outbox row; P4.5 adds the dedupe purge |
| `rule/ai/ExplanationRateLimiter.java` | Token-bucket pattern | Pattern reused for the publish rate limiter (P4.6) |
| `pipeline/**` | Pipeline traces | P4.7 adds transition steps |
| `event/application/EventDispatcher.java` | In-process listener list | Unchanged (D5, contract R28) |
| `simulator/src/nexus_simulator/mqtt_publisher.py`, `command_handler.py` | Simulated telemetry, command handling and ACK | P4.3 configurable stale window; P4.5 `messageId` and redelivery hook |
| `frontend/src/**` Actions and command views | Display of action and ACK status | P4.7 lifecycle and dead-letter display |
| `validate_nexus.py`, `backend/src/test/**`, `frontend/**` tests, `simulator/tests/**` | Validation gate and suites | Extended in P4.8; assertions never weakened |

New backend code lives in new packages `action/outbox` (model, repository, dispatcher, sweeps, state machine) and `action/lifecycle` (state history, projection), keeping upstream packages independent of them. Existing classes receive narrow hooks only.

## Slice dependency graph

```text
P4.0 (this) -> P4.1 -> P4.2 -> P4.3 -> P4.4 ----+
                  |       |                      |
                  |       +-> P4.6 (needs P4.2, P4.3)
                  +-> P4.5 (needs P4.1; independent of P4.2 to P4.4) -+
                                                                       |
P4.7 (needs P4.2, P4.3, P4.4, P4.5, P4.6) -> P4.8 (needs all)  <-------+
```

Strict order: P4.1 (done), P4.2, P4.3, P4.4, P4.5, P4.6, P4.7, P4.8. P4.5 could be scheduled earlier because it does not depend on the outbox, but it is placed after P4.4 to keep heavy validation sequential on the 16 GB development machine. P4.6 requires P4.2 and P4.3 and also bounds P4.5 ingestion. No domain-event outbox slice exists (D5).

## Slice 0 (P4.0): contract foundation

Deliverables are exactly the three Phase 4 documents. No source, test, migration, `ROADMAP.md` or handoff edits. Exit: the user approved the contract, ticket and ledger and resolved the ambiguities A1 to A9 (applied 2026-10-15).

## Slice P4.1: durable outbox schema (V37), configuration and retention protection

Authority: R04, R05, R11, R12, R19, R22, R23. Ledger scenarios: 5 original plus 12 added by the safety corrections (P4-125 to P4-136), all 17 COVERED. Status: **implemented, tested and committed (1936c2b).** Ledger evidence is in the "P4.1 evidence" section of the acceptance ledger.

Delivered:

- `V37__create_command_outbox.sql`: the `command_outbox` table with identity and payload-integrity checks (topic, JSON object, commandId, timestamp, SHA-256 equals the hash of the stored text), state, attempt, lease, terminal and 20 second expiry checks, six indexes (including the single IN_FLIGHT per device unique index), an update guard trigger (immutable identity and payload, terminal rows immutable, legal transitions only, attempt increments only on claim) and a delete guard trigger. Foreign keys: `command_id` to `action_history` ON DELETE RESTRICT; `device_id` to `devices` ON DELETE CASCADE as created by V37, changed to RESTRICT by V38 (see below). The table starts empty and backfills nothing.
- Package `com.nexus.action.outbox`: `OutboxState` (transition table), `OutboxTerminalReason`, `CommandDeliveryLimits` (firmware freshness 30 s, 10 s budget, 20 s ceiling), `OutboxPayload` (built once, SHA-256, verify), `CommandOutboxEntity` (immutable columns, `queued` factory), `CommandOutboxRepository`, `OutboxTransitionRecord` and the `OutboxTransitionRecorder` port (no implementation, no bean), and `CommandOutboxSettings` (flag, limits, startup validation).
- `application.yml`: `nexus.command.outbox.*` defaults; `enabled` is false.
- `RetentionManager`: per-category transactions (telemetry, pipeline traces, domain events, command outbox, action history); resolved outbox rows purged first; the action-history delete excludes rows referenced by current desired state or by any outbox row; failures logged, counted and surfaced (`RetentionFailedException`).
- **Safety corrections after the forensic review** (`V38__command_outbox_safety_corrections.sql`, V37 untouched): `command_outbox.device_id` is ON DELETE RESTRICT; a BEFORE DELETE trigger on `devices` refuses deletion with stable messages; a RETRY_WAIT row must have an attempt left; claiming an expired row is rejected by the database. Application side: port `DeviceDeletionGuard` (device module), `OutboxDeviceDeletionGuard` (action module), `DeviceDeletionBlockedException`, HTTP 409 with a stable `code`. Contract R19, R30 and R31 record the policy.

Not in P4.1 (unchanged by design): acceptance changes, dispatcher, claim queries, any publication or timestamp change, the state-history table and backfill (V39, P4.2), retries, expiry sweeps, REAL-mode dispatch, firmware and simulator.

Verification: unit, schema, safety schema (device deletion, races, concurrent claims, exhaustion, expiry, non-ASCII hash), populated V36 to V38 upgrade, Spring persistence, device-deletion API and retention-isolation tests (restart simulated with a fresh application context) with recorded mutation checks; the fast gate and the full gate.

## Slice P4.2: atomic acceptance and dispatcher

Authority: R05, R07 to R11, R24, R31, R32. Ledger scenarios: 46 (the 32 originally planned, P4-036, P4-098, P4-099 and P4-140 which contract R31 items 4 and 7 make P4.2 requirements, and 10 added by the forensic corrections, P4-143 to P4-152). Status: **implemented and tested, uncommitted; 45 of the 46 are COVERED, P4-017 is partial (its RETRY_WAIT branch needs retries, P4.3).**

Added to this slice on approval: **V39** (`command_state_history`, append-only trigger and the legacy DELIVERY_UNKNOWN backfill, R06 and R16) because the history is first written here. Design constraints carried from P4.1: the dispatcher must bound every publish with the delivery-token wait and the attempt timeout (the shared client's blocking publish waits up to 15 seconds, R12); the mandatory safety requirements of contract R31 (no direct publication bypass when the flag is on, atomic acceptance, no SUCCESS for a queued command, no dispatch of expired commands, lock order outbox then action_history and never the device row, payload completeness, handling of the 23505 claim race, no requeue path, no deletion of unresolved obligations); it must respect the V37 single IN_FLIGHT index and may claim strict per-device FIFO only after proving it under concurrent workers (R09); it decides whether removing a device records an explicit terminal outcome before the cascade (R04).

Deliverables:

- With the flag ON, `CommandAcceptanceService.accept` inserts the outbox row and history rows inside the existing transaction and builds the payload once, with the acceptance-time timestamp and SHA-256. With the flag OFF nothing changes.
- `CommandOutboxDispatcher`: scheduled claim loop using `FOR UPDATE SKIP LOCKED`, per-device ordering and single in-flight, database-clock comparisons, lease and fencing, publish outside any transaction, fenced outcome transaction using the existing conditional ActionHistory updates, after-commit nudge.
- `CommandDispatchCoordinator` branches on the flag; the legacy branch is not modified.
- The dispatcher never calls `RuleExecutionAuthorization`, the acceptance service or any validation (R08).

Not in P4.2: retries beyond recording an uncertain or transient failure, dead-letter recovery, the retry scheduler (P4.3). Scope note: minimal terminal-only expiry handling and lease recovery are included in P4.2 because without them an expired or lost command would block its device queue forever.

Delivered (2026-10-15):

- `V39__command_state_history.sql`: append-only history (UPDATE refused, DELETE only with the parent row via ON DELETE CASCADE so history can never block retention) and the conservative legacy DELIVERY_UNKNOWN backfill (5 minute grace; no outbox row, no replay, no ActionHistory change).
- Package `com.nexus.action.outbox`: `CommandStateHistory` (gapless sequence under a per-command advisory lock, also the `OutboxTransitionRecorder` implementation), `OutboxDeliveryOperations` (candidates, claim with `FOR UPDATE SKIP LOCKED`, fenced outcome, expiry sweep, lease recovery; database clock; 23505 handled), `CommandOutboxDispatcher` (bean only when the flag is `true`; bounded workers; publish outside any transaction; after-commit nudge), `BrokerCommandPublisher` port, `OutboxPayloadValidator`, `OutboxOutcomeListener`; `PahoBrokerCommandPublisher` (delivery-token wait bounded by the attempt timeout, classification confirmed / not sent / uncertain); `OutboxOutcomeReactions` (twin told once, rule fact once, realtime and trace after commit).
- `CommandAcceptanceService.accept` writes the outbox row and the ACCEPTED and QUEUED history facts inside the existing acceptance transaction (flag ON only), builds the payload once with the acceptance-time integer timestamp, validates its completeness, stamps a strictly increasing per-device order key and turns a refused obligation into a rejection. `CommandDispatchCoordinator` branches on the flag and returns ACCEPTED_FOR_DISPATCH; the legacy branch is unchanged. `CommandExecutor.execute` and `publishPrepared` refuse to publish while the flag is ON. `ActionExecutionEngine` records an ACCEPTED trace for a queued command. `ActionHistoryResponse` gains an additive `deliveryState` (omitted from JSON when absent). New setting `nexus.command.outbox.workers` (1 to 16, default 4).
- **Forensic corrections (2026-10-15):** H1 one strictly parsed authoritative flag and an unconditional dispatcher (rollback drains, startup fails if enabled without a dispatcher); H2 the per-device hold after an uncertain outcome (contract R32); M1 conservative NOT_SENT classification; M2 no definitive failure signal for an uncertain or acknowledged command; M3 database time for the wire timestamp, creation, scheduling and expiry; seven tests with the actual ACK handler; the whole dispatch and Phase 3 command-authorization suites also run with the outbox enabled (dual-mode legacy assertions, isolated per-test devices). Details, mutation checks and results are in the ledger evidence section.
- Deviations and findings (also in the ledger evidence section): the ledger wording of P4-004 contradicted contract R08.4 and was amended; the committed firmware has no commandId deduplication while the simulator does (inspected, contract R26 updated); legacy fixtures that bulk-delete devices cannot run with the flag ON because the V38 guard fails closed.


Verification: transaction atomicity and rollback tests; two-worker and two-instance concurrency tests with in-process locks disabled; fencing and lease tests with an injected clock; broker-stopped acceptance test; Phase 3 command and rule dispatch regressions with the flag OFF and ON.

## Slice P4.3: retries, expiry, dead letter, duplicate behaviour

Authority: R10 to R12, R24, R26. Ledger scenarios: 17 (P4-036 and P4-140 moved to P4.2). P4.3 dependencies carried from P4.2: retry ONLY outcomes proven as not sent (contract R10 classification) with backoff inside the window, and NEVER automatically retry an UNCERTAIN outcome without a proven device-side idempotency mechanism (keep the R32 hold); replace the terminal-only transient branch with RETRY_WAIT while attempts and window remain; classify exhaustion as RETRY_EXHAUSTED; extend the ordering proof to RETRY_WAIT; keep the bounded publish, the uncertain-never-success rule, zero reauthorisation and the 23505 handling; model the 30 second firmware freshness check for the window-boundary test.

**P4.3 status (2026-10-17): implemented and tested; the authoritative description of what was built is contract R33 and the P4.3 evidence section of the ledger.** The deliverables below are kept as originally written; where the approved safe policy changed their meaning the change is stated in brackets.

Deliverables:

- Retry scheduler with deterministic backoff, attempt cap and window enforcement; expiry sweep; lease-expiry recovery as uncertain attempts. [Built inside the existing dispatcher, not as a second scheduler: `OutboxRetryPolicy` (pure decision), `OutboxDeliveryOperations.notSent` (the only RETRY_WAIT writer), the claim now accepts RETRY_WAIT rows; the worst case is recomputed from the real settings (10.8 s schedule, 12.55 s end-to-end) and validated at startup. Lease recovery stays uncertain and is NEVER retried.]
- Failure classification table (transient, uncertain, permanent) mapped from Paho exceptions and tested row by row. [`PahoClassificationTableTest` covers every `MqttException.REASON_CODE_*` by reflection: four proven pre-handover codes are transient, everything else is uncertain; permanent = payload integrity.]
- Dead-letter transition: ActionHistory failure, `commandFailed` once, single COMMAND_PUBLICATION_FAILED rule fact, desired state retained. [Added: a bounded recovery scan re-applies exactly those two idempotent signals when a crash lost the post-commit reaction; no new table.]
- No requeue operation in any form (R21).
- Simulator duplicate-command test coverage. The simulator is **not changed** (decision A8): the 30 second firmware freshness check is modelled in a test-side helper and the 300 second simulator default is left as is. [Done as `simulator/tests/test_p43_retry_duplicates.py` and `FirmwareFreshnessBoundaryTest`; neither firmware nor simulator source was modified.]

Verification: bounded-retry, expiry boundary, byte-identical payload capture on a real Mosquitto broker, uncertain-outcome fault injection, zero-reauthorisation assertion, mutation checks on the attempt cap and the expiry guard (recorded and reverted).

## Slice P4.4: ACK facts, persisted timeout, legacy reconciler

Authority: R13, R14, R16. Ledger scenarios: 14.

**P4.4 status (2026-10-18): implemented and tested; the authoritative description is contract R34 and the P4.4 evidence section of the ledger. No migration was needed (V39 already holds the ACK fact types), so V40 stays reserved for P4.5.**

Deliverables: ACK history rows (accepted, rejected, conflict, after timeout, after terminal) [`CommandAckFacts`, written by the actual ACK handler in its own transaction; a late first ACK appends the ACK outcome fact and then the late fact; conflict facts are bounded to 5 per command]; idempotent ACK timeout scanner (single owner of ACK_TIMED_OUT, no second rule fact) [`CommandAckTimeoutScanner`, database clock, row-lock serialised]; runtime legacy reconciler with a grace period that never publishes and never touches rows that have an outbox row [`LegacyDeliveryReconciler`, grace at least 5 minutes]. Added by the review: the ACK deadline and the ACK receipt time come from the database clock, an acknowledged command is never claimed and an acknowledged QUEUED or RETRY_WAIT command is resolved as unconfirmed, and the ACK path takes no outbox lock.

Verification: duplicate, conflicting and late ACK tests; two-instance scanner race; reconciler spy proving zero publications across restarts and with the flag ON; `RuleAckTimeoutScanner` regression.

## Slice P4.5: telemetry inbox deduplication (V40)

**P4.5 status (2026-10-17): implemented and tested on the isolated branch `feature/p45-telemetry-inbox` (worktree `D:\nexus-p45`, started from `e2c77ec`), not merged. The authoritative description is contract R35 and the P4.5 evidence section of the ledger. Ledger scenarios: 17 COVERED plus 9 added by the P4.5 review (P4-159 to P4-167). Decisions applied: A1 narrow poison-message classification, A2 SHA-256 fingerprint, A3 cascade on device deletion, A4 nullable original reference without a foreign key; HTTP identical duplicate 200 with `duplicate: true`, conflicting duplicate 409.**

Authority: R17, R19, R23, R25. Ledger scenarios: 17 (including P4-072 and P4-108, which complete only when V40 exists).

Deliverables: V40 `inbound_message_dedupe`; an invalid `messageId` is rejected (decision A1); optional `messageId` on `TelemetryRequest` for MQTT and HTTP; insert-first dedupe in the telemetry transaction; conflicting duplicate handling; purge in the retention job; metrics; an explicit duplicate/no-op response for HTTP submissions (decision A5: success status with an additive `duplicate: true` field and no second Twin transition). Simulator emits a UUID `messageId` and offers a redelivery hook. `firmware/**` is not modified; payloads from the committed firmware remain accepted without a messageId.

Verification: redelivery tests on real Mosquitto including a message processed but not acknowledged; atomicity under injected failure; concurrent duplicate test; onboarding path test; migration test proving `telemetry_history` and V1 to V36 are untouched.

## Slice P4.6: backpressure and rate limiting

Authority: R18. Ledger scenarios: 6.

Deliverables: queue caps checked inside acceptance under the device lock with typed rejection or visible rule suppression; token-bucket publish rate limiter; ingestion lag metric; no new unbounded in-memory queue.

Verification: cap and race tests, rate-limit test on real Mosquitto, architecture test for unbounded queues.

## Slice P4.7: observability, read API and frontend

Authority: R20, R21, R24. Ledger scenarios: 14 (P4-098 and P4-099 moved to P4.2).

Deliverables: pipeline trace steps per transition; Micrometer metrics with fixed tags; additive read-only lifecycle and dead-letter listing endpoints and a `deliveryState` field; explanation truthfulness for queued, dead-lettered and unknown commands; frontend lifecycle display with separate labels and tolerance for unknown states. **No mutating endpoint and no internal requeue.**

Verification: MockMvc contract tests including 404 or 405 on any mutating path; architecture test for requeue dependencies; Vitest for labels; backend-timestamp latency tests.

## Slice P4.8: integrated validation, fault injection and enablement documentation

Authority: R22, R27, R29. Ledger scenarios: 20 (plus the deferred scenario P4-124 which has no slice).

Deliverables: the complete fault-injection campaign with process-kill points; multi-instance test; flag-off drain test; mutation checks; dry-run and explanation zero-effect fingerprints extended to the new tables; the Phase 4 crash-recovery smoke test added to `validate_nexus.py --full`; the documented, script-checkable enablement and rollback procedure; documentation audit for exactly-once claims; protected-path diff check. After an authorised milestone only, the ledger, `PROJECT_HANDOFF.md` and `NEXT_TASK.md` are updated concisely without erasing history.

## Validation gates (Windows PowerShell, stated working directories)

Run focused regressions first and broad gates only when appropriate to approved changes. Run heavy suites one at a time, foreground.

| Scope | Directory | Command |
|---|---|---|
| Backend, one class | `backend/` | `./mvnw.cmd "-Dtest=ClassName" test` |
| Backend, all | `backend/` | `./mvnw.cmd test` |
| Frontend | `frontend/` | `npm.cmd test -- --run`, `npm.cmd run lint`, `npm.cmd run build` |
| Simulator | `simulator/` | `./.venv/Scripts/python.exe -m pytest -q` |
| Repository fast gate | root | `python validate_nexus.py --fast` |
| Repository full gate | root, normal app stopped, Docker available | `python validate_nexus.py --full` |
| Whitespace | root | `git diff --check`, and after staging `git diff --cached --check` |

Baseline to preserve: official full gate 11 of 11 and fast 8 of 8 at P3.6; backend 152 classes and 1,208 tests; frontend 63 files and 533 tests; Phase 3 ledger 72 COVERED. Every slice must keep these results (counts will rise) with no new skip.

## Slice exit rule

A slice is complete only when: its ledger scenarios are COVERED with exact test names after inspection; the full Phase 3 suites still pass; protected paths are unchanged; `git diff --check` is clean; failures encountered were preserved and explained; and the actual counts, skips and evidence layers are recorded. No slice marks a later requirement complete.

## Stop conditions

Stop and report rather than proceed if: a required gate cannot fit the approved transaction and locking boundaries; the foreign-key or retention interaction breaks the existing retention job (inspected in P4.1; see contract R19 for the pre-existing desired-state finding); a clause would require changing `firmware/**`; the window, lease and retry defaults cannot satisfy the validation rules; or an unexpected overlap with user work appears. Do not weaken a gate, add a requeue path, resend a legacy command or introduce a domain-event outbox to hide a blocker.
