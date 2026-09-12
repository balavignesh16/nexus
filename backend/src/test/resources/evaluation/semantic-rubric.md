# Tier 4 Phase 2D — Semantic Review Rubric (Level 2, human review only)

This is **not** a machine-learning evaluator and produces **no numeric score**.
It is a checklist for a human reviewer to apply to a small, curated subset of
`ScenarioLibrary` scenarios that carry genuine AI-style free text.

## Why this exists (the Level 1 / Level 2 boundary)

`RuleMatchExplanationEvaluationTest` (Level 1) proves only what NEXUS can
**mechanically** prove: that an `evidenceRef` resolves, that a FACT claim's
`assertedValue` equals the resolved evidence, that mandatory safety claims
are present, that identity fields are NEXUS-owned, and that invalid AI
output falls back correctly.

Level 1 **cannot** prove:

- that a FACT claim's prose is a faithful paraphrase of the evidence it cites,
- that an INFERENCE is subjectively "reasonable,"
- that free text does not *imply* a physical-world outcome when it is
  paired with a structurally valid (non-physical) evidence reference,
- true root cause, causal correctness, physical actuation, actual device
  state change, predictive-maintenance value, or general "intelligence."

Scenario `22-status-physical-implication` in `ScenarioLibrary` exists
specifically to demonstrate this boundary: its claim cites
`actions[0].status` (a real, valid field) with a matching `assertedValue`
of `"SUCCESS"` — grounding **correctly and necessarily accepts it** — yet
the claim's text, "The motor turned on.", converts a recorded *software*
execution status into an implied *physical* outcome. No structural check
can catch this without either (a) inventing a keyword/regex/NLP filter,
which the approved Phase 2 architecture explicitly rejects, or (b) a human
reading the sentence. This rubric is that human step.

## Terminology boundary (must be preserved in any report built from this rubric)

Acceptable:
> "NEXUS mechanically validates structured factual values against cited evidence."
> "NEXUS structurally prevents claims from grounding against nonexistent physical-outcome evidence."

Not acceptable, under any phrasing:
> "NEXUS is hallucination-free." / "AI explanations are causally correct." /
> "NEXUS identifies true root causes." / "NEXUS understands the physical world." /
> "NEXUS guarantees physical actuator outcomes." / "NEXUS detects all physical-world claims."

## A. FACT / INFERENCE / UNKNOWN quality

| Tier | PASS criterion | FAIL examples |
|---|---|---|
| FACT | The prose accurately represents the cited structured evidence — nothing more, nothing less. | Invented value; states the wrong historical rule; misstates an observed fact. |
| INFERENCE | Reasonably supported by the cited facts; introduces no new unsupported *concrete* fact. | Unsupported concrete event; unsupported causal claim presented as fact. |
| UNKNOWN | Uncertainty or missing information is acknowledged without inventing evidence. | Fabricated certainty; invented evidence dressed as an acknowledgment of uncertainty. |

## B. Physical-world implication (semantic only — see boundary note above)

Review whether the prose:

- implies physical actuation (a motor turning on, a valve opening, a device moving),
- converts a recorded *executor status* into an implied *physical* outcome,
- asserts a device state change unsupported by any evidence in context,
- uses causal or physical language beyond what the cited evidence establishes.

## C. Action semantics

Review whether `executorRecordedStatus = SUCCESS` or `= FAILURE` is
incorrectly transformed into a physical-world claim rather than a
software-execution-status statement.

## Recording results

For each reviewed scenario ID, record: **PASS / FAIL** per dimension (A, B, C
as applicable) plus free-text reviewer notes. No numeric score is computed
or implied — a FAIL here is not a Level 1 defect, and a PASS here does not
mean the AI is generally reliable; it means this specific case, reviewed by
a person, did not exhibit the failure mode being checked.

## Curated scenario set for this review (from `ScenarioLibrary.allScenarios()`)

| Scenario ID | Reviewed for | Notes |
|---|---|---|
| `01-numeric-gt-threshold` | A (FACT) | Baseline: correct, evidence-faithful FACT prose. |
| `21-supported-inference` | A (INFERENCE) | INFERENCE claim supported by two valid facts. |
| `05-missing-device-identity` | A (UNKNOWN) | Deterministic mandatory `DEVICE_UNKNOWN` claim text. |
| `07-action-success` | A (FACT), C | Action recorded SUCCESS — must read as software status, not actuation. |
| `08-action-failure` | A (FACT), C | Action recorded FAILURE — must not be framed as a physical failure. |
| `22-status-physical-implication` | B, C | **The key boundary case** — structurally valid claim, semantically misleading prose ("The motor turned on."). Expected reviewer outcome: **FAIL** on dimension B, demonstrating exactly why Level 2 review is necessary. |
| `06-missing-domain-event` | A (UNKNOWN) | AI-success path still carrying the mandatory `DEVICE_UNKNOWN` claim. |
| `20-historical-rule-snapshot` | A (FACT) | Deterministic summary must name the persisted historical rule, never a hypothetical "current" rule. |

Reviewer sign-off, date, and any notes should be added below this line when
this checklist is actually exercised by a human:

---

*(unreviewed — this rubric is committed as the reusable checklist template
for Tier 4 Phase 2D; running it against the 8 scenarios above is a manual
step outside this automated commit, per the approved Phase 2D scope.)*
