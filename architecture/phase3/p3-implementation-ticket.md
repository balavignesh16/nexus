# Phase 3 implementation ticket

Date: 2026-10-13.
Status: R1 CORRECTED — READY FOR FINAL TARGETED REVIEW after red/green unit/PostgreSQL evidence and fresh focused, fast and full validation; uncommitted. The post-U1/U2 R1 rejection and earlier frontend timeout/recovery remain preserved below. The original timeout cause remains unknown (classification E). This is not forensic approval or commit approval; C13 arbitration and later slices remain deferred.

Normative authority: [rule-engine-contract.md](rule-engine-contract.md). This ticket owns rollout, source ownership, dependencies and verification gates. It does not override contract clauses. Acceptance authority: [p3-acceptance-ledger.md](p3-acceptance-ledger.md).

## Baseline and entry gate

Expected baseline is `33ad13271b95122e8dffdf168e2d19ec3a2f17a3` on `main`, committed P2.4 and V24. The documentation task began with clean worktree/index. The previous full run passed 829 backend, 440 frontend and 56 simulator tests, with 3 live-stack skips. Fast/full, V24 migration, real-Mosquitto software convergence and application-context restart evidence passed previously. P3.0 did not rerun them.

Before each implementation slice inspect HEAD/branch/status/index and protected paths. Preserve firmware, hardware-tests, results, Review III and physical evidence. Stop on unexpected overlapping user work. No schema or implementation is created merely because a filename is listed below.

## Baseline dependency map

| Source area | Current role | Planned ownership |
|---|---|---|
| `backend/src/main/java/com/nexus/rule/persistence/RuleRegistry.java` | Mutable-rule validation, cache, enable/disable | Combined version/lifecycle/validation slice; later active-reference queries |
| `rule/persistence/RuleEntity.java`, `RuleRepository.java` | Current mutable rule persistence | Logical ownership/revision plus immutable version persistence |
| `rule/application/RuleCapabilityValidator.java` | Generic capability/action validation | Reuse under one centralized validation authority |
| `rule/application/RuleEvaluator.java` | Payload/capability AND evaluation | Frozen context and shared dry-run evaluator |
| `rule/application/RuleMatcher.java` | Event matching, cooldown, per-match publication | Typed trigger batches, durable gates and candidate boundary |
| `rule/domain/Rule*.java`, `ConditionEvidence.java` | Compatibility definition/evaluation models | Additive version/context/trigger evidence with legacy adapters |
| `rule/persistence/RuleMatchEntity.java`, `RuleMatchRepository.java`, `RuleMatchPersister.java` | Snapshot/evidence persistence | Legitimate version/trigger/scope attribution and event-less match support |
| `event/application/EventDetector.java`, `EventDispatcher.java`, `event/persistence/DomainEventPersister.java` | Legacy event stream and subscriber persistence | Preserve legacy predicates; explicit persistence authorization gate |
| `telemetry/application/TelemetryService.java`, `twin/application/DigitalTwinService.java` | Ingestion and accepted reported mutation | Truthful accepted-observation/semantic-transition facts |
| `device/connectivity/DeviceStatusMessageHandler.java`, `InMemoryDeviceConnectivityRegistry.java` | Connectivity persistence/cache/realtime | Durable changed-status identity and trigger integration |
| `action/application/CommandDispatchCoordinator.java`, `device/command/DeviceCommandAckMessageHandler.java` | Publication/ACK facts | Narrow typed-trigger hooks; P2.4 semantics preserved |
| `twin/application/VerificationService.java`, `VerificationDeadlineWorker.java` | Readback/deadline/history | Committed verification transition references; no Rule timer coupling |
| `action/application/ActionPlanner.java`, `ActionExecutionEngine.java`, `action/domain/ActionContext.java` | Immediate per-match execution | Candidate creation and authorized-plan handoff |
| `device/binding/BindingManagementService.java` | Cached enabled-rule reference guard | Active-version reference protection including cross-device targets |
| `rule/ai/RuleMatchExplanationContextBuilder.java`, mapper/renderer/grounding/service | Historical explanations | Additive persisted evidence, truthful event-less context and disclaimer correction |
| `frontend/src/api/ruleApi.ts`, `api/aiApi.ts` | Rule/match consumers | Revision/lifecycle/validation and evidence compatibility |
| `frontend/src/components/explorer/device/CreateRuleModal.tsx`, `pages/framework/GenericRulePage.tsx`, `pages/RulesPage.tsx` | Working rule forms/lists | Revision-token and lifecycle UI in owning slices |
| `frontend/src/components/rules/RuleCanvas.tsx`, `RuleSimulator.tsx`, `RuleInspector.tsx`, `utils/workflowCompiler.ts` | Visual draft/mock save and browser simulation | Real supported-definition save and backend dry-run integration; no branching-engine claim |

Paths abbreviated after the first backend rule row remain under `backend/src/main/java/com/nexus/`; frontend paths are explicit. Existing TimeConfiguration, capability/binding validators, target preparation and Twin quality infrastructure are reused, not reimplemented as competing engines.

## Slice 0: reviewed contract foundation

Authority: C01-C23. Deliverables are exactly the three Phase 3 documents. No source/tests/migration/ROADMAP/handoff edits or commits are part of P3.0 materialization. Review the required contract corrections and ledger before planning runtime work.

## Slice 1: combined P3.4 + P3.1 foundation

Authority: [C03-C09](rule-engine-contract.md#c03), [C13 foundation exception](rule-engine-contract.md#c13), [C20-C21](rule-engine-contract.md#c20).

The dependency-safe first slice combines V25 version/lifecycle persistence, immutable RuleVersion ownership, root optimistic rowRevision and independent executionEpoch, central validation, activation/reconciliation, legacy compatibility, truthful pinned match attribution, durable source/match gating, current version/epoch authorization and minimum necessary API/frontend changes (including revision tokens and lifecycle/validation presentation). Working drafts must not displace V1's active ownership.

Use only C13's restricted existing legacy-event compatibility path. ActionPlanner may still dispatch independent legacy matches; it is not arbitration or persisted plan authorization. No new typed/temporal/scheduled/chain triggers, future operators or unsupported policies use the exception. Full frozen state context, cross-instance durable cooldown serialization and P3.5 candidates/plans are not part of this foundation. Later P3.5 owns candidate construction, conflict arbitration, ActionPlan persistence/authorization and replacement of independent legacy dispatch. This is a temporary compatibility policy, not a permanent alternative engine.

Required preflight and rollout:

1. Audit actual populated rule definitions, current enabled flags, historical snapshots and mutation consumers. Include non-EQ/conflicting DEVICE_ID conditions, absent/disabled bindings, missing installed legacy capabilities, generic aliases, invalid action targets and cross-device targets.
2. Record the schema/entity/writer dependency manifest and fail-closed reconciliation projection required by C07. A populated preflight is mandatory evidence, not replaced by an empty test database.
3. Plan V25 as the next migration; do not modify existing checksums. Import only current definitions with actual provenance. New historical attribution stays NULL.
4. Land migration, matching models/repositories, all rule writers/readers and runtime eligibility gate together. Do not ship constraints requiring version ownership while existing writers still create mutable-only rows.
5. Reconcile imported definitions before dispatch eligibility: conservative non-executing imports, durable successful automatic restoration only for valid previously enabled rules; previously disabled or invalid/ambiguous imports remain PAUSED with appropriate diagnostics. Test startup/infrastructure failure closed, context recreation and a user pause/edit during reconciliation that prevents delayed restoration. Activation now is recorded as a new fact, not backfilled history.
6. Exercise modern token requirements and tokenless legacy exception. Update the project's own frontend to submit revisions. Test both stale-token conflicts and the documented limitation of pre-stale tokenless forms.
7. Exercise changed versus unchanged disabled PUT content; submitted executable edits cannot disappear. Failed enabled PUT must retain the prior active version/lifecycle atomically.
8. Make actual DomainEvent persistence a committed prerequisite to legacy authorization without relying on subscriber order. Capture ruleId/versionId/ruleRevision/executionEpoch and immutable content; persist truthful match attribution before action authorization. Inject source/match failures: no dispatch or new command acceptance.
9. Recheck ACTIVE, matching activeVersionId/epoch, reconciliation/activation eligibility and valid target contracts atomically. SEND_COMMAND checks belong inside the existing device-first CommandAcceptanceService transaction, before acceptance/desired/history/verification writes. No outer decision locks may deadlock nested REQUIRES_NEW acceptance. Non-command executors need equivalent owner gates; CREATE_ALERT/WEBHOOK remain stubs. Preserve already durably accepted commands' P2.4 lifecycle/publication and diagnose suppressions without labeling them MQTT failures.
10. Review actual persistence/transaction/proxy/lock boundaries before implementation approval. If these foundation gates cannot fit approved boundaries, report a blocker; do not weaken them, fabricate plans, add outbox/retries or implement arbitration early.

Expected new source concepts, following repository conventions: RuleVersion domain record and persistence entity/repository, lifecycle and scope models, validation result/error DTOs and lifecycle/validation application services. Reuse RuleRegistry/RuleCapabilityValidator/RuleController as adapters to the authoritative service rather than duplicating their rules.

Gate: foundation assertions of ledger 1-19, 21-24, 55, 66, 71 and 72. Required evidence includes populated PostgreSQL migration, transaction rollback, optimistic concurrent edits/activation, pinned immutable definitions and epoch races (including same-version pause/resume), restart ownership, current-contract re-enable checks, source/match persistence failures, guarded acceptance and API/frontend compatibility. Scenario 18's full state snapshot remains Slice 2 work.

Scenario 12 proves resume current-contract revalidation here, NOT durable temporal-progress reset; that remains Slice 5. Scenario 55 may prove logical cooldown compatibility and preserved match history here, NOT atomic cross-instance durable cooldown serialization; that remains Slice 6. Scenario 72 must prove required legacy source/RuleMatch persistence failure blocks authorization and new command acceptance here, NOT P3.5 ActionPlan failure handling or future manual/timer identities. Record a completed FOUNDATION SUBCASE only after its actual tests pass, with exact evidence; leave the full scenario open/PLANNED until all applicable assertions pass. Do not claim full frozen context, typed triggers, durable temporal runtime, arbitration, backend dry-run or complete scenario 72.

## Slice 2: frozen evaluation context

Authority: [C09-C11](rule-engine-contract.md#c09), [C20](rule-engine-contract.md#c20).

Create context acquisition and adapt RuleEvaluator to captured typed inputs. Plan a read-only REPEATABLE READ snapshot service with an actual external Spring proxy boundary; do not rely on an ineffective internal annotation call. Capture active versions, relevant reported state, persisted connectivity and contract/quality/runtime inputs together. Deep-copy nested values. Keep trigger observation separate from current snapshot.

Runtime write/check services use a separate boundary and compare captured revisions/generations. Analyze interaction with existing REQUIRES_NEW persisters and command acceptance; do not hold their required row locks in an outer transaction while invoking them.

Gate: ledger 18, 20-24, 61 and snapshot parts of 59. A deterministic test must update Twin/connectivity/contracts between attempted reads and show one coherent context, not READ COMMITTED mixtures. Preserve legacy operator semantics behind a deliberate adapter.

## Slice 3: P3.2 explicit trigger routing

Authority: [C02-C03](rule-engine-contract.md#c02), [C11-C13](rule-engine-contract.md#c11).

First implement the legacy adapter, accepted telemetry and semantic Twin-change identities. Then implement connectivity, publication/ACK and verification fact hooks. Document each source transaction, identity, timestamp, post-commit handoff and required persistence check. No synthetic historical events and no automatic expansion of imported subscriptions.

Retain and regression-test the legacy DomainEvent durability and match/owner gates already required by Slice 1; do not postpone them to this slice. Implement the other typed source gates under full C13, not the legacy exception. Subscriber registration order and exception isolation never prove authorization. Typed source action dispatch must wait for approved persisted arbitration/plan authority; it cannot reuse independent legacy dispatch as a bypass.

Gate: ledger 25-36, 66, 68, 72. Force event/source persistence failures and assert no action, desired state or MQTT publication. Assert replay/out-of-order rejection and same-value observation versus semantic transition counts. Verification timeout/ACK timeout use separate source facts and C11 boundaries.

## Slice 4: durable timer foundation

Authority: [C11-C13](rule-engine-contract.md#c11), [C18](rule-engine-contract.md#c18), [C21](rule-engine-contract.md#c21).

Plan Rule-owned schedule/deadline/runtime persistence and a bounded worker. Use persistent generation consumption, ownership/status/evidence rechecks and injected Clock. Support the approved initial schedule increment and truthful manual/due identities. Preserve event-less history compatibility rather than inventing DomainEvents. ACK-timeout observation does not retry delivery.

Gate: ledger 33, 36-37, timer foundations of 46-47 and 72. Test two workers discovering the same candidate, restart before/after due, coalesced missed slots and callback failure semantics. Distinguish decision idempotency from reliable external delivery.

## Slice 5: P3.3 temporal reasoning

Authority: [C10-C11](rule-engine-contract.md#c10), [C14-C15](rule-engine-contract.md#c14).

Implement duration/count/absence/offline using the approved temporal identities and quality exceptions. Implement TREND last. Keep runtime windows/samples bounded. Add pause/resume/new-version resets and restart reconciliation without inventing coverage across freshness gaps.

Gate: ledger 39-49 and generation/reset portions of 12, 19, 55. Use controlled Clock and deterministic timer/telemetry lock orders. No sleeping to establish correctness. Count sample versus state-edge occurrences explicitly; a repeated true sample is not silently labeled a distinct physical motion event.

## Slice 6: P3.5 candidates and arbitration

Authority: [C09](rule-engine-contract.md#c09), [C13-C16](rule-engine-contract.md#c13), [C21](rule-engine-contract.md#c21).

Refactor ActionPlanner to generate candidates from matched batches, persist decisions and hand off only authorized plans. Before P3.5 completion, route ALL legacy actions through approved arbitration, remove independent per-match bypass, persist truthful outcomes for every candidate and retire C13's foundation exception. Full C13 ActionPlan authorization is mandatory; there is no permanent alternative legacy authorization path. Keep existing command acceptance/publication meanings and real-device guards.

Add durable cooldown/repeat gates, explicit hysteresis, canonical targets, coalesced contributor evidence and stable conflict outcomes. Plan the current-version/target authorization boundary inside guarded acceptance. A paused version cannot acquire new execution authority, but already accepted commands are not implicitly canceled. Define trusted configuration for safety override before exposing it. Manual approval workflow remains deferred; unsupported configuration is rejected.

Gate: ledger 50-58, 69 and pipeline parts of 66/68/72, plus the ledger's explicit exception-retirement gate. Prove selected/coalesced/suppressed/held outcomes independently of match facts, legacy routing without an independent bypass and failed ActionPlan persistence/authorization preventing new acceptance. Preserve version/epoch checks in guarded acceptance. Test cross-batch guarantees honestly: no global priority claim. No outbox/resend recovery is introduced.

## Slice 7: explicit rule chains

Authority: [C12-C13](rule-engine-contract.md#c12), [C17](rule-engine-contract.md#c17).

Implement subscriptions, bounded queue, logical-rule cycle detection, depth limits and actual root/parent attribution after trigger and candidate boundaries exist. Suppressed or unresolved action candidates do not acquire dispatch through a chain bypass.

Gate: ledger 38 and persistence-gating regression. Test self-cycle, multi-rule cycle, depth boundary and version changes in a chain.

## Slice 8: P3.6 backend dry-run and frontend integration

Authority: [C19-C20](rule-engine-contract.md#c19), [C22](rule-engine-contract.md#c22).

Use the production evaluator/validator/arbitrator with isolated runtime inputs and no dispatch side-effect capability. Add SUPPLIED_STATE/CURRENT_STATE API models and early size/count checks. Historical replay requests receive an explicit unsupported-mode result.

Replace RuleSimulator's independent evaluation. Wire RuleCanvas's supported flat definition save to real backend responses and lifecycle/version operations; do not display mock success. Adapt workflowCompiler/inspector type handling and false/zero/empty STRING values. This does not promise a graph-branching runtime.

Update historical detail/explanation DTOs, context builder/mapper and deterministic disclaimer in their owning evidence changes. AI must explain persisted candidate facts and remain outside authority paths.

Gate: ledger 59-65, 67, 69-70 and frontend/API regression. Prove zero writes/publications across MQTT, desired, actions, matches, cooldown, repeat, temporal, lifecycle and realtime. Compare live/dry decisions from identical frozen inputs.

## Source gaps and future regression ownership

| Existing gap | Owning slice | Required regression reference |
|---|---|---|
| RuleRegistry.enable bypass | Slice 1 | Ledger 7-8, 12 |
| Mutable definitions/no version concurrency | Slice 1 | Ledger 14-18, 71 |
| Ambiguous DEVICE_ID selection | Slice 1/context | Ledger 21-24 |
| Legacy definition without installed capability | Slice 1 | Ledger 1-3, 71; include installed-legacy absence |
| Cross-device action missed by binding guard | Slice 1/runtime authorization | Ledger 1-3, 23 and runtime invalidity |
| Non-durable event ordering | Slice 1 legacy source/match gate; Slice 3 additional typed sources | Ledger 72 foundation and later subcases |
| ActionPlanner per-match dispatch | Slice 1 restricted gated compatibility; Slice 6 replacement/exception retirement | Ledger 50-54, 66, 68, 72; no foundation arbitration claim |
| RuleSimulator divergent evaluator | Slice 8 | Ledger 59-65 |
| RuleCanvas mocked save | Slice 8 | Real persistence/failed-save UI regression alongside ledger 15/63 |
| Frontend false/zero truthiness and empty-string rejection | Slice 8 | Ledger 61 |
| AI all-executors-stub disclaimer | Owning evidence/Slice 8 | Ledger 67-68 and existing renderer/grounding regressions |

These findings do not authorize fixes during P3.0. Relevant existing tests are foundations, not evidence that these Phase 3 scenarios already pass.

## Schema sequencing and transaction review gate

Only Slice 1's approved plan can allocate/create V25. Assign later migration numbers when their approved schemas are concrete. Each migration lands with matching JPA fields/enums/repositories, valid defaults/import policy and compatible writers. Never edit V1-V24 checksums.

Review source persistence -> snapshot acquisition -> runtime decision/match persistence -> plan authorization -> command acceptance -> post-commit publication as separate boundaries. Verify Spring proxy invocation, isolation, row-lock ordering, optimistic retries and managed-entity reloads. Do not assume an annotation alone proves them. Register/cache publication must be after successful commit, not merely after an outer transactional save call returns.

That is the final pipeline. Slice 1 instead uses committed legacy source -> pinned immutable definition/eligible evaluation -> committed attributed match -> atomic current version/epoch authorization inside guarded acceptance -> post-commit publication, only under C13's explicit exception. It does not create a fake plan or claim a full state snapshot. Device-first locking and P2.4 acceptance writes remain protected; inability to fit these boundaries is a blocker, not permission to omit them.

For operational FKs, indexes, uniqueness, history durability and manual/timer event_id alternatives, follow C21. Record retention/volume implications of any evidence addition. Do not add every non-match to durable history by default.

## Test and review discipline

Each slice proceeds through audit/plan -> user approval -> implementation -> focused validation -> full validation when appropriate -> final review -> controlled commit planning. Slices are not automatic commit boundaries; use actual dependencies for coherent buildable history.

Use unit tests for deterministic semantics, real PostgreSQL for migration/transactions/concurrency, forced barriers/observed locks for races, fresh Spring contexts over one persistent database for restart, real Mosquitto for software command effects, frontend/API compatibility tests and repository fast/full validators. Report actual counts/skips and fixture provenance. Context restart is not a separate OS-process restart unless actually performed.

Close acceptance only with exact automated test references, inspected assertions and real reports. Final review must preserve P2.4 command/ACK/readback semantics and protected paths. No physical proof or delivery guarantees may be inferred from software tests.

## Deferred milestones and completion gate

Historical replay remains an OPEN full-roadmap requirement after the initial P3.6 increment; it needs historical evidence acquisition and a separately approved replay plan. Manual approval is an optional deferred workflow. Graph branching and cron/timezone/DST features are deferred increments, not falsely implemented functionality. TREND stays planned last in temporal work.

Acceptance of the initial increments does not declare the entire Phase 3 roadmap complete. Resolve open required roadmap work or approve an explicit scope change before that claim. Only the combined foundation was authorized in this implementation stage; later source/schema work and all staging/commits/tags/pushes remain unauthorized.

## Foundation implementation closeout (uncommitted)

Entry: main / `33ad13271b95122e8dffdf168e2d19ec3a2f17a3`, empty index, no tracked modifications and exactly three untracked design documents. The entry state matched. Firmware, hardware-tests, results, ROADMAP and CODEX_HANDOFF are unchanged. Docker Desktop was started for disposable integration validation; the normal database received no migration or mutation. Read-only preflight found V24, 9 current rules (all originally enabled) and 6,168 historical matches.

Implemented persistence uses the existing JdbcTemplate/JPA transaction infrastructure: RuleVersionStore is the immutable-definition repository and RuleLifecycleService owns logical/version workflow writes. RuleEntity retains the old compatibility mapping; it is not the new executable store or mutation authority. Old manually constructed registry regression fixtures now inject the lifecycle/store adapters; cleanup truncates only their owned disposable Testcontainers database, without disabling history protections.

New modern endpoints under /api/v1/rules:

| Endpoint | Behavior |
|---|---|
| POST /drafts | Create a non-executing draft; explicit initial expectedRevision 0 required. |
| POST /{id}/draft | Append/select a working draft; active execution remains selected. |
| POST /{id}/lifecycle/{operation} | validate, activate, pause, resume or archive with expectedRevision/If-Match; archive returns 204. |
| GET /{id}/versions | Actual immutable definition/workflow/creation provenance history, including archived history. |

Modern missing token returns 428, stale body revision 409, failed strong numeric If-Match 412, conflicting/malformed tokens 400 and lifecycle conflict 409. Validation failures retain the compatible status/error/message/timestamp envelope and add structured details after the failed attempt commits. Legacy tokenless calls retain the documented pre-stale-form limitation; the project's editing/lifecycle UI uses tokens. Responses expose rowRevision separately from human version revision and executionEpoch.

Required transaction boundaries were inspected and exercised: source commit -> pinned definition -> match commit -> device/contract/current-owner checks -> acceptance commit -> publication. There is no fabricated plan. Non-command actions remain stubs with guarded execution. Registry callbacks occur AFTER_COMMIT, skip older projections, preserve archive invisibility and evict on failure; all authorization reads remain database-backed.

Focused evidence includes real PostgreSQL immutability/ownership/populated import, lifecycle/epoch and concurrent edit/reconciliation tests, actual source/match failure injection, reused-source identity/payload tests, binding/definition lock contention observed through pg_stat_activity, current-contract resume, fresh application-context ownership reload, real disposable-Mosquitto accepted-before-pause publication, API tokens, AI minimization and frontend stale-edit feedback. These are software tests, not physical ESP verification. Foundation partial-scenario evidence is recorded in the ledger rather than counting later assertions as passes.

Review findings corrected: deferred ownership triggers blocked populated-migration DDL (flush constraints before DDL); validation clock ordering could hide a newer failure (append ordinal); independent/mutable event payload was not sufficient durable proof (persisted canonical source); legacy syntax skipped effective definition constraints (same validation authority now checks them); stale matches could poison cooldown hydration (eligibility attribution); cross-device/alias references escaped cached guards (active database references); new internal identifiers could leave the AI boundary (sanitizer); polling could overwrite editing tokens (opening snapshot); archive's empty 200 could appear as a client failure (204); new DRAFT enable wrongly used resume (activate). Exact regressions are linked in the ledger and test manifest below.

Validation totals and the final reviewed file manifest follow. No Phase 3 completion claim, commit or subsequent slice is implied.

## Initial implementation validation evidence (2026-10-12; historical)

| Check | Actual final result / provenance |
|---|---|
| Complete backend suite | 865 tests, 0 failures, 0 errors, 0 skips; Maven BUILD SUCCESS. |
| Complete frontend unit suite | 54 files, 442 tests passed. |
| Complete simulator suite | 56 passed, 3 explicit live-stack skips; skips are not passes. |
| Frontend lint | PASS, 0 errors, 12 existing warnings. |
| Frontend production build | PASS; existing bundle-size/tooling warnings remain. |
| validate_nexus.py --full | PASS after the final implementation/test change set; all wrapper checks passed. |
| validate_nexus.py --fast | PASS on the final change set after the full run. |
| V25 PostgreSQL migration | RuleV25MigrationTest: 2 passing tests, populated edge fixtures plus injected whole-migration rollback/recovery; V1-V24 validated, no repair. |
| Lifecycle/validation/reconciliation | RuleFoundationIntegrationTest: 13 passing tests, including tokens, concurrency, backward clock, malformed drafts and read/write infrastructure rollback. |
| Source/match/version/epoch command safety | RuleCommandAuthorizationIntegrationTest: 16 passing tests over actual disposable PostgreSQL/Mosquitto. |
| Fresh application-context ownership | RuleFoundationRestartIntegrationTest: 2 passing tests against one persistent disposable DB; different registry bean verified. |
| Legacy persistence/history | RuleRegistryPersistenceIntegrationTest: 24 passing regression cases using lifecycle/store adapters. |
| Immutable in-memory executable values | RuleDefinitionImmutabilityTest: 2 passing tests; PostgreSQL UPDATE/DELETE rejection independently covered by migration tests. |
| P2.4 MQTT/readback regression | GenericPhysicalIntelligenceLoopSmokeTest: 1 pass in complete suite; CommandDispatchMqttIntegrationTest: 11 passes in suite and separate wrapper smoke. Software publication/ACK/explicit mapped echo only. |
| P2.4 verification context restart | VerificationRestartIntegrationTest: 3 passing tests; not an OS-process restart. |
| Whitespace/document review | git diff --check and standalone untracked whitespace/Markdown table/link/anchor checks PASS; all 72 ledger IDs intact. |

Final generated reports (ignored, not staged): [.nexus/validation/p31-foundation-full-final.json](../../.nexus/validation/p31-foundation-full-final.json), [full text](../../.nexus/validation/p31-foundation-full-final.txt), [fast JSON](../../.nexus/validation/p31-foundation-fast-final.json), [fast text](../../.nexus/validation/p31-foundation-fast-final.txt), and backend/target/surefire-reports exact XML/text assertions/results. These reports were preserved separately so the later fast run does not overwrite full provenance.

Disclosed harness noise: existing Java/MQTT reconnect/container-shutdown warnings and Surefire's forced fork shutdown after System.exit(0) occurred in full output. Maven still reported BUILD SUCCESS with zero test failures/errors/skips and the wrapper exited successfully; those log warnings are not hidden or counted as extra evidence. No physical ESP or reliable delivery claim follows from these runs.

Final state: HEAD/main unchanged, 31 tracked files modified, 22 untracked files (including the three original design documents), 0 staged files, no commit/tag/push. Protected paths and old migration scripts are unchanged. The foundation is ready for acceptance review, not full Phase 3 acceptance; ledger totals remain 71 PLANNED and 1 COVERED, with foundation subcases and unclosed assertions explicitly recorded.


## Initial implementation file manifest (53 paths; historical)

All 53 files below are in scope. Untracked design documents existed on entry and were preserved/updated. Other untracked entries are new foundation implementation/test files. Generated logs/reports stay under ignored .nexus/ or backend/target/ and are not intended source changes. Nothing is staged.

| File | Git state | Classification |
|---|---|---|
| [architecture/phase3/p3-acceptance-ledger.md](p3-acceptance-ledger.md) | Untracked | Preserved/updated Phase 3 contract and evidence |
| [architecture/phase3/p3-implementation-ticket.md](p3-implementation-ticket.md) | Untracked | Preserved/updated Phase 3 contract and evidence |
| [architecture/phase3/rule-engine-contract.md](rule-engine-contract.md) | Untracked | Preserved/updated Phase 3 contract and evidence |
| [backend/src/main/java/com/nexus/action/application/ActionExecutionEngine.java](../../backend/src/main/java/com/nexus/action/application/ActionExecutionEngine.java) | Modified | Backend foundation runtime/API/guards |
| [backend/src/main/java/com/nexus/action/application/CommandAcceptanceService.java](../../backend/src/main/java/com/nexus/action/application/CommandAcceptanceService.java) | Modified | Backend foundation runtime/API/guards |
| [backend/src/main/java/com/nexus/common/exception/GlobalExceptionHandler.java](../../backend/src/main/java/com/nexus/common/exception/GlobalExceptionHandler.java) | Modified | Backend foundation runtime/API/guards |
| [backend/src/main/java/com/nexus/device/application/DeviceService.java](../../backend/src/main/java/com/nexus/device/application/DeviceService.java) | Modified | Backend foundation runtime/API/guards |
| [backend/src/main/java/com/nexus/device/binding/BindingManagementService.java](../../backend/src/main/java/com/nexus/device/binding/BindingManagementService.java) | Modified | Backend foundation runtime/API/guards |
| [backend/src/main/java/com/nexus/device/capability/definition/CapabilityDefinitionService.java](../../backend/src/main/java/com/nexus/device/capability/definition/CapabilityDefinitionService.java) | Modified | Backend foundation runtime/API/guards |
| [backend/src/main/java/com/nexus/device/capability/DeviceCapabilityService.java](../../backend/src/main/java/com/nexus/device/capability/DeviceCapabilityService.java) | Modified | Backend foundation runtime/API/guards |
| [backend/src/main/java/com/nexus/event/persistence/DomainEventPersister.java](../../backend/src/main/java/com/nexus/event/persistence/DomainEventPersister.java) | Modified | Backend foundation runtime/API/guards |
| [backend/src/main/java/com/nexus/rule/ai/AiExplanationContextMapper.java](../../backend/src/main/java/com/nexus/rule/ai/AiExplanationContextMapper.java) | Modified | Backend foundation runtime/API/guards |
| [backend/src/main/java/com/nexus/rule/api/dto/RuleMatchDetailResponse.java](../../backend/src/main/java/com/nexus/rule/api/dto/RuleMatchDetailResponse.java) | Modified | Backend foundation runtime/API/guards |
| [backend/src/main/java/com/nexus/rule/api/dto/RuleRequest.java](../../backend/src/main/java/com/nexus/rule/api/dto/RuleRequest.java) | Modified | Backend foundation runtime/API/guards |
| [backend/src/main/java/com/nexus/rule/api/dto/RuleResponse.java](../../backend/src/main/java/com/nexus/rule/api/dto/RuleResponse.java) | Modified | Backend foundation runtime/API/guards |
| [backend/src/main/java/com/nexus/rule/api/RuleController.java](../../backend/src/main/java/com/nexus/rule/api/RuleController.java) | Modified | Backend foundation runtime/API/guards |
| [backend/src/main/java/com/nexus/rule/api/RuleLifecycleController.java](../../backend/src/main/java/com/nexus/rule/api/RuleLifecycleController.java) | Untracked | Backend foundation runtime/API/guards |
| [backend/src/main/java/com/nexus/rule/application/ActiveRuleReferenceGuard.java](../../backend/src/main/java/com/nexus/rule/application/ActiveRuleReferenceGuard.java) | Untracked | Backend foundation runtime/API/guards |
| [backend/src/main/java/com/nexus/rule/application/RuleCapabilityValidator.java](../../backend/src/main/java/com/nexus/rule/application/RuleCapabilityValidator.java) | Modified | Backend foundation runtime/API/guards |
| [backend/src/main/java/com/nexus/rule/application/RuleDefinitionValidationException.java](../../backend/src/main/java/com/nexus/rule/application/RuleDefinitionValidationException.java) | Untracked | Backend foundation runtime/API/guards |
| [backend/src/main/java/com/nexus/rule/application/RuleExecutionAuthorization.java](../../backend/src/main/java/com/nexus/rule/application/RuleExecutionAuthorization.java) | Untracked | Backend foundation runtime/API/guards |
| [backend/src/main/java/com/nexus/rule/application/RuleExecutionSuppressedException.java](../../backend/src/main/java/com/nexus/rule/application/RuleExecutionSuppressedException.java) | Untracked | Backend foundation runtime/API/guards |
| [backend/src/main/java/com/nexus/rule/application/RuleImportReconciler.java](../../backend/src/main/java/com/nexus/rule/application/RuleImportReconciler.java) | Untracked | Backend foundation runtime/API/guards |
| [backend/src/main/java/com/nexus/rule/application/RuleLifecycleService.java](../../backend/src/main/java/com/nexus/rule/application/RuleLifecycleService.java) | Untracked | Backend foundation runtime/API/guards |
| [backend/src/main/java/com/nexus/rule/application/RuleMatcher.java](../../backend/src/main/java/com/nexus/rule/application/RuleMatcher.java) | Modified | Backend foundation runtime/API/guards |
| [backend/src/main/java/com/nexus/rule/application/RuleMutationException.java](../../backend/src/main/java/com/nexus/rule/application/RuleMutationException.java) | Untracked | Backend foundation runtime/API/guards |
| [backend/src/main/java/com/nexus/rule/application/RuleNonCommandExecutionService.java](../../backend/src/main/java/com/nexus/rule/application/RuleNonCommandExecutionService.java) | Untracked | Backend foundation runtime/API/guards |
| [backend/src/main/java/com/nexus/rule/domain/ImmutableRuleValue.java](../../backend/src/main/java/com/nexus/rule/domain/ImmutableRuleValue.java) | Untracked | Backend foundation runtime/API/guards |
| [backend/src/main/java/com/nexus/rule/domain/Rule.java](../../backend/src/main/java/com/nexus/rule/domain/Rule.java) | Modified | Backend foundation runtime/API/guards |
| [backend/src/main/java/com/nexus/rule/domain/RuleAction.java](../../backend/src/main/java/com/nexus/rule/domain/RuleAction.java) | Modified | Backend foundation runtime/API/guards |
| [backend/src/main/java/com/nexus/rule/domain/RuleExecutionIdentity.java](../../backend/src/main/java/com/nexus/rule/domain/RuleExecutionIdentity.java) | Untracked | Backend foundation runtime/API/guards |
| [backend/src/main/java/com/nexus/rule/domain/RuleProjectionChanged.java](../../backend/src/main/java/com/nexus/rule/domain/RuleProjectionChanged.java) | Untracked | Backend foundation runtime/API/guards |
| [backend/src/main/java/com/nexus/rule/persistence/RuleMatchEntity.java](../../backend/src/main/java/com/nexus/rule/persistence/RuleMatchEntity.java) | Modified | Backend foundation runtime/API/guards |
| [backend/src/main/java/com/nexus/rule/persistence/RuleMatchPersister.java](../../backend/src/main/java/com/nexus/rule/persistence/RuleMatchPersister.java) | Modified | Backend foundation runtime/API/guards |
| [backend/src/main/java/com/nexus/rule/persistence/RuleMatchRepository.java](../../backend/src/main/java/com/nexus/rule/persistence/RuleMatchRepository.java) | Modified | Backend foundation runtime/API/guards |
| [backend/src/main/java/com/nexus/rule/persistence/RuleRegistry.java](../../backend/src/main/java/com/nexus/rule/persistence/RuleRegistry.java) | Modified | Backend foundation runtime/API/guards |
| [backend/src/main/java/com/nexus/rule/persistence/RuleVersionStore.java](../../backend/src/main/java/com/nexus/rule/persistence/RuleVersionStore.java) | Untracked | Backend foundation runtime/API/guards |
| [backend/src/main/resources/db/migration/V25__rule_versions_and_lifecycle.sql](../../backend/src/main/resources/db/migration/V25__rule_versions_and_lifecycle.sql) | Untracked | V25 migration only |
| [backend/src/test/java/com/nexus/rule/ai/AiExplanationContextMapperTest.java](../../backend/src/test/java/com/nexus/rule/ai/AiExplanationContextMapperTest.java) | Modified | Backend automated regression |
| [backend/src/test/java/com/nexus/rule/application/RuleCommandAuthorizationIntegrationTest.java](../../backend/src/test/java/com/nexus/rule/application/RuleCommandAuthorizationIntegrationTest.java) | Untracked | Backend automated regression |
| [backend/src/test/java/com/nexus/rule/application/RuleFoundationIntegrationTest.java](../../backend/src/test/java/com/nexus/rule/application/RuleFoundationIntegrationTest.java) | Untracked | Backend automated regression |
| [backend/src/test/java/com/nexus/rule/application/RuleMatcherTest.java](../../backend/src/test/java/com/nexus/rule/application/RuleMatcherTest.java) | Modified | Backend automated regression |
| [backend/src/test/java/com/nexus/rule/domain/RuleDefinitionImmutabilityTest.java](../../backend/src/test/java/com/nexus/rule/domain/RuleDefinitionImmutabilityTest.java) | Untracked | Backend automated regression |
| [backend/src/test/java/com/nexus/rule/persistence/RuleFoundationRestartIntegrationTest.java](../../backend/src/test/java/com/nexus/rule/persistence/RuleFoundationRestartIntegrationTest.java) | Untracked | Backend automated regression |
| [backend/src/test/java/com/nexus/rule/persistence/RuleRegistryPersistenceIntegrationTest.java](../../backend/src/test/java/com/nexus/rule/persistence/RuleRegistryPersistenceIntegrationTest.java) | Modified | Backend automated regression |
| [backend/src/test/java/com/nexus/rule/persistence/RuleV25MigrationTest.java](../../backend/src/test/java/com/nexus/rule/persistence/RuleV25MigrationTest.java) | Untracked | Backend automated regression |
| [frontend/src/api/ruleApi.ts](../../frontend/src/api/ruleApi.ts) | Modified | Frontend lifecycle/API or canvas honesty |
| [frontend/src/components/explorer/device/CreateRuleModal.test.tsx](../../frontend/src/components/explorer/device/CreateRuleModal.test.tsx) | Modified | Frontend automated regression |
| [frontend/src/components/explorer/device/CreateRuleModal.tsx](../../frontend/src/components/explorer/device/CreateRuleModal.tsx) | Modified | Frontend lifecycle/API or canvas honesty |
| [frontend/src/components/explorer/device/tabs/RulesTab.tsx](../../frontend/src/components/explorer/device/tabs/RulesTab.tsx) | Modified | Frontend lifecycle/API or canvas honesty |
| [frontend/src/components/rules/RuleCanvas.tsx](../../frontend/src/components/rules/RuleCanvas.tsx) | Modified | Frontend lifecycle/API or canvas honesty |
| [frontend/src/pages/RulesPage.test.tsx](../../frontend/src/pages/RulesPage.test.tsx) | Modified | Frontend automated regression |
| [frontend/src/pages/RulesPage.tsx](../../frontend/src/pages/RulesPage.tsx) | Modified | Frontend lifecycle/API or canvas honesty |

## Historical targeted acceptance closure (2026-10-13; superseded by forensic corrections below)

Entry matched the audited 31 modified + 22 untracked / 53 paths / empty index at the same main HEAD. Gate 1 passed 72 focused tests (zero failures/errors/skips), including real PostgreSQL/Mosquitto B1/B2 and affected P2.4 verification/readback cases. Gate 2 extends only approved foundation evidence; no V25 SQL edit or V26 was needed. The normal backend/development datasource was never started or migrated; Docker Desktop was started only to provide disposable test infrastructure.

B1 production owners: RuleCapabilityValidator and RuleEvaluator. Definition-wide allowed units no longer authorize cross-unit raw comparisons; exact resolved binding units and indexed UNIT_MISMATCH diagnostics are enforced. B2 production owners: RuleCapabilityValidator, RuleEvaluator and ActiveRuleReferenceGuard. Shared installed-reference discovery and actual binding UUID evaluation replace inconsistent name/case selection. Canonical dependency lookup does not confer execution permission: disabled/ambiguous/direction-invalid references cannot activate/dispatch, while unrelated uninstalled TRIGGER_DEVICE scopes are not falsely guarded.

Every further correction followed a failing strengthened regression:

| Invariant/root cause | Minimal correction | Exact regression / red artifact |
|---|---|---|
| C11: matcher bypassed injected Clock | RuleMatcher uses Clock for match timestamp and cooldown comparison | RuleCooldownRestartIntegrationTest.persistedLogicalCooldownSurvivesDraftActivationResumeAndLateOldHistory; backend/target/p31-closure-cooldown-red.log |
| C08: activation accepted RELAY binding without configured channel, unlike command preparation | Extract/reuse existing configured RELAY check in CommandValueValidator/CommandExecutor/RuleCapabilityValidator | RuleCommandAuthorizationIntegrationTest.centralValidatorNegativeContractsNeverReplaceOwnerOrAcceptActions; backend/target/p31-closure-relay-red.log |
| C06: new-draft failed If-Match returned body-conflict 409 | Preserve 409 body versus 412 header precondition | RuleFoundationIntegrationTest.newDraftRevisionPreconditionsAndSuccessfulVersionCreationAreTruthful; backend/target/p31-closure-create-precondition-red.log |
| C08: missing scope device diagnosis identified only the whole list | Preserve exact offending condition ordinal | RuleCommandAuthorizationIntegrationTest.centralValidatorNegativeContractsNeverReplaceOwnerOrAcceptActions; backend/target/p31-closure-condition-path-red.log |
| B2 refinement: strict eligibility lookup falsely blocked unrelated global-scope contracts | Separate canonical dependency identity from execution eligibility using shared binding discovery | RuleCommandAuthorizationIntegrationTest.genericConditionsStayInDeviceOrTriggerScopeWithoutMissingScopeExecution; backend/target/p31-closure-global-guard-red.log |
| C03: typed DEVICE_ID could replace source scope with capability value | Capability predicates limited to VALUE; evaluator always compares source DEVICE_ID, including absent scope | RuleCommandAuthorizationIntegrationTest.typedDevicePredicateCannotHijackDeclaredScopeOrActivate; backend/target/p31-closure-scope-red.log |
| B2/C03 refinement: accepted uppercase UUID text disagreed with raw guard/filter/evaluator string checks | Compare actual UUID identity in guard, DEVICE_ID evaluation and device-filter presentation; preserve raw immutable definition/evidence | RuleCommandAuthorizationIntegrationTest.canonicalCrossDeviceWriteReferenceProtectsCaseAndAlias; backend/target/p31-closure-uuid-red.log and p31-closure-uuid-evaluation-red.log; 116 affected focused tests passed |
| C14 foundation compatibility: owner changes after a legitimate eligible match commit erased its in-memory cooldown handoff | RuleMatchPersister returns the eligibility decided inside its committed transaction; RuleMatcher preserves that result independently of later dispatch permission | RuleCommandAuthorizationIntegrationTest.committedEligibleMatchRetainsCooldownWhenOwnerChangesBeforeTimestampHandoff; real PostgreSQL afterCommit barrier and controlled Clock; backend/target/p31-closure-cooldown-handoff-red.log; 70 affected focused passes |

These logs are diagnostic red-run evidence, not final validation failures. No production correction added triggers, temporal runtime, arbitration, plans, delivery recovery or physical capability.

Closure evidence additionally includes actual evaluator/planner/engine barriers for V2/pause/ABA/stubs, accepted-before-pause real publication, all-supported-effect source/match insert failures, modern/legacy MockMvc semantics and overlapping tokenless locks, real migrated-fixture reconciliation and direct PostgreSQL history constraints, fresh-context pending/failed/interrupted imports, both contract-lock orders, real transaction projection rollback, mock-only callback ordering/faults, controlled persisted cooldown/imported global history, archived history/AI facts and 45 focused frontend passes. Full scenario/subcase statuses belong to the ledger; all 72 IDs are preserved, with 20 COVERED and 52 PLANNED. Later obligations do not count as foundation failures or completed features.

The initial 53-path manifest above is retained as historical inventory. Eight additional paths make the current total 61 (34 modified tracked, 27 untracked, zero staged). Previously listed files changed again in this closure: RuleCapabilityValidator, ActiveRuleReferenceGuard, RuleMatcher, RuleMatchPersister, RuleMatchEntity, RuleController, RuleLifecycleController, RuleCommandAuthorizationIntegrationTest, RuleFoundationIntegrationTest, CreateRuleModal.test.tsx, RulesPage.test.tsx and the three Phase 3 documents. Thus this closure touches 22 files; the remaining original foundation changes were preserved.

| Additional exact path | Classification / purpose | Expected |
|---|---|---|
| [backend/src/main/java/com/nexus/action/application/executor/CommandExecutor.java](../../backend/src/main/java/com/nexus/action/application/executor/CommandExecutor.java) | Dispatch authorization / shared existing RELAY contract check | Yes |
| [backend/src/main/java/com/nexus/action/application/executor/CommandValueValidator.java](../../backend/src/main/java/com/nexus/action/application/executor/CommandValueValidator.java) | Validation / configured RELAY subtarget check shared with activation | Yes |
| [backend/src/main/java/com/nexus/rule/application/RuleEvaluator.java](../../backend/src/main/java/com/nexus/rule/application/RuleEvaluator.java) | Evaluation / canonical binding UUID, exact units and source DEVICE_ID | Yes |
| [backend/src/test/java/com/nexus/rule/application/RuleCapabilityValidatorTest.java](../../backend/src/test/java/com/nexus/rule/application/RuleCapabilityValidatorTest.java) | Tests / focused unit/alias diagnostic semantics | Yes |
| [backend/src/test/java/com/nexus/rule/persistence/RuleRegistryProjectionTest.java](../../backend/src/test/java/com/nexus/rule/persistence/RuleRegistryProjectionTest.java) | Tests / mocked projection ordering/tombstones/eviction/read failure | Yes |
| [backend/src/test/java/com/nexus/rule/persistence/RuleCooldownRestartIntegrationTest.java](../../backend/src/test/java/com/nexus/rule/persistence/RuleCooldownRestartIntegrationTest.java) | Tests / real PostgreSQL controlled-Clock cooldown/context restart | Yes |
| [backend/src/test/java/com/nexus/rule/persistence/RuleMigratedReconciliationIntegrationTest.java](../../backend/src/test/java/com/nexus/rule/persistence/RuleMigratedReconciliationIntegrationTest.java) | Tests / actual populated V24 migration/service reconciliation/context/constraints | Yes |
| [frontend/src/components/rules/RuleCanvas.test.tsx](../../frontend/src/components/rules/RuleCanvas.test.tsx) | Tests / disabled save cannot fabricate success | Yes |

Final full/fast results and protected-state review follow; no staging/commit/tag/push or next slice is authorized.

## Historical closure validation (2026-10-13; 899-test checkpoint)

| Check | Final verified result |
|---|---|
| Complete backend suite | 899 tests, 0 failures, 0 errors, 0 skips; Surefire XML aggregate and captured Maven BUILD SUCCESS agree. |
| Complete frontend suite | 452 passed / 55 files; 45 focused lifecycle/canvas cases also passed. |
| Simulator | 56 passed, 3 explicit live-stack skips. |
| Lint/build | 0 errors / 12 existing warnings; production build PASS. |
| validate_nexus.py --full | PASS on final code/test revision, including all wrapper smokes. |
| validate_nexus.py --fast | PASS after final full run on the same code/test revision. |
| RuleCapabilityValidatorTest | 1 passing unit method with same/omitted/mismatched units and ambiguous identity cases; not counted as database proof. |
| RuleCommandAuthorizationIntegrationTest | 32 passes; one method checks 28 invalid activation cases. Actual pipeline/epoch/stub races, reverse waits, tokenless CAS, uppercase UUID cross-device publication and committed cooldown handoff. |
| RuleFoundationIntegrationTest | 20 passes: MockMvc legacy/modern semantics, exact version/time validation, failed ownership/cache invariants, history/explanation and transaction projection. |
| RuleV25MigrationTest | 2 passes: populated migration and complete rollback/retry without repair. V25 SQL unchanged during closure. |
| RuleMigratedReconciliationIntegrationTest | 6 passes: 13 actual V24 fixtures migrated/reconciled, exact old JSON/NULL fields, restart/intervention/fault/idempotency, PostgreSQL immutable/owner constraints and imported global cooldown. |
| RuleCooldownRestartIntegrationTest | 2 passes: controlled Clock, real persisted eligible history, full lifecycle/context recreation and exact boundary. |
| RuleRegistryProjectionTest | 2 unit passes: callback ordering/tombstone/eviction/read-failure behavior; actual commit/rollback independently tested in foundation integration. |
| RuleFoundationRestartIntegrationTest | 2 passes: fresh Spring contexts, same persistent disposable PostgreSQL; not OS/multiprocess proof. |
| Existing persistence/AI/P2.4 | Registry persistence 24, explanation integration 5, AI mapper 5, generic real-Mosquitto command/ACK/GOOD echo/convergence 1, command MQTT 11 and verification restart 3 passes. |
| Diff/document checks | 72 unique ledger IDs; 20 COVERED / 52 PLANNED; exact Java references resolve. Whitespace/protected state and source hashes checked. |

Final artifacts: [.nexus/validation/p31-closure-full-final.json](../../.nexus/validation/p31-closure-full-final.json), [full text](../../.nexus/validation/p31-closure-full-final.txt), [fast JSON](../../.nexus/validation/p31-closure-fast-final.json), [fast text](../../.nexus/validation/p31-closure-fast-final.txt), backend/target/surefire-reports and focused/red logs listed above. Generated artifacts remain ignored/excluded. Earlier full passes before final assertion/refinement changes are diagnostic history, not final evidence.

One early full capture hit the existing mixed-encoding Python reader-thread error. Final reruns used command-scoped PYTHONUTF8/JAVA_TOOL_OPTIONS UTF-8 stream settings without modifying tooling or the user/global environment; final captured Maven/JSON and independently aggregated XML agree. Existing Java native-access, closed-container/reconnect and Surefire forced fork shutdown warnings remain disclosed; they are not hidden or counted as additional evidence.

Code/test hashes were unchanged between those full and fast runs; only that documentation closeout followed. HEAD/main was unchanged, with 34 tracked modifications + 27 untracked / 61 intended paths and zero staged. Protected paths and V1-V24 were unchanged; no V26, development datasource migration or commit/tag/push occurred. The previous claim that no HIGH foundation defect or mandatory proof remained open was disproved by the subsequent forensic review (U1/U2/U3). The 899-test result is historical regression evidence, not proof of legacy unit/identity safety or persisted concurrent winners. Corrected evidence is recorded below. Deferred slices and C13 retirement remain pending; no physical ESP evidence is claimed.

## Targeted forensic defect correction (2026-10-13; historical failed validation attempt, uncommitted)

Entry matched main / 33ad13271b95122e8dffdf168e2d19ec3a2f17a3, 34 modified tracked + 27 untracked / 61 intended paths, empty index. Only U1, U2 and U3 were authorized. No lifecycle/schema/API/Twin-registry redesign was needed. ROADMAP, CODEX_HANDOFF, protected hardware/results, V1-V25, dispatch gates and later runtime slices are untouched.

| Finding | Narrow correction / exact evidence |
|---|---|
| U1: legacy evaluator compared GOOD values without installed-unit compatibility | RuleEvaluator checks authoritative effective/condition/reported units before comparison. RuleCapabilityValidator emits indexed UNIT_MISMATCH for legacy declarations too. RuleLegacyCapabilityEvaluatorTest explicit/omitted, missing/unknown/stale, unbound and explicitly-unitless cases; RuleCommandAuthorizationIntegrationTest.legacyExplicitAndOmittedUnitMismatchCannotPersistMatchOrAcceptAction, legacyConditionUnitMismatchHasDurableIndexedActivationFailure, legacyMissingUnknownStaleAndUnexpectedContractUnitsCannotCreateEffects and legacyUnboundInstalledCapabilityUsesCanonicalUnitSafeState. |
| U2: legacy lookup selected a first matching current/historical wire name | Resolve installed identity, use existing getByStateKey, verify canonical provenance and retain actual identity in match evidence. RuleCommandAuthorizationIntegrationTest.legacyCanonicalBindingIgnoresRetainedAliasAndMatchesEquivalentGeneric, legacyCanonicalEvaluationIgnoresOtherDeviceAndInactiveWorkingDraft and legacyAmbiguousAndDisabledInstallationSuppressesActivationAndRuntime; canonical unit/Boolean unit regressions. |
| U3: winner counts did not establish the persisted edit | Strengthened RuleFoundationIntegrationTest.overlappingTokenedEditsHaveOneWinner and RuleCommandAuthorizationIntegrationTest.overlappingTokenlessMutationsObserveOneRevisionAndCannotLoseAnEdit. Returned winner/working UUID, complete content/order, [1,2] human sequence, one rowRevision increment, loser absence, prior V1 retention and immutable UPDATE rejection are now asserted after observed PostgreSQL contention. Draft-only epoch is unchanged; legacy disabled composite increments epoch for its real pause. |

Red reproduction: 15 newly added unit/PostgreSQL methods failed against the previous production implementation, 0 errors/skips (backend/target/p31-forensic-legacy-red.log). Gate 1 initially passed 155 focused tests; an additional explicitly-unitless versus unresolved-unit-choice case brought the final Gate 1 result to 156, zero failures/errors/skips (p31-forensic-gate1-final.log). Gate 2's two strengthened race methods passed (p31-forensic-gate2.log). Final focused migration/reconciliation/lifecycle/authorization/identity/units/context/Mosquitto/P2.4 validation passed 100, zero failures/errors/skips (p31-forensic-focused-final.log).

Seven new authorization methods use actual PostgreSQL Twin persistence and existing Matcher/Evaluator/Planner/Engine plus a disposable Mosquitto broker. They assert persisted source/rule/device attribution, no new match/acceptance/desired/history/verification/publication for unsafe evidence, legitimate positive publication and actual binding evidence. Retained unrelated alias rows are compared unchanged, not deleted. The new evaluator class has nine deterministic methods, not nine PostgreSQL tests. Context recreation is not an OS restart; same-JVM workers are not multi-process proof.

Historical dimensional telemetry with missing unit is suppressed rather than labeled with a current default. Genuine explicitly-unitless installed contracts retain NULL units; unresolved dimensional unit choices do not count as unitless. Compatible reported/condition/installed units preserve legacy comparisons and typed Boolean compatibility. Payload-only legacy predicates remain unchanged. No implicit conversion, history rewrite or general Twin lookup change was introduced.

Current intended set is 62 paths: the preserved prior 61 plus backend/src/test/java/com/nexus/rule/application/RuleLegacyCapabilityEvaluatorTest.java. This correction changes eight files: two production files, three test files (including that new class), and only the three Phase 3 documents. All other original foundation paths are preserved byte-for-byte. Generated reports remain ignored.

Gate 3 FAILED. The already-running full validator completed its configured smoke checks; no new test invocation, frontend fix/retry or fast validation was started after the failure was identified. The exact blocker is OverviewTab.test.tsx:81, "shows Unavailable for Latency/Twin Age/Uptime/Firmware/Last Reboot instead of fabricated values", which timed out at the configured 5000 ms. That test and component are unchanged; the timeout's cause was not diagnosed or presumed benign. Targeted closure remains INCOMPLETE pending separate direction.

| Final attempted validation | Actual result |
|---|---|
| Complete backend suite | 915 tests, 0 failures, 0 errors, 0 skipped; captured Maven BUILD SUCCESS and independently aggregated Surefire XML agree. |
| Complete frontend suite | FAIL: 451 passed, 1 timed out / 55 files (54 passed, 1 failed). This is not 452 passes. |
| Frontend lint | PASS: 0 errors, 12 existing warnings. |
| Frontend production build | PASS; existing bundle-size/tooling warnings retained. |
| Simulator | 56 passed, 3 explicit live-stack skips. |
| validate_nexus.py --full | FAIL: frontend unit tests; all other configured checks passed. |
| validate_nexus.py --fast | NOT RUN after Gate 3 failure, per stop requirement. Historical fast passes do not establish this correction's acceptance. |
| Migration/reconciliation/lifecycle/authorization | V25 2, actual migrated reconciliation 6, foundation 20, authorization 39, legacy evaluator unit 9 passes; no migration script changed. |
| Fresh-context / P2.4 regression | Foundation context 2, cooldown context 2, verification context 3, real-Mosquitto generic loop 1 and command MQTT 11 passes. Wrapper loop/MQTT/persistence smokes also passed. Context recreation is not an OS-process restart. |

Final attempted reports (ignored): [.nexus/validation/p31-forensic-full-final.json](../../.nexus/validation/p31-forensic-full-final.json), [text](../../.nexus/validation/p31-forensic-full-final.txt), [console](../../.nexus/validation/p31-forensic-full-console-final.log), backend/target/surefire-reports and focused/red logs above. UTF-8 settings were command-scoped; existing closed-container/reconnect/native-access/Surefire shutdown warnings are disclosed, not counted as test passes. All 59 non-documentation source/test/migration hashes stayed unchanged throughout full validation; only this truthful documentation closeout followed. No development DB query/migration, staging, commit, tag, push or next slice occurred.

## Prior 62-path manifest (forensic correction checkpoint)

Paths are relative to D:/nexus. This supersedes the historical 53/61-path totals, not their preserved source changes. There are 34 modified tracked and 28 untracked paths, zero staged. All paths are classified; no generated or unrelated file belongs to the intended set. This turn changed only RuleEvaluator, RuleCapabilityValidator, the two existing integration classes, the new legacy evaluator class and the three Phase 3 documents; the other 54 paths remain byte-for-byte preserved.

| Exact path | Git state | Primary classification |
|---|---|---|
| `architecture/phase3/p3-acceptance-ledger.md` | Untracked | Documentation |
| `architecture/phase3/p3-implementation-ticket.md` | Untracked | Documentation |
| `architecture/phase3/rule-engine-contract.md` | Untracked | Documentation |
| `backend/src/main/java/com/nexus/action/application/ActionExecutionEngine.java` | Modified | Dispatch authorization |
| `backend/src/main/java/com/nexus/action/application/CommandAcceptanceService.java` | Modified | Dispatch authorization |
| `backend/src/main/java/com/nexus/action/application/executor/CommandExecutor.java` | Modified | Dispatch authorization |
| `backend/src/main/java/com/nexus/action/application/executor/CommandValueValidator.java` | Modified | Central validation |
| `backend/src/main/java/com/nexus/common/exception/GlobalExceptionHandler.java` | Modified | API compatibility/history/AI minimization |
| `backend/src/main/java/com/nexus/device/application/DeviceService.java` | Modified | Device contract protection |
| `backend/src/main/java/com/nexus/device/binding/BindingManagementService.java` | Modified | Device contract protection |
| `backend/src/main/java/com/nexus/device/capability/DeviceCapabilityService.java` | Modified | Device contract protection |
| `backend/src/main/java/com/nexus/device/capability/definition/CapabilityDefinitionService.java` | Modified | Device contract protection |
| `backend/src/main/java/com/nexus/event/persistence/DomainEventPersister.java` | Modified | Event/match persistence safety |
| `backend/src/main/java/com/nexus/rule/ai/AiExplanationContextMapper.java` | Modified | API compatibility/history/AI minimization |
| `backend/src/main/java/com/nexus/rule/api/RuleController.java` | Modified | API compatibility/history/AI minimization |
| `backend/src/main/java/com/nexus/rule/api/RuleLifecycleController.java` | Untracked | API compatibility/history/AI minimization |
| `backend/src/main/java/com/nexus/rule/api/dto/RuleMatchDetailResponse.java` | Modified | API compatibility/history/AI minimization |
| `backend/src/main/java/com/nexus/rule/api/dto/RuleRequest.java` | Modified | API compatibility/history/AI minimization |
| `backend/src/main/java/com/nexus/rule/api/dto/RuleResponse.java` | Modified | API compatibility/history/AI minimization |
| `backend/src/main/java/com/nexus/rule/application/ActiveRuleReferenceGuard.java` | Untracked | Device contract protection |
| `backend/src/main/java/com/nexus/rule/application/RuleCapabilityValidator.java` | Modified | Central validation |
| `backend/src/main/java/com/nexus/rule/application/RuleDefinitionValidationException.java` | Untracked | Central validation |
| `backend/src/main/java/com/nexus/rule/application/RuleEvaluator.java` | Modified | Pinned/canonical evaluation |
| `backend/src/main/java/com/nexus/rule/application/RuleExecutionAuthorization.java` | Untracked | Dispatch authorization |
| `backend/src/main/java/com/nexus/rule/application/RuleExecutionSuppressedException.java` | Untracked | Dispatch authorization |
| `backend/src/main/java/com/nexus/rule/application/RuleImportReconciler.java` | Untracked | Lifecycle/reconciliation |
| `backend/src/main/java/com/nexus/rule/application/RuleLifecycleService.java` | Untracked | Lifecycle/reconciliation |
| `backend/src/main/java/com/nexus/rule/application/RuleMatcher.java` | Modified | Pinned/canonical evaluation |
| `backend/src/main/java/com/nexus/rule/application/RuleMutationException.java` | Untracked | Lifecycle/reconciliation |
| `backend/src/main/java/com/nexus/rule/application/RuleNonCommandExecutionService.java` | Untracked | Dispatch authorization |
| `backend/src/main/java/com/nexus/rule/domain/ImmutableRuleValue.java` | Untracked | Rule version/domain persistence |
| `backend/src/main/java/com/nexus/rule/domain/Rule.java` | Modified | Rule version/domain persistence |
| `backend/src/main/java/com/nexus/rule/domain/RuleAction.java` | Modified | Rule version/domain persistence |
| `backend/src/main/java/com/nexus/rule/domain/RuleExecutionIdentity.java` | Untracked | Rule version/domain persistence |
| `backend/src/main/java/com/nexus/rule/domain/RuleProjectionChanged.java` | Untracked | Lifecycle/reconciliation |
| `backend/src/main/java/com/nexus/rule/persistence/RuleMatchEntity.java` | Modified | Event/match persistence safety |
| `backend/src/main/java/com/nexus/rule/persistence/RuleMatchPersister.java` | Modified | Event/match persistence safety |
| `backend/src/main/java/com/nexus/rule/persistence/RuleMatchRepository.java` | Modified | Event/match persistence safety |
| `backend/src/main/java/com/nexus/rule/persistence/RuleRegistry.java` | Modified | Rule version/domain persistence |
| `backend/src/main/java/com/nexus/rule/persistence/RuleVersionStore.java` | Untracked | Rule version/domain persistence |
| `backend/src/main/resources/db/migration/V25__rule_versions_and_lifecycle.sql` | Untracked | V25 migration |
| `backend/src/test/java/com/nexus/rule/ai/AiExplanationContextMapperTest.java` | Modified | Backend regression tests |
| `backend/src/test/java/com/nexus/rule/application/RuleCapabilityValidatorTest.java` | Untracked | Backend regression tests |
| `backend/src/test/java/com/nexus/rule/application/RuleCommandAuthorizationIntegrationTest.java` | Untracked | Backend regression tests |
| `backend/src/test/java/com/nexus/rule/application/RuleFoundationIntegrationTest.java` | Untracked | Backend regression tests |
| `backend/src/test/java/com/nexus/rule/application/RuleLegacyCapabilityEvaluatorTest.java` | Untracked | Backend regression tests |
| `backend/src/test/java/com/nexus/rule/application/RuleMatcherTest.java` | Modified | Backend regression tests |
| `backend/src/test/java/com/nexus/rule/domain/RuleDefinitionImmutabilityTest.java` | Untracked | Backend regression tests |
| `backend/src/test/java/com/nexus/rule/persistence/RuleCooldownRestartIntegrationTest.java` | Untracked | Backend regression tests |
| `backend/src/test/java/com/nexus/rule/persistence/RuleFoundationRestartIntegrationTest.java` | Untracked | Backend regression tests |
| `backend/src/test/java/com/nexus/rule/persistence/RuleMigratedReconciliationIntegrationTest.java` | Untracked | Backend regression tests |
| `backend/src/test/java/com/nexus/rule/persistence/RuleRegistryPersistenceIntegrationTest.java` | Modified | Backend regression tests |
| `backend/src/test/java/com/nexus/rule/persistence/RuleRegistryProjectionTest.java` | Untracked | Backend regression tests |
| `backend/src/test/java/com/nexus/rule/persistence/RuleV25MigrationTest.java` | Untracked | Backend regression tests |
| `frontend/src/api/ruleApi.ts` | Modified | Frontend/API lifecycle |
| `frontend/src/components/explorer/device/CreateRuleModal.test.tsx` | Modified | Frontend regression tests |
| `frontend/src/components/explorer/device/CreateRuleModal.tsx` | Modified | Frontend/API lifecycle |
| `frontend/src/components/explorer/device/tabs/RulesTab.tsx` | Modified | Frontend/API lifecycle |
| `frontend/src/components/rules/RuleCanvas.test.tsx` | Untracked | Frontend regression tests |
| `frontend/src/components/rules/RuleCanvas.tsx` | Modified | Frontend/API lifecycle |
| `frontend/src/pages/RulesPage.test.tsx` | Modified | Frontend regression tests |
| `frontend/src/pages/RulesPage.tsx` | Modified | Frontend/API lifecycle |

## Controlled frontend diagnosis and validation recovery (2026-10-13; uncommitted)

Entry matched main / 33ad13271b95122e8dffdf168e2d19ec3a2f17a3, 34 modified tracked + 28 untracked / 62 intended paths, empty index. Original failed artifacts above remain preserved: OverviewTab.test.tsx:81, "shows Unavailable for Latency/Twin Age/Uptime/Firmware/Last Reboot instead of fabricated values", exceeded the unchanged 5000-ms callback budget (reported duration 7766 ms). Original output does not establish whether import, render or state settlement completed, or the exact callback phase responsible.

Read-only diagnosis found that the callback explicitly awaits only its dynamic component import; assertions are synchronous and REST mocks are configured to resolve. This file/setup do not enable fake timers; RTL normally registers cleanup with globals enabled. The archived run does not establish actual state settlement or cleanup completion. Module reset imports a default-off DebugOverlay through DeviceWorkspace and all sibling tabs, and unmocked ConnectionManager starts real backend fetches and an undisposed interval. Async diagnostics confirmed monitoring resources; these are concrete fixture-isolation defects, not proof of a production rendering defect or of one specific timing cause.

Classification is exactly **E. INTERMITTENT, CAUSE NOT ESTABLISHED**. The normal full frontend suite reproduced the timeout before correction (451 passed / 1 timeout / 55 files); a serial CLI diagnostic passed (452 / 55). That is consistent with sensitivity to concurrent execution but does not distinguish cross-test contamination from resource pressure. No root-cause attribution to C/D, individual mock, import/render phase or machine load is claimed.

The narrow authorized correction is confined to frontend/src/components/explorer/device/tabs/OverviewTab.test.tsx: stub only the connection singleton's initial disconnected status, isolate the already-null/default-off debug overlay, and unmount/clear this file's owned QueryClients. Real OverviewTab/usePolling/useDeviceTwinState/React Query, all nine original bodies/assertions, live/demo branches and retry:false remain intact. No production, test timeout, retry, worker configuration, .skip/.only, sleep or assertion relaxation was introduced.

| Controlled run | Result | Vitest run duration seconds | Target reported duration ms |
|---|---|---|---|
| Original exact-test repetitions 1-5 | 5/5 passed, no timeouts; 8 other cases filtered per invocation | 6.46, 5.19, 4.62, 4.87, 4.66 | 2832.2, 2558.5, 2369.0, 2368.7, 2269.1 |
| Original full-file repetitions 1-3 | 9/9 each; 0 failures/skips | 4.76, 5.44, 4.94 | 1962.8, 2407.4, 2088.4 |
| Original normal full suite | 451 passed / 1 timeout / 55 files | 24.65 | 5612.5 |
| Serial full diagnostic only | 452 passed / 55 files; 0 failures/skips | 68.51 | 860.4 |
| Corrected exact-test repetitions 1-5 | 5/5 passed, no timeouts; 8 other cases filtered per invocation | 1.17, 1.17, 1.17, 1.19, 1.15 | 211.5, 205.1, 202.6, 198.0, 196.2 |
| Corrected full-file repetitions 1-3 | 9/9 each; 0 failures/skips | 1.46, 1.42, 1.45 | 243.6, 199.8, 200.4 |
| Corrected normal full suites 1-2 | 452 passed / 55 files each; 0 failures/skips | 12.72, 12.45 | 409.6, 411.7 |

Reported test durations include hooks/cleanup and are not isolated import/render measurements. Normal full suites used unchanged parallelism/isolation/default timeout with reporting flags only. Serial execution and async-leak collection were separate diagnostic CLI invocations, not repository configuration changes. Post-correction async diagnostics no longer report the ConnectionManager timer/fetch path but still identify two React Query PROMISE signals; this is not a zero-leaks or eliminated-intermittency claim.

Final fresh validation: validate_nexus.py --fast PASS (2026-10-13T10:56:11Z-10:56:49Z); --full PASS (10:59:18Z-11:05:54Z). Complete backend 915, zero failures/errors/skips; frontend 452 / 55 files, zero failures/skips; simulator 56 passed / 3 explicit live-stack skips; lint 0 errors / 12 existing warnings; production build PASS. Full validator's configured loop/MQTT/persistence smokes passed. Fresh Surefire XML verifies V25 2, migrated reconciliation 6, foundation 20, authorization 39, legacy evaluator 9, V24 1, verification 33, verification context restart 3, generic real-Mosquitto loop 1 and command MQTT 11 passes. These checks are included in the unrestricted complete backend gate; no development services or data were deployed/migrated. Software/context evidence is not physical or OS-process proof.

Artifacts remain ignored: [repetition summary](../../.nexus/validation/p31-timeout-reproduction-summary.json), p31-timeout-single/file/suite-*.json/log (including the new reproduced failure), p31-timeout-corrected-*.json/log, async diagnostic logs, [fast JSON](../../.nexus/validation/p31-timeout-recovery-fast.json), [full JSON](../../.nexus/validation/p31-timeout-recovery-full.json) and their console/text files. The prior p31-forensic-full failure record was not overwritten. Command-scoped UTF-8 capture settings do not change test semantics; existing shutdown/reconnect/native-access/build warnings remain disclosed.

Current set is 63 intended paths: the prior 62 above plus **frontend/src/components/explorer/device/tabs/OverviewTab.test.tsx** (Modified; frontend test isolation/cleanup). This recovery changed only that test and current evidence/status in this ticket and the ledger. Normative contract, all U1/U2/U3 implementation/tests, V1-V25, validation/configuration/scripts, other frontend files, ROADMAP/handoff, firmware/hardware/results/Review III are preserved. All 60 non-documentation source/test/migration hashes stayed frozen through fast/full gates; only documentation closeout followed. HEAD/main unchanged, 35 modified tracked + 28 untracked, zero staged, no commit/tag/push, V26 or next slice. The unchanged contract checkpoint records the earlier failed attempt; this section and the ledger provide current validation evidence without rewriting normative semantics. Outcome is READY FOR TARGETED FORENSIC RE-REVIEW, not approval to commit or proof every intermittent timing risk is gone.

## R1 generic dimensional-unit provenance correction (2026-10-13; uncommitted)

Current outcome: **P3.4 + P3.1 R1 CORRECTED — READY FOR FINAL TARGETED REVIEW**. This supersedes the previous 63-path readiness checkpoint, not its preserved failed/recovery evidence. It is neither forensic approval nor permission to stage/commit. No later slice is implemented.

### Historical rejection and red reproduction

The subsequent targeted review returned **P3.4 + P3.1 TARGETED FORENSIC RE-REVIEW NOT APPROVED**: R1 was identified after accepted U1 legacy-unit safety, U2 canonical selection and U3 persisted-winner corrections. Generic evaluation used `Objects.equals(NULL, NULL)` without the installed dimensional-unit establishment check already present in the legitimate legacy path.

Before changing production, the real unit evaluator and disposable PostgreSQL pipeline each reproduced the same unsafe MATCH: NUMBER READ definition allows CELSIUS/FAHRENHEIT, default/effective/reported/condition units NULL, fresh ONLINE GOOD value 40, GT 35. The two regressions failed their NO MATCH assertions: **2 tests, 2 assertion failures, 0 errors/skips**; no compilation/infrastructure failure. Source SHA-256 matched the saved pre-correction evaluator. Evidence: `backend/target/p31-r1-red.log`, [.nexus/validation/p31-r1-red-unit.xml](../../.nexus/validation/p31-r1-red-unit.xml), [pipeline red XML](../../.nexus/validation/p31-r1-red-pipeline.xml) and `p31-r1-evaluator-before.java`. This expected red run is defect reproduction, not an unexplained failed final gate.

### Narrow production correction and contract authority

`RuleEvaluator.hasCompatibleUnits` extracts the accepted legacy eligibility check and is now shared by legitimate generic and legacy capability comparisons. Effective unit remains binding override, otherwise definition/default resolution. A missing effective unit is eligible only when authoritative installed metadata has no effective/default unit and no allowed dimensional-unit choices. Definitions allowing concrete CELSIUS/FAHRENHEIT with an unresolved unit are dimensional-unknown, not unitless. Reported unit and any explicit condition unit must agree with the established effective unit; an omitted condition unit does not waive that check. GOOD quality cannot establish unit identity.

The existing approved model/contract already distinguishes these configurations; no schema/contract reinterpretation is needed. No conversion, unit inference/backfill, reported-state/history rewrite or general Twin registry lookup change was introduced. Mandatory Spring installed-reference injection remains; existing manually constructed evaluator fixtures retain their prior no-reference compatibility path. Legacy comparison semantics, numeric/Boolean handling, payload predicates, generic typed comparators, canonical binding selection, P2.3 quality and P2.4 acceptance/publication/ACK/convergence boundaries remain unchanged. R1 changes generic runtime eligibility, not its comparator semantics. The normative contract is preserved, including its historical failed-Gate-3 checkpoint; this section and the ledger supply current evidence.

### Exact R1 negative and positive regression evidence

Unless fully qualified otherwise, unit methods below are in `com.nexus.rule.application.RuleGenericUnitSafetyEvaluatorTest`; all 18 methods passed. These are real resolver/installed-reference validator/value validator/quality/evaluator semantics with repository mocks, not PostgreSQL tests.

| Case | Exact method / evidence | Actual result |
|---|---|---|
| A: original dimensional NULL | `originalR1DimensionalNullUnitsCannotMatchGoodObservation` | Accepted installed contract, actual GOOD, NO MATCH; raw 40/NULL preserved. |
| B: missing reported Celsius identity | `establishedCelsiusRejectsMissingReportedUnitEvenWithGoodQuality` | NO MATCH with explicit or omitted condition unit despite GOOD. |
| C: explicit condition mismatch | `explicitGenericConditionMismatchHasIndexedUnitMismatchDiagnostic`; `com.nexus.rule.application.RuleCommandAuthorizationIntegrationTest.effectiveUnitMismatchCannotActivateOrReachAnySideEffect` | Indexed conditions[1] UNIT_MISMATCH; real draft activation rejection and durable failed validation, prior owner/epoch retained, no effects. |
| D: reported Fahrenheit versus installed Celsius | `reportedFahrenheitCannotSatisfyExplicitOrOmittedCelsiusCondition` | Explicit/omitted condition units reject; no implicit conversion. |
| E: unresolved dimensional choice | `unresolvedDimensionalContractCannotChooseAReportedConcreteUnit` | Neither a reported Celsius nor Fahrenheit value chooses an unknown installed effective unit. |
| F: same-binding syntax parity | `equivalentLegacyAndGenericMissingDimensionalProvenanceBothFailClosed` | Same binding/definition and GOOD observation; both NO MATCH. |
| G: compatible Celsius | `compatibleCelsiusMatchesWithExplicitAndOmittedConditionUnitsAndLegacyParity` | 40 Celsius matches both legitimate syntaxes. |
| H: below threshold | `compatibleCelsiusBelowThresholdDoesNotMatchEitherSyntax` | 30 Celsius is NO MATCH in both syntaxes. |
| I: genuine unitless NUMBER | `authoritativeUnitlessNumberPreservesNumericComparisonAndLegacyParity` | 40 matches / 30 does not, with NULL units preserved. |
| J: genuine unitless BOOLEAN | `authoritativeUnitlessBooleanPreservesTypedComparisonAndLegacyParity` | Typed true/false comparisons preserved in both syntaxes. |
| K: STRING / ENUM | `authoritativeUnitlessStringPreservesEqualityAndInequality`; `authoritativeUnitlessEnumPreservesApprovedMemberComparison` | Typed equality/inequality/member comparisons preserved; no invented numeric-unit requirement. |
| L: established legacy behavior | Compatible-Celsius and genuine-unitless parity methods above; unchanged `RuleLegacyCapabilityEvaluatorTest` 9/9 | Valid legacy comparisons retained. |

Additional passing methods prove definition-default fallback, concrete binding override, stale/offline rejection, wrong-binding selection rejection without deleting retained observations, and persisted-value current-contract revalidation. No replay/history mutation or direction expansion was introduced.

### Disposable PostgreSQL and actual pipeline evidence

Four additive methods in `com.nexus.rule.application.RuleCommandAuthorizationIntegrationTest` passed, within its **43/43** suite:

- `genericDimensionalNullUnitsCannotPersistMatchOrAcceptAnySideEffect`: actual evaluator GOOD/NO MATCH and actual matcher persist the truthful attributed source, but no new attributed RuleMatch/Rule-originated ActionHistory acceptance, desired state/history, verification, publication, alert or webhook invocation.
- `genericAndLegacyShareMissingDimensionalProvenanceRejection`: equivalent legitimate legacy/generic syntax resolves the SAME installed binding/definition and both reject; persisted reported row stays unchanged.
- `genericMissingOrMismatchedReportedUnitsCannotReachAnySideEffect`: missing/Fahrenheit observations versus installed Celsius fail for explicit/omitted condition units with the same source/rule/device-attributed no-effect and unchanged-row assertions.
- `genuineUnitlessAndCompatibleNumericConditionsPersistAndPublishRealMqttCommands`: two independent unitless/Celsius fixtures each reject 30 and accept 40, persist attributed match/acceptance/desired/verification/history, and a real Mosquitto subscriber receives QoS 1 non-retained commands whose command IDs equal the persisted IDs.

Existing seed history is retained, not mistaken for a new match. Non-command actions are actually configured in negative definitions, and executor/alert/webhook invocation checks supplement database assertions. Positive publication is not ACK or convergence proof; separate existing P2.4 regressions preserve those facts. Services are disposable Testcontainers, not development PostgreSQL or hardware.

### Fresh gates and preserved accepted work

Gate 1: **203 tests, 0 failures/errors/skips**, normal Maven selection including all R1 methods, central validator, generic/legacy/payload evaluators, existing U1/U2 PostgreSQL cases, U3 foundation races, P2.3 quality, V25/migrated reconciliation and P2.4 MQTT/readback/restart checks. Evidence: `backend/target/p31-r1-gate1.log` and [.nexus/validation/p31-r1-gate1-reports](../../.nexus/validation/p31-r1-gate1-reports).

Gate 2: scenario 5 retains COVERED only after generic and legacy missing dimensional provenance is proven by the new red/green evidence. Scenario 66 remains PLANNED as a full scenario: later arbitration routing/exception retirement is still Slice 6. Exactly **72 unique IDs, 20 COVERED / 52 PLANNED**; no temporal, frozen-context, arbitration, dry-run or replay completion claim.

Gate 3: unrestricted normal fast PASS (2026-10-13T15:53:39.770386Z–2026-10-13T15:54:33.421714Z, 8/8 checks), then full PASS (2026-10-13T15:54:49.881597Z–2026-10-13T16:02:49.355670Z, 11/11 checks). No retries, timeout/assertion/isolation/runner changes or restricted test filters were used in the full backend/frontend gates.

| Fresh validation | Actual result |
|---|---|
| Complete backend suite | **937 passed, 0 failures, 0 errors, 0 skipped**; Maven BUILD SUCCESS and 110 fresh Surefire XMLs agree. |
| Complete frontend suite | **452 passed / 55 files**, 0 failures/skips. |
| Complete simulator suite | **56 passed / 3 skipped**; the three tests require a live backend + MQTT stack, deliberately not started here. |
| Frontend lint / production build | PASS; 0 lint errors, 12 existing warnings; production build PASS. |
| Normal fast / full validators | PASS 8/8 and 11/11; no newFailures or carryovers. |
| Disposable V25 / populated migrated reconciliation | 2/2 and 6/6, fresh green XML; no development migration. |
| P2.4 readback / restart / real MQTT | Verification 33/33, verification context restart 3/3, command MQTT 11/11, generic real-Mosquitto loop 1/1; publication/ACK/convergence remain distinct. |
| Existing foundations | Legacy evaluator 9/9, foundation integration 20/20, authorization 43/43, V24 migration 1/1; U1/U2/U3 not reimplemented. |
| Wrapper loop / MQTT-ACK / persistence smokes | All three PASS. |
| Git whitespace | PASS, including final documentation inspection. |

Complete backend XML was snapshotted before wrapper smokes could overwrite individual class reports: [.nexus/validation/p31-r1-complete-backend-reports](../../.nexus/validation/p31-r1-complete-backend-reports). Current [fast JSON](../../.nexus/validation/p31-r1-fast-final.json), [full JSON](../../.nexus/validation/p31-r1-full-final.json), text reports and `p31-r1-fast/full-console.log` remain ignored. Command-scoped UTF-8 capture does not alter repository runner semantics. Existing native-access, closed-container/reconnect, Surefire shutdown and build warnings remain disclosed; no zero-leaks or physical-proof claim.

Original failed `p31-forensic-full-final.json` SHA-256 remains `28E1BDA448A19FAB3AC19B7ABF98F3483CF1E3A5FB3923CA5FF5DE2147FFEFB5`; original console remains `C72145B8AC00DEA973F5F8B2037E51EA8743E8C5E7989A3FC5026F12DEAD3E90`. Controlled timeout reproduction/recovery artifacts are preserved. Original frontend timeout cause remains **E. INTERMITTENT, CAUSE NOT ESTABLISHED**; current green runs do not establish its cause or eliminate every timing risk.

### Current 64-path worktree and protected state

Entry matched `main` / `33ad13271b95122e8dffdf168e2d19ec3a2f17a3`, **35 modified tracked + 28 untracked = 63 paths**, zero staged, V25 only. Final intended set is **35 modified tracked + 29 untracked = 64 paths**, zero staged. Every tracked semantic diff and every untracked file was directly inspected. The [exact 64-path classified manifest](../../.nexus/validation/p31-r1-worktree-manifest.json) complements the historical 62-path table above plus OverviewTab recovery and the additional R1 unit file.

| Classification | Current paths |
|---|---|
| Backend implementation/API/guards | 37 |
| Backend regression tests | 14 |
| V25 migration | 1 |
| Frontend implementation/API | 5 |
| Frontend regression tests | 4 |
| Phase 3 documents | 3 |
| Total | 64 |

This R1 turn changes only five intended files: evaluator (shared eligibility), existing untracked authorization integration test (four additive methods; all 39 original bodies preserved), NEW `backend/src/test/java/com/nexus/rule/application/RuleGenericUnitSafetyEvaluatorTest.java` (18 methods), ticket and ledger (validated status/evidence). The other **59 of 63 entry paths** remain byte-for-byte unchanged. All **61 non-documentation production/test/migration hashes** stayed frozen through focused/fast/full gates and documentation closeout; see [entry manifest](../../.nexus/validation/p31-r1-entry-source-hashes.json) and [validation freeze](../../.nexus/validation/p31-r1-final-source-hashes.json).

Accepted U1 validator/legacy safety semantics, U2 canonical selection, U3 persisted-winning-content assertions, OverviewTab fixture recovery, normative contract and C13 temporary gates are preserved. HEAD/main/index and the existing tag `v0.8.0-rc1` remain unchanged; no stage/commit/tag/push. V1–V24 are unchanged; V25 remains the only new migration and is not edited by R1. Firmware, hardware-tests, results/Review III, ROADMAP, CODEX_HANDOFF, runtime/validation scripts and other frontend files are unchanged. No normal backend, development DB query/migration/repair/reset/volume recreation, history rewrite or physical ESP claim occurred. Historical development V24 / 9 rules / 6,168 matches is not newly verified.

Additional defects: **NONE** found within this correction. C13 arbitration remains deferred; no V26+, later Phase 3 runtime, ActionCandidate/ActionPlan, outbox, READ_WRITE or firmware expansion. Stop here for explicit final targeted review; readiness is not approval.
