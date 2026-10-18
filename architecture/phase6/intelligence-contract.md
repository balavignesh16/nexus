# Phase 6 contract: safe AI intelligence

Status: P6.0 (2026-10-18). Binding for P6.1 to P6.6. Authority order: current source and committed migrations, then this contract, then `p6-implementation-ticket.md`, then `p6-acceptance-ledger.md`. The roadmap's Phase 6 text (`ROADMAP.md`) is the source of the goals and exit criteria; nothing here weakens it. Phase 3 (`architecture/phase3/rule-engine-contract.md`), Phase 4 (`architecture/phase4/reliability-contract.md`) and Phase 5 (`architecture/phase5/adapter-contract.md`) guarantees are preserved.

## 1. Goal and the one rule that governs everything

AI explains, recommends, drafts and requests approval. AI never publishes a device command, never activates a rule, never mutates the Twin, and never becomes the authority for any fact. Only validated rules or explicitly authorised commands reach the Action Engine, exactly as before Phase 6. Everything a model says is untrusted text until it has been parsed, bound to persisted evidence and checked; a statement that cannot be bound is rejected or marked UNKNOWN.

Roadmap exit criteria, mapped: every AI output cites platform evidence (sections 5 and 6); unsupported claims are rejected or uncertain (6, 9); AI-created rules need deterministic validation and approval (7, 8); AI failures cannot interrupt telemetry, Twins, rules or actions (4); an evaluation suite measures grounding and correctness (10).

## 2. Decisions (approved 2026-10-18) and what each binds

**D1 Approval identity.** NEXUS has no authenticated user model (Phase 5 section 18; the identity phase is Phase 7). Approval decisions are therefore recorded only by a privileged LOCAL administrative workflow (a command-line tool run as a database-authorised operator, in the style of the Phase 5 credential tool). No HTTP endpoint, UI action, header or parameter can approve; an architecture test fails on one. A decision records its actor provenance (operating-system account, database login role, host name), timestamp, rule id, version id, definition digest and the validation attempt it relied on; identity is never taken from request data. If no workflow of this kind is available, drafts stay pending. An AI recommendation is never an approval.

**D2 Providers.** A provider-independent boundary (`AiReasoningBoundary` already exists) with structured input and output, schema validation, timeouts, bounded retries, a circuit breaker, data minimisation and safe errors. The default works offline with deterministic implementations. A real provider needs an explicit enable property AND a separate explicit opt-in to send device evidence; either alone sends nothing. A provider has no access to backend services: it receives a minimised context object and returns text.

**D3 Persistence.** New tables in migration V42 (V41 is the latest applied; the next free number is verified before use, historical migrations are never edited): drafts, append-only approval decisions, recommendations, their evidence references, and a generation log (provider, model, configuration hash, input digest; no raw device data). AI text never replaces telemetry, rule facts or command-state history. Rule versioning and optimistic concurrency are reused, not duplicated.

**D4 Retention.** Unapproved drafts and transient recommendations: configurable, default 30 days, removed by an audited job. Approval decisions and critical evidence references: a separate, longer policy (default: kept; removal only by an explicit audited operator action). Authoritative Phase 3 to 5 facts (rule versions, matches, lifecycle, telemetry) are never deleted to satisfy an AI-output setting. No raw secrets in logs.

**D5 Evaluation.** A reproducible fixed corpus. Safety-critical requirements have ZERO tolerated violations: unauthorised command execution; autonomous rule activation; fabricated publication or ACK evidence; uncertain delivery reported as success; claims of physical actuation without verification; approval bypass. Release-blocking quality thresholds are in section 10. Results describe a finite corpus, never a universal guarantee.

**D6 Layer 3.** Deterministic anomaly detection is the only source of anomaly facts. A model may explain or rank those facts when enabled. Recommendations are read-only: no actuation, no activation, no hidden configuration change.

## 3. Inventory of the existing AI code (`backend/.../rule/ai`)

Already present and kept: `AiReasoningBoundary` (one method returning untrusted text) with `NoOpAiReasoningBoundary` and an OpenAI-shaped provider; `AiExplanationContext` and its mapper (data minimisation of a match context); `AiCandidateParser` (strict JSON parse, FACT/INFERENCE/UNKNOWN shape rules); `GroundingChecker` (every FACT resolves to the asserted value, INFERENCE needs resolving support, UNKNOWN is shape-checked) and `GroundingResult`; `DeterministicExplanationRenderer` (the fallback); `RuleMatchExplanationService` (the only caller of the boundary; falls back whenever AI is disabled, unavailable, malformed or ungrounded; fixed outcome codes); `ExecutionTruthfulness` (platform-owned statement of what a recorded status does and does not prove); `ExplanationRateLimiter`; `AiExplanationMetrics`; the controller; and evaluation scenarios (`rule/ai/evaluation`).

What Phase 6 adds, because it does not exist: explanations for rule NON-matches and for the command pipeline (Layer 1 gaps); explicit unknown-versus-negative evidence; an isolation layer (timeout, breaker, bulkhead, opt-in, architecture rules) around every model call; draft-rule assistance and the approval workflow; deterministic anomaly and recommendation services; and an evaluation suite with release thresholds. The existing guarantees are regression-tested, not rewritten.

## 4. Trust boundaries

```
 authoritative facts (read-only)            untrusted                 trusted gate               result
 rule_versions, rule_matches,   -> EvidenceBundle ->  provider text -> parse -> ground -> render
 arbitration, lifecycle facts,        (minimised)     (may be absent)   (schema)  (refs resolve)  explanation
 twin state, telemetry history
                                                      provider text -> parse -> draft port -> deterministic validator
                                                                         (AI_DRAFT, enabled=false)      |
                                                                                              privileged local approval
                                                                                              + DB guard + existing activation
```

Rules (each becomes an architecture or unit test in P6.2): AI packages may import read-only evidence readers and the draft port only; they may not import action application, outbox, executors, adapter or MQTT classes, Twin write services, `RuleLifecycleService` transitions, or any repository save path. Production classes outside the AI packages and their read-only API do not import the AI packages. Provider failures are contained at the boundary and degrade to the deterministic path. Timing: AI work runs outside the telemetry, rule-evaluation and command-acceptance paths.

## 5. Authoritative evidence inputs

* Rule matches and evaluated-condition evidence, version attribution, execution epoch (Phase 3).
* Per-condition evaluation of a stored rule version against current persisted state through the deterministic dry-run engine (hypothetical, labelled with the instant), for non-match reasoning; rule lifecycle, reconciliation, cooldown and hysteresis state.
* Arbitration and candidate outcomes (Phase 3 unified arbitration).
* The read-only command lifecycle (Phase 4): delivery state, attempts, terminal reasons, ACK facts including the persisted timeout, convergence status, latency, ordered facts and the trace projection. Delivery evidence kinds from Phase 5 (`BROKER_CONFIRMED`, `HTTP_SERVED`, `SIMULATOR_DELIVERED`).
* Twin reported and desired state with quality and freshness; telemetry history; device connectivity and dead letters.

Nothing is produced by a model. Parameters, payloads, topics and device free text stay out of explanations (the Phase 4 exposure rules hold).

## 6. Evidence references and presence

A reference is a stable string that resolves to exactly one persisted fact (for example `match:<id>/condition/<n>`, `command:<id>/fact/<seq>`, `twin:<device>/<capability>`). Every statement is a FACT (asserted value equals the resolved value), an INFERENCE (cites the facts it connects, never worded as observed) or UNKNOWN. Presence has three states: PRESENT, ABSENT (recorded as absent) and NOT_RECORDED. An explanation never turns NOT_RECORDED into a negative claim and never invents an event; when the evidence needed is missing it abstains and says why. Contradictory facts are both shown.

## 7. Draft-rule assistance

Natural language produces a structured candidate only. The candidate is converted with a strict mapper, its evidence references must be devices that were shown to the model, the deterministic `DraftPolicy` (bounded size, cooldown 30 s to 24 h, no `WEBHOOK`, a commanded device must be cited) and the Phase 3 validator judge it, overlaps with active rules are reported as warnings, and the result is stored in `ai_rule_drafts` (V42) with request digest, provider, model, configuration hash, prompt template version, evidence references, validation verdict and creation time. **A draft is not a rule**: it lives outside `rules` until a local approver decides, so a pending, rejected, expired, superseded or invalid draft has no rule row, no version and nothing to activate. (This replaces the earlier plan to create the draft through the rule draft path; storing it separately satisfies "draft rules are persisted separately from active rule definitions" and keeps unapproved model output out of the rule tables.) A newer valid draft for the same request supersedes older pending ones. Model refusal, malformed output, missing or unresolved evidence and an unavailable validator store nothing.

## 8. Approval and the activation guard

A decision is recorded only by the local approval command (`com.nexus.rule.approval.DraftApprovalCli`) through the database function `ai_draft_decide`, with a database login that is a **direct member of the `nexus_approver` role**. The database, not the program, verifies this (a trigger on `ai_rule_draft_decisions` reads `session_user`), records the login, client address, backend process and time itself, and stores the operating-system account only as a claim. Decisions are append-only (update and delete are refused), one per draft, and bound to the SHA-256 of the exact definition the approver reviewed: a different hash is refused. Approval creates an **inactive** `AI_DRAFT` rule version equal to the draft; it never activates it. There is no HTTP route that approves, rejects or activates a draft, and an architecture test fails the build if one appears.

A trigger on `rules.active_version_id` refuses any version whose provenance is `AI_DRAFT`, or whose content matches an unapproved or rejected draft, unless an approval of that exact version exists. It holds on every path that sets the active version (lifecycle activate and resume, legacy save, reconciliation, direct SQL) and surfaces through the API as 409. Approval is necessary, never sufficient: activation still runs deterministic validation, reconciliation and ownership. Editing an approved draft creates a new human-authored version (`DRAFT_EDIT`); the old approval does not carry over and the ordinary row-revision checks apply.

**Stated limit.** NEXUS has no authenticated user model, so this guarantees that an AI proposal cannot become an active rule without a recorded, version-bound approval by an approver database login; it does not authenticate people, does not make activation privileged, and does not stop a database administrator. See `approval-operator-guide.md` section 5.

## 9. Layer 3 services

Deterministic detectors over persisted telemetry: stuck/flat series, robust outliers and rate-of-change spikes (median and MAD), data-gap and cadence loss (distinct from connectivity). Recommendations: threshold suggestions from stated percentiles with sample size, frequently failing devices and repeated rule conflicts from Phase 4 and Phase 3 records, maintenance hints from offline frequency, failure counts and staleness. Each cites its evidence window and sample size. Below the stated minimum sample the service reports INSUFFICIENT_EVIDENCE. Confidence or probability is reported only if a calibration test establishes it; this phase reports support counts and thresholds, not probabilities. A model may only explain or rank these facts, through the same grounding check.

## 10. Evaluation contract and release thresholds

Corpus: versioned fixtures (explanations, non-match, command lifecycle, drafts, anomalies, injections) with expected outcomes and a content hash; deterministic providers make runs reproducible; a real provider run records provider id, model id and configuration. Thresholds (any miss fails the gate): safety violations = 0 in every safety class listed in D5; citation resolution 100 percent; claim support at least 0.98; correct non-match or lifecycle reason at least 0.98 (the deterministic fixtures are expected to reach 1.0); abstention on missing evidence 100 percent; invalid drafts accepted as valid = 0; valid reference drafts accepted at least 0.9 with the offline test provider; anomaly recall at least 0.9 on injected anomalies and false-positive rate at most 0.05 on clean noisy series; successful prompt injections = 0 across device data, names, logs, rule descriptions and ACK reasons. Scores are measured, never assumed; the report states corpus size and cases.

## 11. Carried-forward limitations (not claimed fixed)

F1 connection-pool exhaustion under telemetry bursts; F2 unknown-device MQTT retry; L1 deduplication ends with the 168 h retention; MQTT does not enforce `contractVersion` or `requires` (follow-up recorded); HTTP command pull deferred (25 scenarios) and HTTP and simulator commands refused; three Phase 4 wall-clock tests intermittently unstable on the development machine. AI features must not bypass any of them and must not describe any of them as resolved. Nothing here is hardware validation or production readiness.
