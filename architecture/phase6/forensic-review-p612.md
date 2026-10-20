# Phase 6 extension forensic review (P6.7 to P6.12) - DRAFT

Status: DRAFT. Written before the extension's full gate has passed. Nothing here certifies the extension; P6-241 and P6-242 stay PLANNED until their closure
requirements in the ledger are met. "Proven" means a named test failed when the behaviour was removed (mutation) or asserted it directly.

| # | Question | Evidence | Result / open point |
|---|---|---|---|
| 1 | Can the assistant write, execute, approve or activate? | `AssistantBoundaryTest` pins the imports of the assistant package (only `SuggestionService` may import the draft workflow); write verbs including reduce/cut/lessen are refused (`DeterministicRouterTest`, `AssistantEnergyIntegrationTest.p6_224_*`); digest of 6 tables unchanged in the chat and energy tests. | Proven for the tested phrasing. The router is keyword based: unusual phrasing of a change request may be read as a question. A read answer still cannot change anything. |
| 2 | Is every FACT backed by resolvable evidence? | `AnswerComposerTest`, `AssistantIntegrationTest`, `AssistantEnergyIntegrationTest.sourcesResolve`: every FACT carries refs that exist in the source list; platform-written sentences only. | Proven on the tested paths. The model may only reorder presented statements. |
| 3 | Does the local model have any path to the user's text? | `OllamaModelProviderTest` (loopback-only URL, cloud and remote models refused, 1 MB cap, fixed failure codes), `AssistantContractsTest`; model output is validated by `ToolPlanParser` and `EvidenceSelection`. | Proven. The real 3B model was unreliable (routing 0/30, abstention 0/7); the assistant falls back to the deterministic router. |
| 4 | Are unit, provenance and gap rules honest in the energy report? | `EnergyAnalyzerTest` 7, `EnergyIntegrationTest` 4, `AssistantEnergyIntegrationTest` 4; mutants E01 to E10 killed. | Proven. The platform has no power capability type: energy is NOT_AVAILABLE unless a device stores power or energy readings under the configured keys. No baseline, tariff or savings figure. |
| 5 | Does trend analysis overclaim? | `TrendAnalyzerTest`, `TrendIntegrationTest`; P6.8 mutants 15 of 15 killed. | Proven for the tested series. Trends are statistics on stored readings, not a diagnosis. |
| 6 | Is retrieval bounded and read only? | `EvidenceRetrievalIntegrationTest`, `RetrievalBoundaryTest`; P6.7 mutants 14 of 14 killed (R12 retargeted to the lookup path after the statement-timeout line appeared twice). | The statement timeout on the device-name listing path (second occurrence) has no mutation test of its own. NOT_AUTHORIZED is never produced because no user authentication exists. |
| 7 | Can stale mutation output contaminate the evaluation gate? | The full gate read evaluation result files written by a mutated run when its backend batches stopped at batch 1 (attempt 1 of the extension full gate). | Found and corrected: `validate_nexus.clear_evaluation_results()` runs before the backend batches; `tests/test_phase6_eval_gate.py::FreshResults` (2 tests) pins it. The gate reports INCOMPLETE on missing results. |
| 7b | Does retrieval read free text that could carry an injection? | Full gate attempt 3 (batch 7): `DraftEvaluationIntegrationTest` failed `injection.vector_unprotected` (LOG_LINE): the P6.7 retrieval SQL selected `rule_matches.message`, `action_history.message` and `ack_message`. The extension code had never been run against that release-blocking scan. | Found and corrected: the three columns are no longer selected (nothing consumed them; the composer and model prompt never used them) and the matching keys are gone. `EvidenceRetrievalIntegrationTest` now asserts the planted strings appear nowhere. The scan was not weakened. Effect: the evidence endpoint no longer shows ACK or command message text. |
| 8 | Timing-sensitive test | `CommandDispatchMqttIntegrationTest.concurrentSameTargetUsesDurableAcceptanceOrder` (run through `CommandDispatchMqttOutboxModeIntegrationTest`): failed once in batch 1 of attempt 1; passes 11 of 11 as a class and 8 of 8 as a single method in isolation. Phase 4 code, not changed. | UNVERIFIED hypothesis only: the test takes `Instant.now()` on two threads before the acceptance lock and asserts distinct timestamps and lock-order consistency, which may be sensitive to scheduling under load. No assertion or timeout was changed. |

## Not verified here

* The full gate has not passed on the extension tree (attempt 1 failed on the test in row 8; attempt 2 was stopped by the host for low memory; attempt 3 failed in batch 7 on the injection scan, row 7b, corrected).
* Real-model results are one local 3B model on a small held-out corpus; the deterministic router is not independent of that corpus (26 of 30).
* F1, F2, L1, MQTT protocol version enforcement and the deferred HTTP pull transport are unchanged.

## Attempt 6 outbox blocker - focused correction, 2026-10-20

This section supplements the historical draft above; it does not certify Phase 6. P6-241 and P6-242 remain PLANNED.
Attempt 6 completed at 16:19:29 IST with validator exit 1: 15 checks PASS, backend and deterministic evaluation FAIL.
Backend coverage stopped at 4/13 batches, 83/294 classes, 618 tests, 0 failures, 1 error, 0 skipped.
The error was an empty claim in `UncertainDeliveryBarrierTest.everyUncertainPathCreatesTheHold_timeoutLateCompletionAndLeaseLoss`
(original line 211). Evaluation correctly remained INCOMPLETE with no fresh required results; partial passes are not combined into certification.

### Diagnosis and evidence

Evidence directory: `D:/nexus-p5-evidence/p6-attempt6-outbox-fix-20261020T164005/`.
It preserves the original failing Surefire XML/text, attempt-6 validation summary and batch-4 log before focused execution.
New runs use unique Surefire report suffixes; their XML/text and console logs are retained separately.

* The old fixture used `Instant.now().minusSeconds(13)`. `OutboxSql.queued` sets expiry to payload epoch second + 15,
  leaving only 1-2 seconds on the JVM clock, not a guaranteed two seconds on PostgreSQL's clock.
* One instrumented original-method run passed (`01-original-diagnostic.log`); this alone was not treated as a diagnosis.
* A bounded diagnostic then reproduced the original recipe against the ACTUAL clocks: **12/12 claims were empty**.
  The first sample captured JVM time `11:15:21.750436500Z`, expiry `11:15:23Z`, and database wall time `11:15:23.279825Z`.
  Before and after each rejected claim: state QUEUED, due=true, unacknowledged=true, no_older_unresolved=true,
  no_uncertain_hold=true, **unexpired=false**. The database was approximately 1.5 seconds ahead of the JVM observations.
  See `05-actual-clocks-diagnostic.log` and `actual-clock-diagnostic-source.java` (temporary diagnostic source, removed afterwards).
* This proves the rejecting eligibility predicate and clock-domain failure mode on the live environment, rather than inferring
  a timing problem from an isolated pass. Attempt 6 did not record its SQL timestamps, so its exact historical offset is not recoverable.
* The permanent regression uses a controlled three-second writer-clock lag and proves that expiry alone rejects the old recipe,
  no attempt/publication is recorded, and the never-attempted row expires as EXPIRED, not UNCERTAIN_EXPIRED.

### SQL, transactions and isolation

`OutboxDeliveryOperations.tryClaim` uses its own REQUIRES_NEW transaction and atomically rechecks state, due time,
expiry, ACK and per-device guards with FOR UPDATE SKIP LOCKED; it can also return empty on a unique-index race.
The test kit wires no publish gate. The isolated fixture uses a fresh device, committed auto-commit inserts, and no concurrent
worker on that device. Default JUnit execution is sequential; the class has its own disposable PostgreSQL and truncates
outbox/history before each method. Other devices' holds cannot reject this row.

PostgreSQL `now()` is transaction-start time; `clock_timestamp()` advances during a transaction
([PostgreSQL 16 documentation](https://www.postgresql.org/docs/16/functions-datetime.html#FUNCTIONS-DATETIME-CURRENT)).
Claim eligibility uses `now()` in its short transaction; completion uses `clock_timestamp()` against the immutable expiry
and fences by state, owner and attempt. Delay before claim's database transaction can consume the fixture margin; a JVM/database
offset can remove it immediately. The reproduced rejection needed no deliberate scheduling delay or competing transaction.
No production claim, completion, lease, ordering, hold, trigger or migration logic was changed.

### Narrow test-only correction and safety checks

Only `backend/src/test/java/com/nexus/action/outbox/UncertainDeliveryBarrierTest.java` is changed for the blocker.
The late branch creates its ordinary 15-second delivery window from database time, one second in the past, then waits
on `clock_timestamp() > expires_at + 10 ms` instead of sleeping an assumed 3.2 seconds. The wait has a 20-second bound;
the existing class timeout and validation budgets are unchanged. The test does not rewrite expiry or disable database guards.
Its branch-local lease is MAX_DELIVERY_WINDOW + LEASE (26 seconds), and the test asserts that lease is still live at completion,
so delivery expiry is tested independently of the separate lease-loss branch.

Assertions pin UNCERTAIN_EXPIRED / PUBLISH_COMPLETED_AFTER_EXPIRY, FAILURE rather than success, the exact attempt/uncertainty/
dead-letter facts, and fencing of a duplicate late completion. All three uncertain paths now assert that both candidate selection
AND direct claim block the successor, with zero successor attempts. Existing real-time expiry and hold-boundary tests remain.

### Focused validation only

All Maven invocations ran sequentially from `D:/nexus-p6/backend` with `MQTT_PORT=1`, `JAVA_TOOL_OPTIONS=-Xmx768m`,
`MAVEN_OPTS=-Xmx300m`, command `./mvnw.cmd -Dtest=<selection> -Dsurefire.reportNameSuffix=<suffix> test`.
These are focused-run heap bounds only; full-gate budgets/settings were not edited. All results below have zero failures, errors and skips.

| Log | Selection / suffix | Tests | Result |
|---|---|---:|---|
| 01-original-diagnostic.log | UncertainDeliveryBarrierTest#everyUncertainPathCreatesTheHold_timeoutLateCompletionAndLeaseLoss / p6-a6-diagnostic | 1 | PASS, original recipe with instrumentation |
| 02-corrected-methods.log | UncertainDeliveryBarrierTest#everyUncertainPathCreatesTheHold_timeoutLateCompletionAndLeaseLoss+aNarrowJvmTimeFixtureCanExpireBeforeClaimWhileEveryOtherEligibilityPredicatePasses / p6-a6-corrected-methods | 2 | PASS |
| 03-full-class.log | UncertainDeliveryBarrierTest / p6-a6-full-class | 14 | PASS |
| 04-adjacent-outbox.log | OutboxDeliveryOperationsTest,OutboxDispatchConcurrencyTest,CommandOutboxDispatcherTest / p6-a6-adjacent | 41 (18+7+16) | PASS |
| 05-actual-clocks-diagnostic.log | UncertainDeliveryBarrierTest#diagnoseOriginalFixtureAgainstActualClocks / p6-a6-actual-clocks | 1 (12 samples) | PASS; old-recipe claim rejection reproduced 12/12 |
| 06-repeat-1.log, 06-repeat-2.log, 06-repeat-3.log | UncertainDeliveryBarrierTest#everyUncertainPathCreatesTheHold_timeoutLateCompletionAndLeaseLoss / p6-a6-repeat-1, -2, -3 | 1 each | PASS, 3/3 sequential repetitions |

Adjacent tests cover expiry refusal, late outcomes, lost leases, owner/attempt fencing, eight-thread single-row races,
multiple-instance durable acceptance order, no duplicate fake-broker dispatch and no successor overtaking a stalled/lost command.
These finite runs are not proof against arbitrary host suspension, resource exhaustion or hardware/MQTT delivery behaviour.

### Certification preparation - still open

* The P6.7 release-blocking scan in `DraftEvaluationIntegrationTest` is unchanged from HEAD, including LOG_LINE/message checks.
  The existing retrieval hardening and regression files were preserved byte-for-byte. The scan was inspected, not rerun in this focused campaign.
* `validate_nexus.backend_batches_check` still calls `clear_evaluation_results()` BEFORE backend execution. Its existing changes
  and `tests/test_phase6_eval_gate.py` were preserved byte-for-byte. No stale evaluation JSON was restored to manufacture a PASS.
* The required deterministic inventory is 110 measure rows across nine suites; the stored optional real-model result has nine measures.
  110 + 9 explains 119 arithmetically, not identical provenance or a certified real-model run. Optional absence is NOT_EVALUATED, not PASS.
* P6-242 expressly requires a full pass on a clean tree. A separately authorized, audited local CANDIDATE commit of the pending fixes
  is needed before that qualifying gate; its ledger must remain PLANNED. A later certification-only documentation commit can record
  the actual full-run evidence. No candidate or certification commit was made here, and the clean-tree condition was not bypassed.
* This work modifies no development database: focused Flyway/test writes use disposable Testcontainers only. No full gate was launched;
  no existing validation evidence was deleted. Protected checkouts, firmware, hardware-tests and results artifacts were not modified.
* Resource headroom must be rechecked before a full run; approximately 2.8 GiB free sufficed for these bounded serial tests but is below
  the approximately 5.1 GiB available when attempt 6 started. No applications were terminated or services restarted.
