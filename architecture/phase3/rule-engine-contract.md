# Phase 3 Production Rule Engine contract

Date: 2026-10-13.
Status: targeted U1/U2/U3 corrections are implemented and regression-proven, uncommitted; closure is INCOMPLETE because Gate 3 full validation failed on an unchanged frontend test timeout. Fast validation was not run after that failure; work stopped. Later Phase 3 runtime slices remain unimplemented. This remains the normative design authority; the explicit foundation exception below is still active.

Supporting baseline: `33ad13271b95122e8dffdf168e2d19ec3a2f17a3`, branch `main`, committed P2.4, latest migration V24. The P3.0 audit was read-only. Previous validation was 829 backend passes, 440 frontend passes and 56 simulator passes with 3 live-stack skips; those checks were not rerun for P3.0. This is software evidence, not physical ESP verification.

Document ownership:

- This contract owns semantics, invariants, compatibility exceptions and scope boundaries.
- [Implementation ticket](p3-implementation-ticket.md) owns dependency slices, rollout, source ownership and validation gates. It references these clauses rather than redefining them.
- [Acceptance ledger](p3-acceptance-ledger.md) owns scenario IDs, required evidence and acceptance statuses. A planned test is not proof.
- ROADMAP Phase 3 and CODEX_HANDOFF provide supporting context. Current committed source and schemas remain the authority for what exists today.

## Foundation implementation checkpoint (2026-10-12; uncommitted)

V25 implements immutable executable history, separate version workflow, append-only validation attempts, logical lifecycle, active/working ownership, optimistic rowRevision, executionEpoch and import provenance/reconciliation. PostgreSQL protects version UPDATE/DELETE and same-rule ownership. Existing root JSON is a compatibility projection, not executable authority. Old matches retain their snapshots and NULL version/revision/epoch attribution.

Spring runtime delegates mutations to RuleLifecycleService and reads executable definitions from RuleVersionStore. Activation/resume use the extended RuleCapabilityValidator and current device/definition locks; after-commit registry projection is revision-aware and never authorizes execution. Imported valid originally enabled rules can restore only after actual durable reconciliation, with revision/restoration checks against user intervention. Discovery is bounded in PostgreSQL; infrastructure failure leaves pending eligibility closed.

Legacy evaluation now obtains its actual committed DomainEvent through an externally invoked REQUIRES_NEW boundary, reads the persisted payload, rejects reused identities with inconsistent scope/type/correlation, pins immutable version/epoch and commits attributed matches before action handoff. Command acceptance rechecks permission inside its existing device-first transaction before intent/history/verification writes. Stub non-command executors use an equivalent transactional gate. Suppression diagnostics are not MQTT failures. Already accepted commands retain P2.4 publication/ACK/readback semantics.

Definition comparison is conservative structural equality of name, priority, ordered conditions/actions, metadata and cooldown; it excludes enabled, execution identity and mutation tokens. Legacy expected-value strings and typed action values are not coerced; different numeric representations may produce a successor rather than silently rewriting old content. Nested executable values are copied/frozen. Validation attempt presentation uses append order, not clock sorting, while retaining actual injected-clock timestamps.

Minimum UI exposes active/working identities, lifecycle/reconciliation and original enabled provenance, draft creation/save and tokened lifecycle actions. Forms retain their opening revision and entered data on conflict. Canvas mock-save success is disabled, not replaced with graph persistence. Backend APIs remain legacy-compatible with additive fields; modern mutation tokens use 428/409/412/400 as documented in the ticket.

This does NOT establish frozen Twin snapshots, typed trigger routing, temporal/repeat runtime, multi-instance atomic cooldown, arbitration/ActionPlan authorization, chains, schedules, backend dry-run, historical replay or reliable delivery. Full C13 remains the P3.5 exit requirement. See the ticket/ledger for exact evidence and remaining assertions; a successful regression suite is not blanket acceptance of all 72 scenarios. The original implementation's read-only development snapshot was V24; it is historical, not a current verified count/schema. Targeted correction does not query or migrate development data; V25 evidence uses disposable PostgreSQL.

### Historical acceptance-closure checkpoint (2026-10-13; superseded by forensic corrections below)

B1 is corrected: generic conditions validate the exact installed effective unit, not only the definition-wide allowed-unit list. Omitted units use the resolved contract; incompatible reported/condition units fail closed without conversion. B2 is corrected: installed-reference discovery is shared by validation, evaluation and active-reference protection. Evaluation selects actual binding UUID identity, not an arbitrary matching state; canonical dependency identity remains distinct from enabled/direction/installation permission. Case/alias/cross-device references are protected without treating an unrelated uninstalled scope as a dependency. Ambiguity fails closed.

Strengthened tests additionally exposed and corrected controlled-Clock bypass in match/cooldown time, missing configured-RELAY validation at activation, new-draft If-Match status mismatch, missing-device diagnostic location, a typed DEVICE_ID scope override and raw UUID case disagreement. The shared RELAY check preserves existing command preparation semantics. Capability predicates apply to VALUE; DEVICE_ID and active target references compare actual UUID identity without rewriting immutable input/history. Committed match eligibility is handed back to the matcher so a later epoch change cannot erase a legitimate logical cooldown; late ineligible history still cannot extend it. These are bounded C03/C08/C09/C11/C14 compatibility corrections, not a trigger router, temporal engine, cross-instance cooldown subsystem or policy redesign.

Actual evaluator/planner/engine barriers, all-supported-effect persistence failures, populated V24 migration followed by real reconciliation, fresh-context imports/cooldown, both PostgreSQL contract-lock orders, cache callbacks/rollback, legacy/modern API and frontend lifecycle cases now supply foundation evidence. Exact method references and full-scenario versus subcase boundaries are in the ledger. Final validation passed 899 backend, 452 frontend and 56 simulator tests with 3 explicit skips. This supports a forensic-review recommendation, not full Phase 3 completion or a commit.

### Targeted forensic corrections (2026-10-13; uncommitted)

The subsequent read-only forensic review rejected that checkpoint: generic B1/B2 tests did not establish legacy capabilityType safety (U1/U2), and concurrent-edit winner counts did not prove persisted winning content (U3). Those findings are historical defects, not blanket approval of the 899-test checkpoint.

Legacy capabilityType evaluation now resolves the same current installed reference as validation/active protection and selects only its canonical reported state: binding UUID where bound, otherwise the existing canonical installed-legacy state key. Device, binding and definition provenance are checked; stale wire aliases cannot select another binding. The general Twin registry's historical lookup semantics are unchanged. New legacy match evidence retains the actual binding/definition/key/unit rather than omitting known attribution.

Before a legacy comparison, the observation unit must equal the installed effective unit, and any explicit condition unit must agree. Omitted condition units do not waive this check. Definition-wide allowed units do not authorize raw cross-unit comparisons. Missing units on dimensional historical telemetry fail closed; no inferred backfill or conversion is introduced. NULL-unit comparison is allowed only when the current installed contract is explicitly unitless (no effective/default unit and no dimensional allowed-unit choices), with valid canonical provenance and P2.3 quality. Missing receipt, stale/offline/invalid evidence still fails existing quality gates. Legacy numeric/1-or-0 Boolean comparisons and canonical typed Boolean values remain supported; payload-only legacy predicates and generic comparison semantics are unchanged.

Both concurrent-edit regressions now observe actual PostgreSQL lock waiting and identify the successful submission. They reload and compare exact ordered conditions/actions, metadata, priority and cooldown, verify the returned working UUID, human revisions [1, 2], one root-revision increment, no losing successor/overwrite and immutable UPDATE rejection. The modern draft-only race retains active V1/epoch; legacy enabled=false composite editing retains the active pointer but legitimately pauses/increments epoch. Tokenless pre-stale-form detection is still not guaranteed.

Final evidence and readiness are recorded in the ticket/ledger. The complete backend passed 915 tests, but the frontend result was 451 passed / 1 timeout, so full validation FAILED and correction closure is not ready for targeted re-review. No frontend fix/retry or fast validation followed the failed gate. These are condition-safety and acceptance-proof corrections only: no V25 change, new migration, frozen snapshot, trigger/temporal engine, arbitration, outbox or physical verification.

<a id="c01"></a>
## C01. Objective and status language

A production evaluation must select an explicit trigger, capture a stable context, use trustworthy evidence, pin an immutable rule version, explain condition decisions, propose actions, arbitrate candidates and dispatch only an authorized plan. Dry-run uses the same decision semantics with isolated side effects.

The words MUST and MUST NOT describe future implementation requirements. They do not assert that the baseline already meets them. Every implementation slice requires a separately approved plan. P3.0 authorizes documentation only.

<a id="c02"></a>
## C02. Baseline behavior and compatibility boundary

The baseline RuleMatcher evaluates enabled rules for every DomainEvent dispatched to it. EventDetector produces TWIN_CREATED, unconditional TWIN_UPDATED for accepted Twin mutations, and numeric SENSOR_VALUE_CHANGED at an absolute delta of at least 0.5. One observation can produce several separate evaluations.

At the committed baseline, rules had mutable definitions, an enabled flag, ascending numeric priority, AND conditions and optional global-per-rule cooldown. Old RuleMatch history stores definition snapshots and condition evidence but no immutable ruleVersionId. Capability conditions still consult current Twin registry state; legacy payload conditions retain their string/Double comparison behavior. Capability quality remains gated by P2.3 in the production evaluator. The checkpoint above distinguishes the new version/lifecycle foundation from this baseline.

At the baseline, RuleRegistry.enable bypassed complete registration validation and RuleCanvas mocked saving. The foundation fixes the enable path and disables mock-save success. ActionPlanner still dispatches separately for each RuleMatchedEvent under C13's restricted exception, and the frontend simulator remains an independent evaluator. Those remaining gaps are not approved final production semantics.

Compatibility preserves existing routes, supported legacy definitions and truthful historical evidence. Explicit safety tightenings are current-contract activation validation, imported-rule reconciliation, persistence-gated authorization and runtime invalidity suppression. These may prevent execution that the old implementation allowed; they must be diagnosed rather than hidden.

<a id="c03"></a>
## C03. Evaluation scope and canonical identities

Initial condition scopes are DEVICE and TRIGGER_DEVICE. Each evaluation has exactly one resolved condition-evaluation device.

| Scope | Resolution and eligibility |
|---|---|
| DEVICE | Resolve the declared device. Device-bearing triggers must match the declared scope/filter; supported manual/timer sources carry an explicit scope. |
| TRIGGER_DEVICE | Resolve the device carried by the trigger. Reject eligibility when it is absent; manual requests must explicitly supply a supported device scope. |

All reported-state conditions resolve within that device. New bound conditions use canonical binding identity, validated ownership/direction/enabled state and typed contracts. Keys/aliases are configuration inputs, not permission to select an ambiguous same-name state.

Explicit actions may target another device, matching existing builder support. Their targets are independently validated. This does not authorize arbitrary cross-device condition snapshots, which are deferred.

The legacy adapter retains unambiguous DEVICE_ID EQ and trigger-device behavior. Multiple contradictory DEVICE_ID predicates or non-EQ predicates used as a scope selector MUST NOT be silently converted. Reconciliation records a scope diagnostic and prevents dispatch until explicitly corrected. Original predicates/snapshots are retained.

<a id="c04"></a>
## C04. Immutable versions and definition identity

A logical Rule has a stable UUID. Each executable RuleVersion has a UUID and a positive monotonically increasing revision unique within that logical rule. Executable content is immutable and contains the full applicable definition: scope, triggers, repeat policy, conditions/actions with stable identities, priority, cooldown policy, temporal configuration and definition metadata.

Validation/workflow metadata is separate from immutable executable content. A version is never mutated to edit an active definition. Submitted definition comparison excludes execution lifecycle flags, revision tokens and transport fields; changing only enabled=false does not constitute a definition edit. Definition field ordering and typed comparison normalization must be specified and tested by the implementation slice.

Modern edits to active content create a successor working DRAFT. The current active version remains selected until deliberate activation. Graph metadata such as `metadata.version = "1.0.0"` is not RuleVersion identity and does not establish executable graph branching.

Evaluation pins ruleId, ruleVersionId, ruleRevision, executionEpoch and the immutable executable definition at acquisition. It uses that definition throughout the decision. Later edits cannot alter its conditions or evidence. Pinning the definition in the foundation does not establish the full frozen state snapshot of C10.

<a id="c05"></a>
## C05. Logical lifecycle versus working-version workflow

Logical execution lifecycle and working-version validation are distinct. For example, logical state ACTIVE with activeVersion V1 and workingVersion V2/DRAFT is valid; creating V2 does not move the logical rule to DRAFT.

Allowed logical transitions are:

- DRAFT -> VALIDATED after successful validation of the intended working version.
- VALIDATED -> ACTIVE after current-contract validation and atomic ownership selection.
- VALIDATED -> DRAFT when a successor working definition replaces the sole validated working selection.
- ACTIVE -> PAUSED.
- PAUSED -> ACTIVE only after revalidation of the intended version.
- DRAFT, VALIDATED, ACTIVE or PAUSED -> ARCHIVED.
- ARCHIVED is terminal in Phase 3.

Working definitions progress from DRAFT to VALIDATED through a recorded result; further editing creates a new draft version. An ACTIVE or PAUSED root may retain a separate working draft without changing execution ownership. ACTIVE requires a legitimate activeVersion reference and successful activation/reconciliation checks; later runtime invalidity is governed by C08 and import handling by C07.

Pause prevents new execution authorization. It does not retract commands whose guarded durable acceptance completed before the pause. Archive preserves immutable versions and history. Pause/resume and activation reset temporal/repeat generations as specified in C14-C15; logical cooldown is preserved.

<a id="c06"></a>
## C06. Legacy API and enabled compatibility

Existing /api/v1 routes and enabled fields remain supported. Responses retain their established fields and may add lifecycle, active/working version references, optimistic revision and reconciliation diagnostics. enabled reflects reconciled logical execution state: true only for ACTIVE with passed reconciliation where required. A successor draft alone does not disable the active root. Later runtime invalidity suppresses the affected evaluation/candidate under C08 without rewriting enabled or logical lifecycle.

| Legacy operation | Required adapter behavior |
|---|---|
| POST enabled=true | Create an immutable definition, validate current contracts and activate as one operation. |
| POST enabled=false | Preserve registration validation; create a non-executing selected definition with an appropriate paused/working projection. Do not invent a previous activation. |
| PUT enabled=true | Explicit composite create/select submitted version -> validate current contracts -> activate atomically. Failed validation leaves the previous active version, lifecycle and persisted definition selection unchanged. |
| PUT enabled=false, definition changed | Create a successor working version from submitted content and atomically pause logical execution. The executable edit is not discarded. |
| PUT enabled=false, definition unchanged | Pause without manufacturing a new version. |
| Enable | Validate/select the intended working version explicitly, or the retained selection when no successor exists. Never silently choose an older definition over a submitted successor. |
| Disable | Pause without editing executable content. |
| DELETE | Archive while preserving legacy removed-from-default-list and subsequent not-found presentation. Historical evidence remains accessible through history. |

Current missing-ID enable/disable calls return success without mutation; preserve that compatibility. An archived legacy-deleted rule is treated as unavailable to these legacy operations and MUST NOT be resurrected. Modern activation of ARCHIVED is rejected.

New version-management mutation APIs require expected revision or If-Match. Legacy PUT/enable/disable calls may omit a token. Server-observed optimistic revisions protect overlapping transactions where possible. A tokenless client cannot reliably detect a form that was already stale when its request started; strong stale-client protection MUST NOT be claimed for it. The project's own frontend will send revision tokens in the implementation slice. Tokenless support is an explicit compatibility exception, not the recommended modern workflow.

<a id="c07"></a>
## C07. Populated migration and reconciliation

Only each current mutable rule definition is imported into one initial immutable version. Record the actual import time and provenance. Deleted logical rules referenced only by history are not reconstructed. Old RuleMatch snapshots remain authoritative and their new version attribution remains NULL.

Preserve original enabled state as migration provenance, separately from the post-migration execution projection. Do not fabricate past versions, successful validations, activation transitions or historical version IDs.

Before imported definitions become eligible for new runtime dispatch, reconciliation validates current scope, devices, real binding/legacy-capability existence, directions, enabled state, values, units and action contracts.

Required reconciliation policy:

| Outcome | Logical/compatibility projection |
|---|---|
| Pending reconciliation | PAUSED, execution ineligible, original enabled provenance retained; no dispatch. |
| Valid, originally enabled | Record a real successful reconciliation now; atomically activate the imported version now. Do not describe it as a historical activation. |
| Valid, originally disabled | Remain PAUSED with an explicitly selected/validated working version. Enable later requires current-contract revalidation. |
| Invalid or ambiguous | Remain PAUSED and ineligible, expose structured FAILED/REQUIRES_REVIEW outcome and original enabled provenance. Correction and explicit validation/activation are required. |

An originally enabled invalid rule therefore projects enabled=false with a migration diagnostic. This is a disclosed safety tightening, not a fabricated successful validation to preserve enabled=true. Reconciliation must be restart-safe and must not dispatch retroactive actions merely because an imported rule is activated.

Automatic restoration requires durable successful reconciliation and an atomic recheck that the imported selection and restoration eligibility have not been superseded. A user pause or edit during reconciliation invalidates delayed automatic restoration; a stale worker MUST NOT undo that intervention. Startup/infrastructure failure leaves execution ineligible and fails closed. Retrying reconciliation cannot invent past validation or activation facts.

The implementation MUST perform a real populated-database preflight, including ambiguous/non-EQ DEVICE_ID conditions, missing/disabled bindings, missing installed legacy capabilities, invalid action references and old historical matches. P3.0 document materialization itself authorized no migration; current V25 implementation was separately approved.

<a id="c08"></a>
## C08. One validation authority and runtime invalidity

Use one authoritative validation path for safe creation/editing, explicit validation, activation/resume and execution-time safety checks. Reuse capability/binding/command validators; do not maintain competing rule-validator semantics.

Validate device/scope existence, ownership, direction, enabled state, installed capability existence, alias ambiguity, operator/type compatibility, numeric constraints, enum membership, Boolean parsing, valid STRING values, effective units, action support/parameters, write commands/subtargets, triggers, temporal parameters, repeats and unsupported combinations. No implicit unit conversion or generic OBJECT comparison is introduced.

Draft storage may allow incomplete executable fields, but incomplete/invalid drafts cannot activate. The legacy registration adapter preserves its current complete-definition validation expectation.

Errors include code, message, location/path, conditionId or actionId when applicable and safe details. Preserve existing ErrorResponse status/error/message/timestamp meanings; add structured validation detail rather than replacing compatibility with a concatenated string.

Activation/resume validate current device and binding contracts transactionally. Failure does not partially change lifecycle, active selection, registry/cache or temporal state. After activation, runtime invalidity suppresses the evaluation/action candidate with structured evidence while keeping the logical rule ACTIVE. No automatic lifecycle transition is inferred from an unavailable target.

<a id="c09"></a>
## C09. Concurrency, cache ownership and authorization ordering

The database is authority for lifecycle, active selection, revisions, reconciliation eligibility, cooldown and temporal state. Registry/cache entries are projections, not independent execution authorization.

Version edits, validation and activation use optimistic revision checks. Version revision allocation is serialized per logical rule with database uniqueness. Two mutations carrying the same expected revision cannot silently overwrite; one succeeds and one conflicts. Tokenless legacy limitations are governed by C06.

The root optimistic rowRevision is distinct from immutable ruleRevision and the independent executionEpoch. ExecutionEpoch changes when execution ownership/authorization changes, including activation, pause, resume and archive; creating a working draft while V1 remains ACTIVE does not itself change execution ownership. An evaluation captured before pause/resume cannot regain permission merely because the same version is active again: its epoch must still match. Persist and reload this authority rather than deriving it from cache.

A source/ownership read used to discover involved devices is rechecked after acquiring locks. The shared write-lock order is sorted affected device IDs, then logical rule rows, then runtime rows in stable key order. Reference-definition revisions or equivalent contract tokens must be verified; a changed contract cannot be silently validated using stale managed entities.

Persistence must commit before cache projection and external publications. Failed writes/rollbacks cannot leak activation or timer state into cache. Multi-instance execution cannot rely solely on JVM synchronized blocks or startup-only hydration.

Dispatch authorization atomically rechecks ACTIVE lifecycle, captured activeVersionId and executionEpoch, completed reconciliation/activation eligibility and current target device/binding/action validity. For commands, this authorization must be inside the existing device-first CommandAcceptanceService transaction before ActionHistory acceptance, current desired state, desired history or current verification initialization are written. Lost permission means no new acceptance. Commands already durably accepted before a later pause/version switch retain their P2.4 lifecycle and may complete publication. Non-command executors require an equivalent current-owner gate before their side effect. Decision transactions must not retain locks while invoking a nested REQUIRES_NEW command acceptance that would wait on those locks. The C13 foundation exception does not relax these checks.

<a id="c10"></a>
## C10. Frozen evaluation context and consistent snapshot

Each evaluation has an immutable RuleEvaluationContext derived from a frozen batch snapshot. It includes evaluationId, batchId, rule/version/revision, evaluationTime, snapshot acquisition metadata, typed trigger/identity, scope, reported states, typed values, quality/freshness, connectivity, relevant command/verification facts and temporal/repeat inputs. Include available source, correlation and causation references without inventing missing historical fields.

Acquire the relevant data together using a read-only PostgreSQL REPEATABLE READ transaction, or a verified equivalent single-snapshot strategy. Default READ COMMITTED across separate queries is insufficient. Capture the batch's eligible versions, canonical conditions' states, persisted connectivity, relevant device/binding/definition/freshness contracts and needed command/verification/runtime inputs in that boundary.

Do not combine independently changing cached connectivity with queried Twin rows and call it an atomic snapshot. Deep-copy mutable nested typed structures before leaving the snapshot boundary. Conditions use captured inputs rather than repeatedly fetching changing current state. Quality calculations use one evaluation time and captured policy inputs, preserving C11/P2.3 boundaries.

The triggering observation and current reported snapshot are separate. Later observations may already exist at snapshot acquisition; record both identities/times truthfully. A snapshot is not a reconstruction of all physical state at device event time.

Runtime mutation follows a separate write boundary with captured runtime revision/generation rechecks. Stale runtime inputs require a coherent retry/reacquisition or explicit suppression, not a mixture of old evidence and new temporal state. Dispatch uses the current checks in C09/C13.

<a id="c11"></a>
## C11. Time semantics and exact boundaries

Keep these times distinct: device event time, backend receivedAt, source trigger occurrence time, evaluationTime, snapshot acquisition time, scheduled dueAt and temporal window start/end. Injected backend Clock controls live rule evaluation. Frontend time is never live authority; simulation time is explicit and isolated.

| Boundary | Contract |
|---|---|
| Window inclusion | `(evaluationTime - duration, evaluationTime]` |
| Ordinary timer/duration completion | `now >= dueAt` |
| Cooldown suppression | `now < lastMatchedAt + cooldown` |
| P2.4 derived ACK timeout | `now > ackDeadlineAt`; equality is not timeout |

Use UTC instants and database microsecond ordering precision where applicable. Preserve existing P2.3 freshness age/threshold semantics rather than replacing them with a different boundary. Device timestamps drive existing replay/skew checks, not backend duration authority. Temporal source ordering is serialized and does not rewind on duplicate/late evidence.

<a id="c12"></a>
## C12. Typed triggers and legacy event adapter

Typed envelopes carry trigger type, truthful source identity, device/scope, occurrence time, evidence and available parent/correlation references. Imported rules subscribe only through their deliberate legacy adapter; introducing new trigger types does not silently expand their subscriptions.

| Trigger | Source identity / time | Eligibility and repetition |
|---|---|---|
| LEGACY_DOMAIN_EVENT | Persisted existing DomainEvent ID and event timestamp | Preserve the current TWIN_CREATED/TWIN_UPDATED/SENSOR_VALUE_CHANGED stream and payload predicates. Distinct events remain distinct. |
| ACCEPTED_TELEMETRY | Actual telemetry composite identity and committed accepted-observation disposition; backend receipt | Only validated newer observations with accepted Twin mutation. New same-value observations qualify; stored replay/out-of-order/future-rejected rows do not. |
| TWIN_STATE_CHANGED | Persisted reported transition ID and transition timestamp | Initial creation or actual semantic changes under P2.2 equality; same-value freshness updates do not qualify. |
| CONNECTIVITY_CHANGED | Persisted observed status-change identity/generation and backend observation time | Actual committed changes only; duplicate status observations do not create transitions. Current last-known state alone is not a historical trigger ID. |
| COMMAND_PUBLICATION_FAILED | Actual command ID and committed publication-failure fact/time | Separate publication failure from validation rejection before command acceptance; duplicates of one fact do not fire anew. |
| COMMAND_ACK_REJECTED | Command ID and first authoritative rejected ACK; backend ACK receipt | Wrong-device/unknown/conflicting/identical duplicate ACKs do not create another fact. |
| COMMAND_ACK_TIMED_OUT | Command ID and stored ACK deadline | Observe derived timeout once while no authoritative ACK supersedes it, strictly after deadline. No resend. A late ACK already present cannot justify inventing a historical timeout event. |
| VERIFICATION_CHANGED | Actual CONVERGENCE_CHANGED transition ID/time | Explicit status filters distinguish CONVERGED, DIVERGED and TIMED_OUT. Historical verification is separate from current observation quality. |
| VERIFICATION_TIMED_OUT | The same transition identity with TIMED_OUT filter | A named filtered form, not a second duplicate occurrence in the same rule subscription. |
| MANUAL | Persisted server-issued request/evaluation identity and backend time | Explicit scope and selected version. No fake DomainEvent. |
| SCHEDULE | Schedule identity, due slot and generation | C18's persistent occurrence/consumption authority. |
| TEMPORAL_DUE | Temporal identity, generation and stored due time | Recheck current ownership, status and evidence; consume a due generation idempotently. |
| RULE_MATCHED | Actual persisted parent match ID/time | Explicit downstream subscriptions under C17. |

The existing legacy numeric 0.5 event threshold does not define semantic Twin equality. ACK acceptance is not convergence. Rule readback facts do not imply physical ESP verification.

<a id="c13"></a>
## C13. Persistence-gated action authorization

The final production pipeline, mandatory before P3.5 completion, requires all of the following. Only the explicitly bounded foundation exception below permits a temporary departure from full frozen-context and ActionPlan requirements; it does not waive source/match persistence or current-owner safety.

1. It has a valid truthful source identity and supported scope.
2. Required source persistence has committed successfully.
3. Eligibility, validation and frozen version/context evaluation have succeeded.
4. RuleMatch persistence has succeeded.
5. Candidate arbitration has produced a persisted, authorized ActionPlan.
6. ACTIVE lifecycle, active-version/epoch ownership, reconciliation/activation eligibility and current target validity pass the atomic dispatch check in C09.

| Source family | Required durable source fact |
|---|---|
| Legacy events | Actual persisted DomainEvent; subscriber registration order is not proof. |
| Accepted telemetry | Committed telemetry reference plus truthful accepted-observation identity/disposition. Persist the necessary acceptance facts in the source transaction; merely finding a raw history row is insufficient. |
| Twin change / verification | Actual committed transition row with matching device/target/status attribution. |
| Connectivity | Committed observed transition identity/generation tied to the status update. |
| Publication / ACK | Authoritative persisted action lifecycle fact and first-ACK semantics; necessary identity/time must remain inspectable. |
| Manual / schedule / temporal due | Truthful persistent request or due-generation/consumption identity; no fabricated telemetry or DomainEvent. |
| Rule matched | Successfully persisted parent match with bounded causation chain. |

Any required persistence failure fails closed. An in-memory broadcast alone cannot dispatch. Old EventDispatcher ordering/failure behavior is intentionally tightened at the Rule authorization boundary. Matching a condition, selecting a candidate and recording a command acceptance remain separate facts.

### Foundation-stage exception: restricted legacy-event compatibility path

During the separately approved P3.4 + P3.1 foundation implementation, only existing supported legacy DomainEvent evaluation (TWIN_CREATED, TWIN_UPDATED and SENSOR_VALUE_CHANGED with its existing payload/operator semantics) may retain the current per-match ActionPlanner execution path. This is a temporary compatibility policy, not a completed production-grade Rule Engine guarantee. The old ActionPlanner is NOT an ActionCandidate pipeline, arbitrated ActionPlan, batch conflict resolver, durable candidate selector or P3.5 authorization mechanism. No fake persisted plan is permitted.

New typed, temporal, scheduled or rule-chain triggers, future operators and unsupported action policies MUST NOT use this exception. All applicable foundation gates remain mandatory:

1. The source has an actual durable DomainEvent identity; required source persistence commits successfully before evaluation can authorize downstream execution. Subscriber registration order is not proof, and fabricated DomainEvents are forbidden.
2. Evaluation pins ruleId, ruleVersionId, ruleRevision, executionEpoch and the immutable executable definition. Eligibility and current-contract validation must pass.
3. A truthful version/epoch-attributed RuleMatch is successfully persisted before downstream action authorization. Failed source or match persistence means no action dispatch or new command acceptance.
4. Before any new Rule-originating side effect, atomically recheck ACTIVE lifecycle, the captured activeVersionId and executionEpoch, completed reconciliation/activation eligibility and valid target device/binding/action contracts.
5. For SEND_COMMAND, perform gate 4 inside the existing device-first CommandAcceptanceService transaction BEFORE ActionHistory acceptance, current desired state, desired history and current verification initialization. Losing permission prevents new acceptance; previously durably accepted commands retain their P2.4 lifecycle and may finish publication.
6. Non-command executors need an equivalent current-owner gate before their side effect. CREATE_ALERT and WEBHOOK remain their current stub implementations; this policy does not implement them.
7. Record truthful suppression/diagnostic evidence. Stale version/epoch, paused lifecycle, required persistence failure and invalid binding are NOT MQTT publication failures. If persistence is unavailable, expose truthful failure diagnostics rather than claim a saved match/suppression record.

**Explicit transitional limitations:** separate legacy matches may still dispatch independently through ActionPlanner. Conflicting requests are not batch-arbitrated; priority ordering is not conflict resolution; identical commands are not guaranteed to coalesce; no global cross-trigger priority is guaranteed; no persisted P3.5 ActionPlan is created. Full frozen evaluation-state snapshots (C10) and full multi-instance durable cooldown serialization (C14) are not yet implemented. No Phase 4 outbox, retry or delivery recovery is added. Definition pinning, match history and compatibility cooldown checks cannot be represented as those missing guarantees.

**Exit gate:** before P3.5 may be marked complete, the approved candidate/arbitration/persisted ActionPlan pipeline becomes dispatch authority for legacy actions too. Independent per-match dispatch must no longer bypass arbitration, every candidate outcome must have truthful evidence, full C13 authorization must be enforced, and this exception must be retired. Retirement is future work, not implemented by this documentation correction.

If source/match gating and version/epoch authorization cannot fit the approved transaction and device-first locking boundaries, report an implementation blocker. Do not weaken a gate, invent a plan, introduce an outbox or implement P3.5 early to conceal the incompatibility.

Source/plan gates must not become a Phase 4 outbox, generic MQTT inbox or delivery retry queue. Rule-level duplicate consumption and timer bookkeeping are bounded operational decision state. A crash after durable decision/acceptance but before publication is not silently recovered by resending in Phase 3.

<a id="c14"></a>
## C14. Durable cooldown, repeats and hysteresis

Imported rules retain global-per-logical-rule cooldown. New rules may explicitly opt into per-resolved-scope cooldown. Cooldown identity belongs to the logical rule, not RuleVersion. Version edits, activation and resume cannot bypass an existing cooldown; policy changes must not erase stored last-match authority.

Cooldown begins after a repeat-eligible RuleMatch is durably persisted. Subsequent arbitration suppression or dispatch failure does not erase that match. Runtime updates and cooldown checks are serialized in the database for multi-instance safety. Failed match persistence cannot advance cooldown. Null cooldown means no suppression, but persisted match authority still survives later policy changes.

These are full runtime requirements. Under C13's restricted foundation exception, logical cooldown compatibility and preserved match history can be proved separately; they do not establish atomic cross-instance durable cooldown serialization. Temporal/repeat generation resets remain pending until the owning runtime slice.

Repeat modes are EVERY_TRIGGER_WHILE_TRUE and EDGE_TRUE. Once-per-cooldown is the former plus cooldown. Imported rules retain every-trigger behavior. EDGE_TRUE fires on an eligible transition into true; the first eligible true in a new generation may fire once. UNKNOWN/invalid evidence does not manufacture a false edge to rearm an existing true latch.

Pause, resume, archive and new-version activation reset pending temporal/repeat generations; cooldown persists. Runtime invalidity/quality loss invalidates positive temporal progress but does not pretend a false observation rearmed an already true edge. Numeric hysteresis requires explicit same-unit enter/exit comparisons with tested boundaries; no hidden hysteresis is inferred.

<a id="c15"></a>
## C15. Temporal evidence

Temporal state identity includes ruleVersionId, conditionId, resolved scope and runtime generation. Old-version progress cannot satisfy a new version. Windows and retained samples are bounded, deadlines durable, and duplicates/out-of-order handling deterministic.

| Operator | Inputs and proof | Reset, expiry and due behavior |
|---|---|---|
| FOR_DURATION | Qualified true accepted observations with valid freshness coverage. Backend receipt-time duration; no backdating from old device time. At least the start evidence and valid coverage through completion are required. | False, runtime invalidity, non-GOOD quality or a freshness gap resets pending progress. Complete at due only after rechecking coverage. A single stale sample cannot prove continuous truth. |
| COUNT_WITHIN | Distinct qualified predicate-satisfying observations in C11's window. Positive configured count; explicitly distinguish sample count from state-edge occurrences. | Evict expired members, ignore duplicates, never rewind for late evidence. Quality/invalidation policy resets invalid progress; timer expiry may remove eligibility. |
| ABSENCE_FOR | Valid scope/binding contract and elapsed time since the latest accepted observation or activation/resume arm time, whichever is later. | Accepted newer observations rearm; invalid/replayed observations do not. Missing positive state is an explicit evidence exception, not a GOOD sample. Due worker rechecks the source watermark. |
| OFFLINE_FOR | Actually observed last-known OFFLINE connectivity. Arm no earlier than activation/resume. UNKNOWN does not prove offline duration. | ONLINE resets; runtime invalidity suppresses. Reconcile persisted deadline/status after restart. It describes connectivity evidence, not electrical truth. |
| TREND | At least three distinct numeric samples, explicit required span/change, same effective unit and bounded window/sample storage. Implement last. | No interpolation. Non-GOOD quality, invalidity, missing coverage or expiry invalidates the trend. Device-time disorder does not rewind runtime state. |

Pause/resume/new-version activation do not silently carry pending progress. Backend restart can resume a persisted generation only after ownership, quality, last-observation and gap checks. A freshness gap crossed during downtime is not repaired retroactively by the next sample. Absence/offline operators have the explicit evidence exceptions above; positive sensor predicates retain quality gating.

<a id="c16"></a>
## C16. Candidates, arbitration and action evidence

The final P3.5 flow is match -> ActionCandidate -> selected/suppressed/held disposition -> approved ActionPlan -> dispatch attempt. No unresolved candidate may dispatch. Arbitration covers one trigger batch and its pinned eligible rule/version set, not unrelated concurrent batches. C13's restricted foundation legacy path is the sole temporary staging exception: it creates no arbitration candidate/plan and cannot claim these guarantees. All legacy action dispatch must adopt this flow and the exception must be retired before P3.5 completion.

Canonical command target is deviceId + binding/legacy target + subtarget. Relay channels are separate targets. Validate command/value/unit types before comparing candidates.

| Situation | Required policy |
|---|---|
| Same target, identical command/value/unit | Coalesce and retain all contributors. |
| Same target, conflicting values/commands | Smaller numeric priority wins. |
| Equal priority, conflicting values | Suppress conflicting candidates with explicit conflict evidence. Stable IDs are for ordering/evidence, not permission to select a random winner. |
| Different devices/subtargets | Independent unless an explicit validated mutual-exclusion group applies. |
| Mutual exclusion | Apply the configured group's deterministic rank/conflict policy. |
| Safety override | Explicit validated policy only; never inferred from severity, names or AI. Its allowed configuration authority must be defined before exposing it. |
| Manual approval | Workflow initially deferred. Unsupported approval configuration is rejected; an unresolved/held candidate is never dispatched. |

Use stable priority/rule ID/revision/action ordinal ordering, never repository iteration order or random UUID ranking. There is no implicit last-write-wins arbitration policy.

Evidence records candidate generation, coalescing, selection, suppression/hold, reason, winning candidate/rule, target, priority, batch/evaluation and actual dispatch reference when present. Coalescing produces one command with a legitimate primary-match link for existing ActionHistory and separate contributor records; it does not fabricate an execution for each match.

Concurrent batches retain P2.4 serialized durable command acceptance and supersession. Phase 3 does not claim cross-batch global priority or persistent safety reservations. Plan authorization is a decision gate, not reliable delivery recovery.

<a id="c17"></a>
## C17. Rule chains

RULE_MATCHED subscriptions are explicit. Propagate real root/parent references and correlation. Detect repeated logical rule IDs in the causation chain, regardless of version changes, and enforce maximum chain depth 8. Reject excess/cyclic work with evidence. Use a bounded work queue rather than unbounded synchronous recursion. Missing historical causation is recorded as unavailable, never invented. No crash-recovery delivery guarantee is inferred from a rule chain.

<a id="c18"></a>
## C18. Schedule and due-time authority

Rule timers use their own persistent due/generation state. A bounded scanner uses injected Clock, reloads lifecycle/version/runtime ownership, locks and rechecks eligibility, and consumes a generation idempotently. Multi-instance workers cannot create duplicate matches/plans from the same due occurrence. Memory-only deadlines are not authoritative.

Initial schedules are UTC one-shot and fixed interval. Periodic missed slots are coalesced into one eligible evaluation, skipped-slot count recorded, and the next slot advanced into the future. One-shot due work may evaluate once after restart unless explicitly expired. Ordinary deadlines use C11; ACK timeout retains its strict boundary. Timer processing does not share Twin verification tables or resend MQTT commands. Cron/timezone/DST scheduling is deferred from the first scheduling increment.

<a id="c19"></a>
## C19. Backend dry-run

Initial modes are SUPPLIED_STATE and CURRENT_STATE. Use the same validation, selected version, trigger/context processing, quality, temporal/repeat semantics, candidate generation and arbitration as production. No independent JavaScript evaluator is authoritative.

SUPPLIED_STATE uses typed supplied inputs, explicit isolated simulation time and isolated runtime state. CURRENT_STATE acquires a backend snapshot/Clock and copies relevant runtime inputs into isolated decision state. A simulated device uses the same mode and engine, not a special comparator path. Unsupported version/scope/trigger/input combinations are diagnosed before evaluation.

Limits are one evaluation scope, at most 100 selected versions, at most 100 conditions per version and at most 1 MiB supplied input. Validate these before evaluation.

Dry-run MUST NOT publish MQTT, write desired state or ActionHistory, create production RuleMatch, advance production cooldown/repeat/temporal state, alter lifecycle or publish production realtime effects. It returns evidence, proposed candidates, arbitration and hypothetical state effects without committing them.

Given the same definition/version, trigger, context/time, runtime inputs and arbitration inputs, live/dry decisions and evidence are equivalent apart from generated presentation IDs and transport timing. false, zero and valid empty STRING remain typed values, not missing input. Legacy adapters retain explicit legacy comparison semantics rather than loose JavaScript coercion.

<a id="c20"></a>
## C20. History and grounded explanations

Keep existing immutable RuleMatch snapshots and evaluated-condition evidence. Foundation rows add legitimate pinned version/revision/executionEpoch attribution; later slices add trigger, evaluation/batch, scope, frozen inputs/quality, temporal and candidate/arbitration attribution as actually implemented. Historical rows keep NULL unknown attribution. Short-circuited conditions are UNEVALUATED, not invented FALSE. Foundation explanations MUST NOT invent an arbitrated winner or frozen state snapshot.

Manual/timer matches use truthful alternative trigger references, not fake DomainEvents. Device history and explanation consumers must handle those cases without losing scope identity. Persist selection/contributor facts before an explanation claims why an action won.

AI explains persisted facts and does not authorize activation, arbitration or dispatch. Preserve deterministic fallback, grounding and the data-minimization boundary when new evidence fields are introduced. Fix the stale all-executors-are-stubs disclaimer during the owning implementation slice: distinguish actual MQTT publication, stub executors, software ACK, qualified readback and physical evidence. No AI SDK/provider redesign is required by Phase 3.

<a id="c21"></a>
## C21. Persistence design and history durability

V24 is the committed baseline migration. The approved foundation now adds V25 in the uncommitted worktree; no V26+ exists. P3.0 itself created no migration. The table distinguishes the full design scope: only foundation ownership/version/workflow/validation and match-attribution storage has been implemented; cooldown/temporal/schedule/candidate/plan and alternative-trigger storage remain later work.

| Storage | Identity, constraints and indexes | Lifecycle/history policy |
|---|---|---|
| Logical rule ownership | Stable rule UUID, optimistic rowRevision, independent executionEpoch, lifecycle, active/working references and reconciliation provenance | Gate ACTIVE execution; archive rather than destructively removing immutable history. |
| rule_versions | Version UUID; unique (ruleId, revision); full immutable executable content | Restrict destructive deletion; references must belong to the same logical rule. |
| Validation/reconciliation metadata | Version/attempt identity, result, current-contract references and actual validation time | Keep failed/pending provenance; no fabricated historical success. |
| Source fact attribution | Actual accepted-observation identity/disposition/receipt and connectivity transition identity/generation; indexes appropriate to source lookup | Extend authoritative source persistence where needed without inventing old receipt/acceptance/transition facts or creating a generic inbox. |
| Cooldown/repeat runtime | Logical rule plus explicit global/scope key; unique gate state | Cooldown survives versions; repeat generations are explicit operational state. |
| Temporal runtime | Version, condition, scope, generation; pending due-time index | Bounded operator evidence; controlled cleanup of operational state cannot erase history. |
| Schedule/deadline runtime | Schedule/source ID, due slot, generation and consumption state; pending index | Restart and multi-instance authority; bounded current state rather than every non-match log. |
| Match attribution | Existing match UUID plus truthful version/revision/executionEpoch and later trigger/evaluation/scope fields | Preserve old snapshots/rows and NULL unknown fields; no destructive cascade. |
| Action plans/candidates | Batch/plan/candidate IDs; unique batch/version/action identity; target and contributor indexes | Durable selected/suppressed/held evidence and legitimate command links; no dispatch retry queue. |

Operational foreign keys may use controlled owner cleanup, but immutable version/match/arbitration evidence MUST NOT be cascade-deleted when a binding, device, event or operational row disappears. Use durable identity snapshots or restrictive ownership where appropriate. No history-retention job or unbounded per-non-match persistence is introduced.

Manual/timer history requires a truthful alternative to mandatory legacy event_id: new rows may have NULL event_id only with valid trigger attribution; existing rows are unchanged. Update DTO/query/explanation compatibility together. Do not attach invented historical device/version/trigger IDs.

<a id="c22"></a>
## C22. Deferrals, roadmap completeness and boundaries

HISTORICAL_REPLAY is deferred from the initial P3.6 increment because accepted-observation disposition, backend receipt, connectivity and historical contract evidence are insufficient for faithful replay. Reject unsupported requests clearly. The full roadmap replay requirement remains OPEN unless separately removed by approval or later implemented. Initial dry-run completion is not full Phase 3 roadmap completion.

Manual approval workflow is initially deferred; its roadmap item is optional. Graph branching is not promised by the current flattened builder/compiler or this initial Rule Engine contract; broader visual workflow execution remains deferred and must be recorded accurately against later frontend scope. Cron/timezone/DST scheduling and arbitrary cross-device condition snapshots are likewise deferred. TREND is planned last, not falsely marked implemented or removed from temporal scope.

Preserve P2.4: ExecutionStatus.SUCCESS means publication, ACK is not convergence, reported and desired state are separate, quality and verification retain their meanings, relay subtargets remain isolated and COMMAND_ECHO is explicit software readback attribution. Existing REAL-device command guards remain in force.

No Phase 4 outbox, delivery retry/resend/recovery, generic inbox dedupe, READ_WRITE, firmware changes, physical verification claims, hidden unit conversion, unbounded non-match history or unapproved breaking /api/v1 change belongs to this work.

<a id="c23"></a>
## C23. Approval and acceptance discipline

The [ticket](p3-implementation-ticket.md) defines dependency-safe slices; slices are not automatic commit boundaries. Every slice requires audit/plan, user approval, implementation, focused validation, full validation when appropriate, final review and controlled commit planning.

The [ledger](p3-acceptance-ledger.md) preserves 72 scenario IDs. Existing regression foundations are not new Phase 3 acceptance proof. All currently unimplemented scenarios begin PLANNED; feature deferrals remain explicit and do not count as passes. Context restart means fresh Spring/application contexts over persistent PostgreSQL unless a separate OS-process restart is actually performed.

Foundation subcases, especially 12 (resume revalidation), 55 (cooldown compatibility/history) and 72 (legacy source/match failure gates), may be recorded as completed only after their actual tests pass. The full scenario remains open/PLANNED until all applicable assertions, including its later-runtime/ActionPlan requirements, are proved. The foundation does not complete frozen contexts, typed triggers, durable temporal runtime, arbitration, backend dry-run or full scenario 72.
