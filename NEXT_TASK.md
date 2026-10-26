# NEXUS next task: review, then close the remaining Phase 3 obligations

Status (2026-10-14): Slices 2-5 are implemented, validated locally and **uncommitted**. The previous Slice 2 planning brief below is historical. Nothing is staged, committed, tagged or pushed.

## Immediate next steps (user decisions)

1. Review the uncommitted change set against `architecture/phase3/p3-acceptance-ledger.md` ("Slices 2-5 implementation evidence") and decide whether to commit it (a commit needs an explicit request).
2. Remaining dependency order: retire the C13 legacy exception by routing legacy DomainEvent actions through the persisted plan (scenario 66) -> durable cross-instance cooldown (55/56) and hysteresis (58) -> mutual-exclusion/safety policy (57) and RELAY integration (53) -> backend dry-run and frontend (59-65, 70) -> explanation of arbitration (67) and approval handling (69).
3. Do not claim physical validation, unlimited scale or a retired C13 from the existing software tests.

---

# Historical: Slice 2 planning brief (2026-10-13)


Status: **READ-ONLY PLANNING FIRST; implementation is not yet authorized.**
Creating this handoff did not authorize P3.2, P3.5, hardware work or another commit.

## Outcome required from the first Claude session

Verify the checkpoint, explain the current state briefly, then propose a staged
implementation plan with exact files, transaction boundaries, tests and risks.
Stop for user approval before any runtime/schema/test implementation.
Do not rerun full suites merely to establish context for this planning task.

## Read only these authoritative sections initially

1. `PROJECT_HANDOFF.md` and Git branch/HEAD/status.
2. `architecture/phase3/p3-implementation-ticket.md`: heading
   **Slice 2: frozen evaluation context**, plus Slice 1's retained boundaries.
3. `architecture/phase3/rule-engine-contract.md`: C03 (scope), C08 (validation),
   C09-C11 (ownership/snapshot/time), C13's foundation exception, C20 (evidence).
4. `architecture/phase3/p3-acceptance-ledger.md`: scenarios 18, 20-24, 59 and 61.

Scenario 18 has version pinning but lacks full frozen state; 20 is unimplemented.
21-24 already have scope/provenance evidence that must survive. 59's snapshot
inputs and 61's typed-presence semantics are relevant, but full dry-run/frontend
obligations remain open; do not claim these whole scenarios closed by this slice.
Historical logs/R1 sections are on-demand, not startup reading requirements.

## Why this task is next

The current evaluator consults live Twin/cache/contract inputs. Immutable Rule
version pinning alone does not make those reads one coherent state snapshot.
The next foundation must evaluate captured typed inputs without mixing concurrent
telemetry, connectivity, policy/contract and version changes across conditions.
This is distinct from adding typed triggers or action arbitration.

## Relevant existing source

Paths are relative to `backend/src/main/java/com/nexus/`:

- `rule/application/RuleMatcher.java` and `RuleEvaluator.java`: live reads and handoff.
- `rule/persistence/RuleVersionStore.java`: eligible immutable version ownership.
- `rule/application/RuleCapabilityValidator.java`: installed contract authority.
- `device/binding/runtime/RuntimeCapabilityResolver.java`: binding/effective units.
- `twin/domain/DigitalTwinRepository.java` and
  `twin/persistence/InMemoryDigitalTwinRegistry.java`: persisted state versus cache.
- `device/connectivity/DeviceConnectivityRepository.java`: persisted connectivity.
- `twin/application/TwinStateQualityEvaluator.java` and `FreshnessPolicy.java`:
  existing quality calculations and current-contract inputs.
- `rule/application/RuleExecutionAuthorization.java`,
  `action/application/CommandAcceptanceService.java` and
  `rule/application/RuleNonCommandExecutionService.java`: retain guarded effects.
- `rule/persistence/RuleMatchPersister.java`: truthful pinned/evaluation evidence.

Trace additional dependencies only as needed. Any proposed context DTO/service
name is a design proposal, not proof that the class already exists.

## Required design decisions in the plan

1. Define the smallest complete capture set: eligible versions, scope, canonical
   reported states/typed values, persisted connectivity, installed contracts,
   quality/freshness policies and other facts actually used by this evaluation.
2. Propose one read-only PostgreSQL REPEATABLE READ snapshot boundary, or prove
   an equivalent strategy. It must be invoked through a real external Spring
   proxy. Self-invocation and READ COMMITTED multi-query reads are insufficient.
3. Deep-copy nested values/metadata/contracts and detach captured input from
   mutable registry/JPA objects. Quality uses one injected evaluation time and
   captured policy inputs, not later independent cache reads.
4. Keep trigger identity/observation separate from current captured reported
   state; preserve event, receipt, evaluation and acquisition times truthfully.
5. Adapt evaluation to the capture while retaining payload predicates, typed
   comparisons, unit eligibility, canonical scope and missing-value behavior.
6. Preserve independent write/acceptance transactions and current-owner/contract
   rechecks. Do not hold decision locks across nested REQUIRES_NEW boundaries.
7. Specify truthful persisted evidence without rewriting historical matches or
   inventing frozen inputs for old rows. Prefer existing schemas; justify any
   migration explicitly before implementation and never edit V1-V25 checksums.
8. List precise staged changes, deterministic tests, failure behavior and rollback
   risks. Identify uncertainty rather than implementing a later slice to hide it.

## Acceptance evidence to plan, not claim implemented

- Disposable PostgreSQL races update Twin/connectivity/contracts/versions between
  attempted reads: one coherent snapshot, never old/new READ COMMITTED mixtures.
- Conditions never reread mutated live values after acquisition. Nested maps,
  objects/lists cannot mutate captured decisions/evidence.
- Canonical same-device/binding identity, explicit/trigger-device scope, missing
  inputs and cross-device action targets remain correct. Preserve false, zero
  and valid empty STRING as present typed values, not truthiness fallbacks.
- R1/U1 unknown/mismatched dimensional units reject despite GOOD; genuine unitless
  types and compatible Celsius values retain existing comparison behavior.
- Captured version/epoch cannot authorize new effects after ownership loss.
  Durable source/match gates remain; already accepted commands retain P2.4.
- Quality/time boundaries and publication/ACK/qualified-readback distinctions stay
  unchanged. No hardware proof or replay reconstruction is inferred.
- Specify focused regression classes first, then normal fast/full gates after
  an approved implementation is stable. Record actual counts and explicit skips.

## Out of scope and first-response format

No new typed trigger dispatch, timers, temporal operators, schedules, chains,
ActionCandidate/ActionPlan, arbitration/coalescing, full cooldown serialization,
backend dry-run/replay, outbox/retry, READ_WRITE, AI-provider redesign or firmware.
Do not query/migrate development PostgreSQL or launch the normal application.
Do not edit source/tests/migrations or stage/commit/tag/push in this first session.

Respond with: (1) verified baseline and expected local changes, (2) completed versus
missing behavior, (3) proposed file/transaction plan, (4) test-to-scenario matrix,
(5) risks/blockers and (6) exact implementation scope awaiting user approval.
