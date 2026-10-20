# The NEXUS Assistant (P6.10)

One place to ask about the stored state of the platform. It reads facts and states what they do and do not show. It changes nothing.

## Turn it on

The assistant is off unless `nexus.assistant.enabled=true`. NEXUS has no user authentication, so anyone who can reach the endpoint can read what the assistant reads; enable it only in a local or private environment.

```
nexus.assistant.enabled=true
nexus.assistant.max-lookups-per-question=6      # bounds, see retrieval settings in the contract
nexus.assistant.max-rows=200
nexus.assistant.max-lookback-hours=168
nexus.assistant.statement-timeout-millis=2000
nexus.assistant.max-concurrent=4
nexus.assistant.total-time-millis=8000
```

## Ask

```
POST /api/v1/assistant/ask
{ "question": "What is the status and the latest temperature of Boiler 1?", "conversationId": "optional-8-to-64-chars" }
```

The answer lists `statements` (and `additional` ones), each a FACT (cites evidence references that resolve in `sources`), a LIMIT (what the evidence cannot show) or a NOTICE. `status` is ANSWERED, NEEDS_CLARIFICATION (which device?), REFUSED_WRITE, OUT_OF_SCOPE, NO_EVIDENCE, UNAVAILABLE, DRAFT_STORED or DRAFT_NOT_STORED. `routedBy` and `orderedBy` say whether a model took any part.

## What it can answer

Device state and connectivity, Twin reported and desired state, latest and historical telemetry, trends (the 98, 99, 100 example is described as increasing, not as dangerous), active rules and why a rule matched or did not, commands and their delivery evidence (publication, acknowledgement, timeout and verification kept apart), anomaly observations and suggestions. A follow-up such as "is it online?" uses the device the conversation established and looks the facts up again.

## Drafting a rule from chat (P6.11)

Ask for a draft with the words draft, suggest or propose followed by a rule or automation:

* `Draft a hard limit rule for Boiler 1 temperature at 100 celsius` needs no model. It stores an alert-only draft (limit compared with greater-or-equal) through the existing draft workflow. The device, the capability, the value and the unit must all be in the question; a unit is never assumed.
* `Draft a rule that turns the fan on when it is hot` needs a configured, consented model (see the local model guide); without one nothing is stored and the answer says why.

The answer reports the STORED draft in platform-written sentences: its state (PENDING_APPROVAL or INVALID), the validation codes, the conflict analysis against active rules and exclusion groups with its findings, any warnings, and what happens next. A draft is not a rule. This assistant cannot approve, reject, activate, resume or send anything; the approval is the local command described in the operator guide and activation is a separate step through the normal lifecycle. Requests to create, make, add, approve, activate or enable are refused, and the refusal points to the draft wording.

## What it will not do

* It never changes, controls, sends, approves, activates or deletes anything: those requests are refused, and a read question that also contains such a request is answered as a read with a notice. No code path in the assistant can reach a command, a rule change or an approval; the only thing it can create is a DRAFT, through one file whose imports an architecture test pins.
* It never turns missing data into a physical condition. No reading is "no reading", not "offline" or "healthy".
* It does not guess a device: an unknown or ambiguous name gets a question or a list of candidates.
* A device name, a rule name or stored text that looks like an instruction is data: only names the platform knows are recognised, and what a name says is never read as an instruction.

## The model, if you configure one

Routing is deterministic and offline. Every sentence is written by the platform from stored evidence. When a model is configured, consented to (`nexus.ai.send-device-evidence=true`) and reachable, it may only choose which already-retrieved evidence references come first; its choice is validated (an unknown reference, an abstention, a failure or bad JSON leave the deterministic order) and no text it writes is ever part of an answer. P6.9 measured a local 3B model on the routing and selection contracts and found it unreliable on both, which is why the model is an optional aid and not the source of answers.

## Limits

* Routing is keyword and name based. On the 30 routing questions of the frozen held-out corpus it routed 26 as expected and refused all 8 write or out-of-scope questions; the router and the corpus have the same author, so this is a regression figure and not an estimate for other people's questions. The four misses (an extra telemetry lookup for "active right now" and "current state", "reported" read as the Twin, and a hostile question over-refused as a write) are recorded, not tuned away.
* It answers about devices the platform has registered and cannot answer about energy, causes or the future.
* Conversation state is in memory, bounded (200 conversations, 20 turns, one hour) and holds identities only. A conversation id is a handle, not an identity: there are no users.

## Energy and usage questions (P6.12)

"How much energy did X use?", "Why is usage high?" and "How can we reduce usage?" are answered only from stored power or energy readings
(keys `power`, `active_power`, `power_w`; `energy`, `energy_total`, `energy_kwh`; units W/kW/mW and Wh/kWh).

- Energy from power is an ESTIMATE (trapezoid between samples); gaps are reported, not filled. An energy counter gives a METERED difference; resets are not counted.
- Figures are labelled MEASURED (real device) or SIMULATED, and totals are never added across provenance or kind.
- Without such readings the answer is "cannot be reported" and says a power or current sensor is needed. Nothing is estimated from ratings or averages, and no savings figure is produced.
- Suggestions are advice for a person; a request to switch something off or to "reduce usage by turning things off" is refused as a change.
- The same report is available at `GET /api/v1/intelligence/energy?deviceId=&windowHours=`.
