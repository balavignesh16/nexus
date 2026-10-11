# Twin 2.0 contract — implemented through P2.4

**P2.4/V24 implemented and committed; acceptance matrix and forensic final review complete.** P2.1/V21,
P2.2/V22, and P2.3/V23 are committed. P2.4 sections describe the tested
software contract within the qualification and physical-evidence limits below.
Existing Twin routes and `latest*` summaries remain additive/compatible.

**CURRENT BEHAVIOR:** `CapabilityDirection` has only `READ` and `WRITE`;
reported `digital_twins` uses P2.2 canonical `(deviceId,stateKey)` identity.
P2.1 durably accepts ActionHistory plus current desired state before MQTT
publish. Existing API `SUCCESS` still means publish success.
The legacy `ack_received_at` remains device-supplied. V24 adds a separate
authoritative backend ACK receipt timestamp.

**P2.4 qualification boundary:** normalized mappings explicitly connect a
WRITE binding/subtarget to a distinct enabled READ binding. Template mappings
materialize to device binding IDs at provisioning. The current modes are
`COMMAND_ECHO` (a matching telemetry `readbackCommandId` for the current
desired command) and `NONE` (never qualifies convergence). A GOOD mapped
readback is required; value equality, publication, and ACK alone are never
proof. `IDENTITY`/`OBJECT_FIELD` selectors and `EXACT`/
`NUMERIC_TOLERANCE` comparators are implemented. No generic unit conversion
or implicit READ_WRITE binding is introduced. The existing physical ESP
telemetry has no qualified command echo and stays unverified.

`CommandStatus` is additive and derived from durable publication, ACK, and
deadline facts, with a `Clock`-derived ACK timeout. It does not redefine
`ExecutionStatus.SUCCESS`. The backend preserves ACK payload time separately,
guards duplicate/conflicting ACK updates, and never uses ACK to write reported
state. Verification is durable and independently reconciled, including a
restart-safe pending-deadline scan and status-change-only convergence history.
Newer qualified observations can show drift/recovery; quality decay alone
does not rewrite stored convergence. Desired intent is not auto-cleared.
There is no P2.4 outbox, retry, delivery recovery, public desired-clear
endpoint, or Phase 3 implementation.

## Identity and authority

**INVARIANT:** a bound state has identity `(deviceId, bindingId)`; a desired
subtarget such as RELAY channel 2 adds `(channel, 2)` beneath that binding.
V17 enforces `UNIQUE(device_id, capability_definition_id)`, so two bindings
for one definition cannot currently coexist on a device. Keep
`capabilityDefinitionId` as denormalized provenance in API/history even though
binding ID resolves it, so historical evidence survives definition/binding
changes. `capabilityKey` is semantic identity; display name is never identity.
V17 backfilled legacy DeviceCapability bindings using their IDs. For a truly
unbound legacy row, use `(deviceId, legacy:CapabilityType)` after the existing
alias mapping (`*_SENSOR` → canonical type); retain external wire identifier
separately. Do not merge old and bound state merely because keys match: require
an explicit one-to-one migration mapping.

**P2.2 canonical reported-state correction:** one READ binding currently
resolves both its definition key and every configured external-identifier
value. Because V21 reported persistence is keyed by the raw identifier, one
binding can already have multiple current rows; unbound legacy aliases such as
`TEMPERATURE` and `TEMPERATURE_SENSOR` can do the same. V22 therefore makes
reported current-state identity authoritative too: `(deviceId,bindingId)` or
`(deviceId,canonicalLegacyType)`. Add non-null `state_key` plus nullable
`legacy_capability_type`, enforce binding-vs-legacy XOR and primary-key
uniqueness on `(device_id,state_key)`, and retain the configured canonical
telemetry key and last raw wire identifier as provenance. The old
`reportedState` object shape and external-identifier keys remain compatible;
they are projections of one canonical state, never separate authorities.

Before enabling the constraint, V22 groups existing rows by canonical state
identity. It selects the current authoritative row by `latest_timestamp DESC`,
then `last_seen DESC`, then lexical `latest_sensor_type` solely as a
deterministic tie-breaker. It never uses display name. Every non-winning alias
row is copied verbatim to a no-retention reconciliation-audit table with the
chosen state key/reason before removal from current-state storage. No row is
silently discarded and no synthetic state transition is invented.

**P2.4 BEHAVIOR:** a WRITE binding may have a separately defined READ
binding as readback. This is a validated explicit mapping, including subtarget
selector and comparable unit/value type, not an assumed `READ_WRITE` direction
or same-key lookup. Without mapping, convergence is UNVERIFIED. One definition
cannot serve as both READ and WRITE under today's direction model.

Database is the durable authority; cache is acceleration. Current-state and
transition writes commit together. Only after commit may the stable-identity
cache projection be updated, followed by Twin realtime, Twin/domain events,
and Rule evaluation. If projection fails, evict that state key and use
read-through reconstruction from PostgreSQL; log/measure the failure and do
not roll back durable state. Later realtime/event/Rule failures are logged,
do not roll back, and receive no automatic retry/outbox in P2.2. Restart reloads
reported and desired values, command IDs, receipt times, binding policies,
and pending deadlines, then recomputes quality/effective/convergence. Effective state is
**never independently writable**. Current `reportedState` map keyed by
external identifier and existing `latest*` summary remain for old clients;
new stable `stateId` is added inside each capability state.

## State definitions and admission

`reportedState` is the latest accepted device-originating observation.
Command issuance and ACK **never** mutate it. `desiredState` is latest
validated, durably accepted NEXUS intent for a writable target. Mere HTTP
request, failed validation, or a rejected action does not establish intent.
Manual and rule commands use one target resolution, validation, acceptance,
and desired-state path; `source` records MANUAL_COMMAND or RULE_ACTION.
**P2.1 acceptance boundary:** in one DB transaction, insert a command-linked
ActionHistory record in a new explicit `ACCEPTED_FOR_DISPATCH` state and write
the current desired intent. Only after commit may MQTT publish be attempted.
On transaction rollback neither row/transition survives. Publish success
updates execution to existing `SUCCESS`; publish failure updates it to
`FAILURE` while retaining desired intent as intended-but-undelivered. Existing
API `SUCCESS` must keep its current meaning (publish success). The transaction
does **not** guarantee delivery after a process crash between commit/publish;
that state remains `ACCEPTED_FOR_DISPATCH`/delivery unknown until Phase 4
outbox/recovery work. No claim of reliable queueing is made in P2.1.

Every newly accepted command is a new desired intent. It creates
`DESIRED_STATE_CHANGED` even when its typed target equals the prior target,
because `commandId`, `acceptedForDispatchAt`, and source/provenance are part of
authoritative desired intent. This is intentionally different from a newer
same-value reported observation, which refreshes timing but creates no
`REPORTED_STATE_CHANGED` transition.

`effectiveState` is a derived **structured resolution**, not a third truth.
It exposes `value` and `source` (`REPORTED`, `DESIRED_UNVERIFIED`, or `NONE`),
plus both `observedValue` and `intentValue`, `stateQuality`, and separate
`verification`. When reported and desired disagree, `value` is the observed
value, never the desired one silently. READ-only cannot have desired state and
uses `verification=NOT_APPLICABLE`. WRITE-only after ACK with no readback may
display desired intent only with `source=DESIRED_UNVERIFIED`; it is never a
physical observation. Missing fields are JSON `null`, not fabricated zero/false.
In examples, `effective.stateQuality` is the categorical value; top-level
`stateQuality` may also carry `{state,evaluatedAt,thresholdSeconds}` evidence.

| Reported | Desired | `effective.value` / source | `stateQuality` | Verification |
|---|---|---|---|---|
| absent | absent | null / NONE | UNKNOWN | NOT_APPLICABLE |
| present READ-only | absent | reported / REPORTED | derived | NOT_APPLICABLE |
| present writable | absent | reported / REPORTED | derived | NO_DESIRED_STATE |
| absent | present | desired / DESIRED_UNVERIFIED | UNKNOWN | PENDING, then UNVERIFIED after accepted ACK if no readback |
| present, equal | present | reported / REPORTED | derived | CONVERGED only with qualifying readback; otherwise PENDING/UNVERIFIED |
| present, unequal | present | reported / REPORTED | derived | PENDING until deadline, then DIVERGED |
| stale/offline report | present | last reported / REPORTED, marked non-GOOD | STALE/OFFLINE | cannot newly CONVERGE |

## Time, telemetry, and transition rules

`eventTime` is device/source-asserted time; `receivedAt` is backend arrival;
`transitionedAt` is the injected-backend-clock timestamp at which the
authoritative transition row is created/recorded inside its transaction. It is
not the PostgreSQL commit timestamp; the row becomes durable only if that
transaction commits. Desired records also contain
`requestedAt` and `acceptedForDispatchAt`; command evidence may contain
`publishedAt` and backend `acknowledgedAt`; readback contains its own
`readbackEventTime`/`readbackReceivedAt`. Device ACK payload timestamp is
untrusted for backend latency; retain it separately. A transition's
`correlationId` is the ingest/request chain ID; `commandId` is
`ActionHistory.requestId` for accepted command intent. A later readback with
no proven command link has `readbackAttribution=UNATTRIBUTED`; its transition
retains its **own** telemetry correlation ID, never a fabricated command ID.
Current `TelemetryRequest.timestamp` is required and current Twin ordering
uses it directly. **P2 target:** malformed/missing eventTime is rejected, not
replaced by receivedAt; an eventTime more than a configurable 5-minute future
skew beyond receivedAt is rejected/diagnosed so it cannot freeze the Twin.
Operational freshness uses receivedAt. Cross-device clocks are never used
alone to infer causality. A later readback can establish **current observed
equality** without proving the command caused it; record attribution
separately. A pre-intent buffered readback without a trustworthy source
sequence/correlation must not establish convergence merely because it arrived
late: it remains UNVERIFIED pending new observation. Without device sequence or
command echo, the post-intent criterion is a new observation cycle explicitly
requested after acceptance and received by the backend after that request;
an arbitrary late arrival cannot qualify. This establishes current equality,
not command causation.

| Telemetry category | Raw telemetry | Reported Twin | P2.2 history | Rule effect |
|---|---|---|---|---|
| Valid newer, typed value/unit/semantic metadata changed | retain | replace | `REPORTED_STATE_CHANGED` | normal observed-state evaluation |
| Valid newer, same typed value/unit/metadata | retain | refresh event/receipt time | none; observation only | existing event behavior retained until explicit rule change |
| Exact replay ID (if supplied later) | currently may retain; Phase 4 inbox dedupe | no second state update | none | no new Twin event |
| Same eventTime, different receipt | current raw path may retain | none | none | no new Twin event |
| Older eventTime for same stateId | current raw path may retain; diagnose OUT_OF_ORDER | none | none | no new Twin event |
| Invalid/missing time or invalid value | reject/diagnose | none | none | none |
| Valid typed value with eventTime beyond configured future skew | retain and diagnose | none | none | none |
| Unknown capability | reject/diagnose | none | none | none |

P2.2 persists `REPORTED_STATE_CHANGED`, `DESIRED_STATE_CHANGED`, and
`DESIRED_STATE_CLEARED`. P2.4 adds `CONVERGENCE_CHANGED` only when a
real verification state changes, not every poll. `QUALITY_CHANGED` is not a
durable Twin transition: quality is time/connectivity-derived and can change
without new input; diagnostics may track it separately. Controlled sources:
`TELEMETRY`, `DEVICE_READBACK`, `MANUAL_COMMAND`, `RULE_ACTION`, `SYSTEM`;
`ONBOARDING` is used only if it actually writes first reported state, otherwise
TELEMETRY. Never a free-form UI label. A transition has `transitionId`,
device/state identity, type/source, typed previous/new value and unit,
applicable times, `correlationId`, and optional proven `commandId`.
Normal current telemetry uses `TELEMETRY`; `DEVICE_READBACK` is never inferred
from value equality and is not emitted until P2.4 supplies an explicit mapping.

Raw `TelemetryReceived` realtime and successful TELEMETRY pipeline traces mean
accepted/persisted telemetry, not an ingestion attempt: invalid attempts emit
neither today. P2.2 therefore moves both after successful commit as well. A
post-commit publication/trace failure is logged and does not roll back or retry
the raw/current/history transaction.

Proposed **P2.2-only** API:
`GET /api/v1/devices/{deviceId}/twin/history?bindingId=<uuid>&legacyKey=<key>&transitionType=<type>&from=<instant>&to=<instant>&limit=50&cursor=<opaque>`.
At most one capability selector; stable order `(transitionedAt DESC,
transitionId DESC)` and opaque cursor for keyset pagination; max limit 200.
The cursor contains version, recorded `transitionedAt`, transition UUID, and a
canonical filter fingerprint; it is bounded, URL-safe base64 without padding,
and unsigned. Malformed or filter-mismatched cursors return the existing
structured 400 response. `transitionId` is only the deterministic tie-breaker
when recorded timestamps tie.
Response: `{ "items": [transition], "nextCursor": null }`. Indexes follow
the actual queries: `(device_id, transitioned_at DESC, transition_id DESC)`,
`(device_id, binding_id, transitioned_at DESC, transition_id DESC)` and a
`command_id` index only if command filtering is implemented. Authorization
follows existing API policy; retention must not erase audit evidence needed
for current state explanation. Existing routes are unchanged. No WebSocket
topic is proposed in P2.0.

## Quality and freshness (P2.3)

**P2 TARGET BEHAVIOR:** add `stateQuality` with only GOOD/STALE/OFFLINE/UNKNOWN.
Keep existing `quality` fields and their fixed 30/120-second **age-only**
meaning unchanged for `/api/v1` compatibility; do not silently reinterpret
them. `ingestionHealth`/last rejection is a separate diagnostic, never
`stateQuality=INVALID` merely because a candidate was rejected. If a stored
value is discovered invalid under a changed definition, quarantine/diagnose
it and expose UNKNOWN until repaired; it must not drive rules.

Freshness threshold precedence: binding override → template capability
default → system default (including unbound legacy rows). Null means inherit;
zero/negative is invalid. An instance override may only shorten (or equal)
the parent threshold. Quality is evaluated at a supplied backend clock time
from last **valid receivedAt**, not device eventTime. This new receipt field
must be captured at ingress; current `lastSeen` is the Twin write time and
is retained as a compatibility field.

Template freshness is immutable template-version configuration once any device
references that template, matching the existing composition lock. Referenced
template capabilities cannot change freshness. Existing devices keep their
exact parent policy; their binding override may be tightened, removed to relax
back to that parent, or otherwise changed only within the parent ceiling and
existing active-Rule protections. Changing the parent for future devices
requires a new template identity representing a new immutable revision; the
current code has no in-place fork/version operation for referenced templates,
and no generic composition editor is introduced. An unreferenced template may
change freshness through the existing
capability update API. The UI exposes only a narrow freshness control for an
unreferenced capability and renders referenced parent policy read-only.

| Reported value | Connectivity | Freshness | `stateQuality` |
|---|---|---|---|
| absent | any | any | UNKNOWN |
| present | OFFLINE | any | OFFLINE |
| present | UNKNOWN | any | UNKNOWN |
| present | ONLINE | age within threshold | GOOD |
| present | ONLINE | age over threshold | STALE |
| invalid persisted row | any | any | UNKNOWN + data-integrity diagnostic |

Invalid incoming candidates do not poison the last valid reported value;
their ingestion diagnostics remain separate. OFFLINE and STALE are distinct.
P2.3 rule conditions based on physical observation continue reading
**reported**, never desired/effective state. They fail closed on non-GOOD
input by default; any future exception needs explicit authorization and
separate review. Legacy and generic rule fixtures must cover this intentional
P2.3 behavior change. No fabricated quality percentage is introduced.

## Command, ACK, readback, and supersession (P2.4)

**Separate axes:** `commandStatus` is ACCEPTED_FOR_DISPATCH, PUBLISHED,
ACKNOWLEDGED, REJECTED, FAILED, or TIMED_OUT. `verification` is
NOT_APPLICABLE, NO_DESIRED_STATE, PENDING, CONVERGED, DIVERGED, TIMED_OUT,
or UNVERIFIED. `SUPERSEDED` is historical intent disposition, not the current
verification state. `ACKNOWLEDGED` is never a verification value.

| Command/readback situation | Desired-state effect | Command status | Verification |
|---|---|---|---|
| Validation fails or transaction rolls back | unchanged | none | unchanged |
| Acceptance transaction commits | set current desired | ACCEPTED_FOR_DISPATCH | PENDING |
| Publish succeeds, no ACK yet | retain | PUBLISHED | PENDING |
| Publish fails | retain intended-but-undelivered value | FAILED | UNVERIFIED |
| ACK REJECTED | retain until explicit clear/supersession | REJECTED | UNVERIFIED |
| ACK ACCEPTED, no readback mapping | retain | ACKNOWLEDGED | UNVERIFIED |
| ACK ACCEPTED, readback expected | retain | ACKNOWLEDGED | PENDING |
| Qualified matching readback | retain | unchanged | CONVERGED (current equality, not proof of command causation) |
| Qualified mismatch before deadline | retain | unchanged | PENDING, discrepancy visible |
| Qualified mismatch at deadline | retain | unchanged | DIVERGED |
| No qualified readback by deadline | retain | TIMED_OUT if ACK missing, otherwise ACKNOWLEDGED | TIMED_OUT |
| New accepted command | replace current; old intent remains historical | independent per-command | new PENDING; old SUPERSEDED |

ACKNOWLEDGED means only that the simulated/device software accepted the
command; it proves neither readback nor physical actuation. For NUMBER,
definition/binding supplies an explicit nonnegative absolute tolerance in
the same normalized unit (default exact BigDecimal comparison); BOOLEAN and
ENUM use exact typed/member equality, STRING exact equality unless the
definition explicitly declares normalization. OBJECT has no generic JSON
comparator: without a declared capability-specific mapping it is UNVERIFIED.
RELAY `{channel,state}` is a command object, not whole-device readback.
Readback must map explicitly to the WRITE binding/subtarget, be valid/GOOD,
and meet the post-intent observation criterion above (source sequence/command
echo where available; otherwise an explicit new observation cycle).
Unattributed equality may establish current-state match
but does not assign command causation. No new CONVERGED state is asserted
from stale/offline data. A separate positive convergence timeout follows
binding override → template default → system default and is **not** the
freshness threshold. Deadline is based on acceptedForDispatchAt and persisted.
Duplicate/late ACK updates only its own command; it never rewrites current
desired state or resurrects a superseded intent. Late readback updates
reported state if valid/newer, then evaluates only the **current** desired
target; it never inherits an older command ID. Current desired remains after
convergence to detect later drift, and is cleared only by explicit authorized
clear/decommission/binding removal (which leaves history).

## JSON examples (proposed additive fields; timestamps are illustrative UTC)

All examples use device `11111111-1111-4111-8111-111111111111`, WRITE
binding `22222222-2222-4222-8222-222222222222`, READ binding
`33333333-3333-4333-8333-333333333333`, and illustrative UTC times.
`stateId` is a proposed stable string encoding of the pair, not a current
database column. Omitted legacy `latest*` fields remain present in actual
additive responses. Example fragments show only fields relevant to the case.

| Case | Representative JSON fragment |
|---|---|
| A. READ temperature | `{"stateId":"binding:33333333-3333-4333-8333-333333333333","capabilityKey":"temperature","reported":{"value":23.5,"valueType":"NUMBER","unit":"CELSIUS","eventTime":"2026-10-03T09:00:00Z","receivedAt":"2026-10-03T09:00:01Z"},"desired":null,"effective":{"value":23.5,"source":"REPORTED","verification":"NOT_APPLICABLE","stateQuality":"GOOD"},"correlationId":"tel-1"}` |
| B. WRITE LED ACK/no readback | `{"stateId":"binding:22222222-2222-4222-8222-222222222222","reported":null,"desired":{"value":true,"commandId":"44444444-4444-4444-8444-444444444444","source":"MANUAL_COMMAND","requestedAt":"2026-10-03T09:00:00Z","acceptedForDispatchAt":"2026-10-03T09:00:01Z"},"commandStatus":"ACKNOWLEDGED","acknowledgedAt":"2026-10-03T09:00:02Z","effective":{"value":true,"source":"DESIRED_UNVERIFIED","verification":"UNVERIFIED","stateQuality":"UNKNOWN"}}` |
| C. Paired READ/WRITE converged | `{"stateId":"binding:22222222-2222-4222-8222-222222222222","readbackBindingId":"33333333-3333-4333-8333-333333333333","desired":{"value":true,"commandId":"44444444-4444-4444-8444-444444444444","acceptedForDispatchAt":"2026-10-03T09:00:01Z"},"reported":{"value":true,"eventTime":"2026-10-03T09:00:03Z","receivedAt":"2026-10-03T09:00:04Z"},"readbackEvidence":"POST_INTENT_OBSERVATION_CYCLE","readbackAttribution":"UNATTRIBUTED","effective":{"value":true,"source":"REPORTED","verification":"CONVERGED","stateQuality":"GOOD"}}` |
| D. Mismatch before deadline | `{"stateId":"binding:22222222-2222-4222-8222-222222222222","desired":{"value":true,"acceptedForDispatchAt":"2026-10-03T09:00:01Z"},"reported":{"value":false,"receivedAt":"2026-10-03T09:00:04Z"},"deadlineAt":"2026-10-03T09:00:11Z","effective":{"value":false,"source":"REPORTED","verification":"PENDING"}}` |
| E. Stale | `{"stateId":"binding:33333333-3333-4333-8333-333333333333","reported":{"value":23.5,"receivedAt":"2026-10-03T08:58:00Z"},"stateQuality":{"state":"STALE","evaluatedAt":"2026-10-03T09:00:00Z","thresholdSeconds":30},"effective":{"value":23.5,"source":"REPORTED","verification":"NOT_APPLICABLE"}}` |
| F. Offline | `{"stateId":"binding:33333333-3333-4333-8333-333333333333","reported":{"value":23.5,"receivedAt":"2026-10-03T08:59:59Z"},"connectivity":"OFFLINE","stateQuality":{"state":"OFFLINE","evaluatedAt":"2026-10-03T09:00:00Z"}}` |
| G. Older telemetry diagnostic | `{"stateId":"binding:33333333-3333-4333-8333-333333333333","candidate":{"value":20,"eventTime":"2026-10-03T08:59:00Z","receivedAt":"2026-10-03T09:00:05Z"},"currentEventTime":"2026-10-03T09:00:00Z","disposition":"OUT_OF_ORDER","twinChanged":false,"transitionId":null}` |
| H. Rejected input diagnostic | `{"stateId":"binding:33333333-3333-4333-8333-333333333333","candidate":{"value":9999,"eventTime":"2026-10-03T09:00:00Z","receivedAt":"2026-10-03T09:00:01Z"},"disposition":"INVALID","twinChanged":false,"transitionId":null}` |
| I. Superseded intent | `{"stateId":"binding:22222222-2222-4222-8222-222222222222","previousDesired":{"value":true,"commandId":"44444444-4444-4444-8444-444444444444","acceptedForDispatchAt":"2026-10-03T09:00:00Z","disposition":"SUPERSEDED"},"desired":{"value":false,"commandId":"55555555-5555-4555-8555-555555555555","acceptedForDispatchAt":"2026-10-03T09:00:02Z"},"effective":{"verification":"PENDING"}}` |
| J. Late ACK for I | `{"ack":{"commandId":"44444444-4444-4444-8444-444444444444","status":"ACCEPTED","acknowledgedAt":"2026-10-03T09:00:04Z"},"currentDesiredCommandId":"55555555-5555-4555-8555-555555555555","currentDesiredChanged":false,"verification":"PENDING"}` |
| K. Legacy fallback | `{"deviceId":"11111111-1111-4111-8111-111111111111","stateId":"legacy:TEMPERATURE","legacySensorType":"TEMPERATURE_SENSOR","bindingId":null,"capabilityKey":"temperature","reported":{"value":23.5,"eventTime":"2026-10-03T09:00:00Z","receivedAt":"2026-10-03T09:00:01Z"},"correlationId":"tel-legacy-1"}` |

## Compatibility review gates

**COMPATIBILITY REQUIREMENT:** stay under `/api/v1` for additive fields and
the planned history subresource; do not create `/api/v2` without a breaking
change. Existing Twin routes, `latest*` values/timestamps/units, current
`quality` age-only fields, `reportedState` keys, and `/topic/twins` payloads
retain their meanings. New state fields are additive; old frontend views,
Explorer, realtime invalidation, Rules, and Actions continue to work.

Legacy ESP topics/payloads and numeric 1/0 boolean telemetry remain valid.
Generic definition/binding IDs and typed values are first-class; a new key
never requires `CapabilityType`. Onboarding admission remains authoritative.
Observed-value Rule conditions continue reading **reported Twin state**;
command intent must never satisfy an observation rule. P2.3 quality gating is
an explicit tested change, not an accidental switch to effective state.
RuleMatch evidence and existing pipeline trace stages are retained; do not
invent TWIN/COMMAND/CONVERGENCE trace rows that are not persisted. AI
explanations must distinguish reported fact, desired intent, ACK fact, and
inferred current-state match; no explanation may call ACK physical proof.

**DEFERRED:** Phase 4 outbox/retry/idempotent delivery, physical verification,
new WebSocket topics, and a generic READ_WRITE direction. These are not
assumed by P2.1–P2.4. There is no blocking OPEN QUESTION in this contract;
implementation must follow the explicit boundaries above and user approval.
