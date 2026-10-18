# Phase 5 device adapter and SDK contract

Date: 2026-10-18.

Status: **P5.0 AMENDED (second revision, 2026-10-18). DOCUMENTATION AND PLANNING ONLY. NO PRODUCTION CODE, NO MIGRATION, NO SCHEMA FILE AND NO SDK EXISTS YET.** Decisions D1 to D6 were approved by the project owner on 2026-10-18 and D7 to D10 plus a contract-version refinement were settled in the same review (wording in section 2; the corrections they required are marked *Amendment* in the sections they change). Production implementation (P5.1 onward) needs a separate authorisation after the 2026-10-20 review and the integration of the Phase 4 branches into main.

Baseline: branch `feature/p4-final`, commit `e29e215e5138de4460e9891e05859a61a098c3aa` (ancestry verified: `e29e215` > `b13acb9` > `e6d95e8` > `4b73289` > `3efbefa` > `e2c77ec`, the head of `main`). Latest migration V40; V41 is provisionally reserved for Phase 5 (section 12) and is **not created** by P5.0.

Document ownership:

- This contract owns semantics, invariants, interfaces, limits and scope boundaries of Phase 5.
- [Implementation ticket](p5-implementation-ticket.md) owns slices, dependencies, rollout, estimates and the risk register.
- [Acceptance ledger](p5-acceptance-ledger.md) owns scenario IDs, required evidence and statuses. A planned test is not proof; every scenario starts PLANNED.
- The Phase 4 contract ([reliability-contract.md](../phase4/reliability-contract.md)) and ledger are **preserved unchanged**. Where Phase 5 needs a wider statement than Phase 4 made, this contract states an *extension* (section 11) and never rewrites an accepted Phase 4 guarantee.
- Current committed source and schemas remain the authority for what exists today. ROADMAP Phase 5 provides the goal and exit criteria; it is not edited here.

## 1. Goal, exit criteria and non-goals

Goal (ROADMAP): support additional protocols without changing the Twin and Rule engines.

Exit criteria, restated as testable clauses:

1. **E1.** A new transport adapter is added without modifying the Twin or Rule engines (proved by dependency architecture tests and by a deliberately trivial test adapter, section 14).
2. **E2.** A firmware developer can validate payload compatibility locally, with no backend running (a command-line validator that uses the same authoritative schemas as the backend tests, section 8).
3. **E3.** Contract versions support backward-compatible evolution under an explicit, finite support window (section 6).

Non-goals (stated so they are not mistaken for omissions): Modbus, BLE gateway, OPC UA, serial, CAN and vendor-cloud adapters (deferred, D5); platform-wide authentication or authorisation (only device-adapter authentication is in scope, D2); changing `firmware/**`, `hardware-tests/**` or `results/**`; changing the Phase 4 delivery semantics for MQTT; physical-hardware validation of the ESP32 library; production load or soak claims.

## 2. Approved decisions, exact wording

Each decision is recorded as approved by the project owner, followed by the operational wording this contract adopts. Where the owner's wording asks for a design that does not yet exist, the clause says so.

**D1. ESP32 SDK: APPROVED.** Create `sdk/esp32/`. `firmware/` and the existing hardware-test evidence stay immutable. Prefer a small reusable PlatformIO-compatible library with Arduino compatibility, with sample sketches inside the SDK. Host-side contract and compilation tests are required. No claim of physical hardware validation without actual flashing and device testing. The library supports contract-compatible telemetry creation, stable message IDs, command validation and ACK construction.

**D2. HTTP adapter authentication: APPROVED WITH CONDITIONS.** Per-device ingestion credentials with securely hashed server-side keys. Requirements: HTTPS in real deployments; credentials never stored or logged in plaintext after creation; constant-time verification where applicable; credential rotation and revocation behaviour; bounded request size; per-device request authorisation; rejection of cross-device impersonation; no credentials in URLs; negative tests for missing, invalid and revoked credentials. Narrowly scoped to device-adapter authentication; the platform as a whole is not claimed to be authenticated or secure. If HMAC signing is introduced later it needs a separate secure secret-management design, because a one-way hash of an API key cannot be the recoverable HMAC signing secret. V41 is provisionally reserved and is not created in P5.0.

**D3. Adapter selection: APPROVED.** A server-controlled device or template transport assignment. Default `MQTT_JSON`. Existing devices keep their behaviour when no explicit assignment exists. An incoming payload can never choose a different adapter to bypass device authorisation or admission policy. SIMULATED operating mode stays distinct from transport identity. Tests: mixed fleets, legacy devices, unsupported adapter types.

**D4. Simulator freshness: APPROVED.** Correct the simulator's 300-second command freshness window to the firmware's 30-second rule, using the existing command timestamp contract and injected clocks for deterministic boundary tests, accounting for realistic backend and device clock skew without silently relaxing the firmware safety window. Firmware is not modified. No production change in P5.0. *Finding recorded while preparing this contract:* the firmware compares the **absolute** difference (`age < 0` is negated, so a command timestamped in the future by more than 30 s is also STALE), whereas the simulator's handler only rejects `now - timestamp > window` and therefore accepts any future-dated command. The correction must therefore change both the window (30 s) and the direction (absolute difference), or the simulator would still be more permissive than the firmware.

**D5. Initial adapters: APPROVED.** MQTT JSON, HTTP/webhook, Simulator. Deferred: Modbus, BLE, OPC UA, serial, CAN and vendor clouds. An additional minimal test adapter may prove extensibility but is never represented as a production-supported transport. HTTP command delivery needs a dedicated safety design (section 10) before any pull or webhook delivery is implemented; if it cannot fit the Phase 4 contract safely, the necessary contract extension is reported and the omission is not disguised as conformance.

**D6. Contract versioning: APPROVED WITH REFINEMENT.** Explicit versioned wire contracts and JSON Schemas; byte-compatible interpretation of existing firmware payloads; legacy unversioned messages accepted under an explicit legacy-v1 policy; new producers emit `contractVersion`; additive optional fields are compatible minor changes; breaking changes need a new major version; explicit version resolution and unsupported-version behaviour; never silently reinterpret an unsupported major; a finite support window listing the actual supported versions; golden fixtures prove compatibility; the CLI validator uses the same authoritative schemas as the backend contract tests. No ambiguous "N/N-1": section 6 defines whether N is a major or a minor.

**D7. Credential administration: APPROVED.** A local administrative CLI creates, rotates and revokes per-device credentials. There is no unauthenticated or publicly reachable credential-management REST endpoint, and the CLI must not create a hidden unauthenticated administrative HTTP interface. Documented: OS and database privilege requirements, secure high-entropy generation, hashed verification, one-time secret display, revocation, audit events and credential handling (section 9.8).

**D8. HTTP command pull: CONDITIONALLY APPROVED.** HTTP command pull remains a Phase 5 objective. Its API is an authenticated **POST** (never a state-changing GET). Before any outbound delivery is implemented the design must: (1) specify the exact durable outbox claim and delivery-state transitions; (2) distinguish an HTTP response from MQTT broker publication; (3) not assume that writing a response proves receipt; (4) determine what delivery progress the HTTP framework can actually observe; (5) preserve NOT_SENT-only retries and the uncertainty barrier; (6) preserve ordering, deadlines, fencing and ACK correlation; (7) define process-kill behaviour before the claim, after the claim, during response writing and before the ACK; (8) specify how a polling retry avoids duplicate unsafe delivery; (9) define backward-compatible delivery evidence that does not mislead Phase 4 clients; (10) verify whether the implementation needs a schema extension. `HTTP_SERVED` is never synonymous with broker publication or physical execution. If the Phase 4 safety contract cannot be met, HTTP stays ingest-only and the outbound scenarios are explicitly deferred. Section 10 now answers each of the ten points and names the spikes that must confirm them (section 20).

**D9. HTTP command eligibility: APPROVED WITH REFINEMENT.** Eligibility comes from a recent, authenticated, valid device contact measured on backend-authoritative time, with a configurable initial window of 60 seconds to be finalised against the documented polling cadence. It is an eligibility signal, not proof of current connectivity. Invalid or revoked credentials, malformed requests and inactive devices never establish availability. The rule is implemented through the adapter/core boundary without changing Twin or Rule Engine responsibilities (section 10.8).

**D10. Memory-safe complete validation: APPROVED.** Bounded sequential backend test segments with exhaustive discovery and reporting; every required test executes; no failed or missing segment can pass the official full gate; existing resource limits are kept unless a separate measured justification is approved; the additional total execution time is recorded transparently (section 21).

**Contract-version refinement (owner).** When a newer minor version is received, known fields are validated strictly, unknown optional fields may be ignored, and unsupported semantics must not be silently accepted; legacy unversioned firmware stays compatible with the existing v1 rules (section 6.1).

**Critical architecture requirement (owner):** do not build a monolithic adapter interface whose methods are meaningless for some transports; design around transport capabilities with a common adapter contract; keep authorisation, deduplication, command acceptance, retries, ACK truthfulness, verification and rule execution in the core; an adapter never modifies Twin or Rule Engine state; provide a dependency diagram and architecture tests. Sections 3 to 5.

## 3. Architecture

```text
                        +-------------------------------------------------------------+
   devices / gateways   |                          CORE (unchanged owners)              |
                        |                                                             |
 +----------------+     |  TelemetryService --> Twin, Rules, dedupe inbox (V40)         |
 | MQTT JSON      |---->|  DeviceCommandAckMessageHandler / CommandAckFacts            |
 | adapter        |     |  DeviceConnectivityRegistry                                 |
 +----------------+     |  CommandAcceptanceService --> command_outbox (V37)           |
 +----------------+     |  CommandOutboxDispatcher --claim/lease/retry/expire          |
 | HTTP/webhook   |---->|        |                                                    |
 | adapter        |     |        v  CommandEgressRouter  (picks the adapter by the     |
 +----------------+     |        |   device's server-side transport assignment)        |
 +----------------+     |        v                                                    |
 | Simulator      |<----|  CommandEgress.deliver(envelope) -> Attempt                  |
 | adapter        |     |   CONFIRMED(evidenceKind) | NOT_SENT(code) | UNCERTAIN(code) |
 +----------------+     +-------------------------------------------------------------+
        ^   all three implement the capability interfaces of section 4
        |   and call only the intake ports of section 5
```

Dependency rule (the mechanical form of exit criterion E1):

```text
 com.nexus.adapter.*  ──may depend on──>  com.nexus.adapter.spi (interfaces and value types only)
 com.nexus.adapter.<transport> ──may NOT depend on──> com.nexus.twin.*, com.nexus.rule.*,
                                                      com.nexus.action.application.*, com.nexus.action.outbox.*,
                                                      com.nexus.telemetry.application.*, repositories or JdbcTemplate
 com.nexus.twin.*, com.nexus.rule.*, com.nexus.action.*, com.nexus.telemetry.*  ──may NOT depend on──> com.nexus.adapter.<transport>
 only com.nexus.adapter.core (the registry, the intake bridges, the egress router) may depend on both sides.
```

`adapter.spi` holds the interfaces and immutable value types (no Spring, no persistence). `adapter.core` is the only package that bridges to the core services. A transport package holds nothing but transport code (sockets, Paho, servlet) and decoding.

## 4. Capability-split adapter interfaces

There is no single "DeviceAdapter" with every method. An adapter declares the capabilities it really has and implements exactly those interfaces. All value types are immutable records.

| Interface | Methods | Meaning | MQTT JSON | HTTP/webhook | Simulator |
|---|---|---|---|---|---|
| `AdapterDescriptor` (mandatory) | `id()`, `supportedContractMajors()`, `capabilities()`, `diagnostics()` | Identity, versions, declared capabilities, a read-only health and counter snapshot | yes | yes | yes |
| `AdapterLifecycle` | `start()`, `stop()` | Own the connection or listener (connect and disconnect) | yes (Paho client) | yes (servlet endpoints; start and stop gate acceptance only) | yes (in-process) |
| `TelemetryDecoder` | `decode(RawMessage) -> Decoded<TelemetryPayload> \| Rejection` | Bytes and transport metadata to a typed payload, never throws for bad input | yes | yes | yes |
| `AcknowledgementDecoder` | `decode(RawMessage) -> Decoded<AckPayload> \| Rejection` | Bytes to a typed ACK | yes | yes | yes |
| `ConnectivitySource` | `decode(RawMessage) -> Decoded<ConnectivityObservation> \| Rejection` | Transport-observed presence (MQTT retained birth and will), delivered to the core as an internal `ConnectivityObservation`. This is a core-facing value, **not a wire message**: the MQTT wire format is the plain-text status of section 7 and no JSON connectivity message exists or is introduced. HTTP has none: its eligibility signal is not connectivity (section 10.8) | yes | no | yes |
| `CommandEgress` | `deliver(CommandEnvelope, Duration) -> Attempt` | Put one already-accepted, already-leased command on the transport; classify the outcome | yes | **only after section 10 is approved**; otherwise the capability is not declared | yes |
| `DeviceAuthenticator` | `authenticate(RawMessage) -> AuthenticatedDevice \| Rejection` | Transport-level identity of the sender | no (broker-level; topic scoping) | yes (section 9) | no |

Rules:

1. An adapter that does not declare `CommandEgress` is **ingest-only**. The conformance report shows "command egress: not supported", never a pass.
2. `Decoded` and `Rejection` are the only results of decoding. A `Rejection` carries a **fixed reason code** from the closed vocabulary of section 7 and a classification `PERMANENT` or `TRANSIENT`; it never carries payload text.
3. `CommandEnvelope` is the immutable accepted command: `commandId`, `deviceId`, the exact stored payload bytes and their SHA-256, the payload epoch second, the correlation id. An adapter must deliver the bytes verbatim and must not rebuild or refresh them (Phase 4 R11).
4. `Attempt` is the existing Phase 4 shape (`CONFIRMED`, `NOT_SENT`, `UNCERTAIN` with a stable code), with `CONFIRMED` carrying an **evidence kind** (section 11). An adapter classifies conservatively: `NOT_SENT` only with proof that nothing reached the transport; anything ambiguous is `UNCERTAIN`.
5. No adapter method receives or returns a Twin, a Rule, a repository, an entity or a `JdbcTemplate`.

## 5. Core intake ports and the transport context

Adapters never call the services directly; `adapter.core` bridges decoded values to the **existing** core entry points. The bridging layer does not contain business logic.

| Port (in `adapter.spi`, implemented in `adapter.core`) | Delegates to (unchanged) | What stays in the core |
|---|---|---|
| `TelemetryIntake.submit(TelemetryPayload, TransportContext)` | `TelemetryService.processTelemetry(request, source)` | device existence and activation, onboarding rules, quality, normalisation, Twin and rule routing, the messageId dedupe inbox and its conflict semantics (Phase 4 R17), retention |
| `AcknowledgementIntake.submit(AckPayload, TransportContext)` | `DeviceCommandAckMessageHandler` / `CommandAckFacts` | first-ACK-wins, conflict and late-ACK facts, the persisted ACK timeout, convergence being separate |
| `ConnectivityIntake.submit(ConnectivityObservation, TransportContext)` | `DeviceConnectivityRegistry.applyObservedStatus` | transition authority, generation counters, rule facts |
| `CommandEgressRouter` (called by the dispatcher) | the `BrokerCommandPublisher` seam generalised to `CommandEgress` | authorisation at acceptance, admission caps, the publish gate, claim and lease, retry and expiry policy, uncertain hold, state-history facts |

`TransportContext` is immutable: `adapterId`, `AuthenticatedDevice` (absent for MQTT, whose scoping is by topic) or the topic-derived device id, `receivedAt` (the backend clock), `sourceLabel`. **Authorisation rule (D2, D3):** before a decoded message is accepted by a port, `adapter.core` checks that (a) the device's server-side transport assignment equals the adapter that decoded the message, and (b) the device id in the payload equals the authenticated or topic-derived device id. A mismatch is a `PERMANENT` rejection (`TRANSPORT_MISMATCH`, `DEVICE_MISMATCH`) counted, never processed. A payload field can never select an adapter.

The ACK and presence ports reach the same application steps as the MQTT handlers through two behaviour-preserving extractions made in P5.2: `DeviceCommandAckMessageHandler.acknowledge(...)` (the body of `handle` after parsing, unchanged order and clock) and `DeviceStatusMessageHandler.observe(...)`. `TelemetrySource` (today `HTTP`, `MQTT`) gains values for the new transports only where a label is needed; it is a diagnostic label and never part of the dedupe key (Phase 4 R17 unchanged).

## 6. Contract versioning and legacy-v1

**Version identifier.** `contractVersion` is a string `MAJOR.MINOR` (two non-negative integers, no patch). A message may carry it as a top-level JSON field `contractVersion`, or, for HTTP only, as the header `X-Nexus-Contract-Version`; if both are present and differ, the message is rejected `CONTRACT_VERSION_CONFLICT`.

**N is a major version.** The word "N" in any statement about the support window means a major version. Minor versions are never counted that way.

**Resolution (a total function, tested exhaustively).**

| Input | Resolved to | Behaviour |
|---|---|---|
| no `contractVersion` | `legacy-v1` (semantically 1.0 plus the legacy leniencies below) | accepted; counted `nexus.adapter.contract{version=legacy-v1}` |
| `1.m` with `m` less than or equal to the highest minor the backend knows | that version | validated against the schema of that minor |
| `1.m` with `m` greater than the highest known minor | `1.<highest known>` | accepted; unknown **optional** fields are ignored; counted `forward-minor`. Because additive-only is the rule for minors, the message is still a valid 1.<known> message. A producer that needs a field the backend does not know must use a new major. |
| `M.m` with `M` not in the supported-majors list | none | **rejected permanently** `UNSUPPORTED_CONTRACT_MAJOR`; never reinterpreted as an older major; counted and visible in diagnostics |
| not a string of the form `MAJOR.MINOR`, or empty | none | rejected permanently `INVALID_CONTRACT_VERSION` |

**Support window (finite, actual versions).** At the end of Phase 5: supported majors `{1}`; supported minors of major 1 `{0}` plus `legacy-v1`. When a major 2 is introduced, major 1 stays supported for **one full release cycle after the release that deprecates it** (the release notes must name the removal release), and the list in this section is updated in the same change as the code. No other version is accepted.

### 6.1 Amendment: strict known-field validation for a newer minor

A message that declares a minor above the highest one the backend knows is **not** trusted more than a known one. The rules, all tested:

1. **Known fields are validated exactly as in the highest known minor**: type, required-ness, range, length, alphabet and enum membership. A known field whose value would be invalid under the known minor is rejected `INVALID_FIELDS` (or the field-specific code) even though the message declares a newer minor. A newer minor therefore cannot loosen the validation of a field the backend already understands.
2. **Unknown fields are ignored, never interpreted.** They are not stored, not forwarded to the Twin or the rules, and not echoed. They are counted (`nexus.adapter.unknown_fields{adapter}`, a count only: no field names or values as tags or log text, so a hostile producer cannot create unbounded cardinality).
3. **Unsupported semantics are rejected, not accepted silently.** A message may declare features it requires the receiver to understand in the reserved top-level array `requires` (strings of the form `<name>`). A receiver that does not know every named feature rejects the whole message permanently with `UNSUPPORTED_REQUIRED_FEATURE`. This is the only way a minor may carry semantics the receiver must honour; a change that is not ignorable and is not announced in `requires` is a breaking change and needs a new major. `requires` is itself a known field of 1.0 (optional, at most 8 entries of at most 32 characters from `a-z 0-9 _ -`); in Phase 5 no feature name is defined, so any non-empty `requires` is rejected today.
4. **Unknown enum values in known enum fields are rejected** (`status` of an ACK is exactly `ACCEPTED` or `REJECTED`), unless the contract explicitly marks a field as tolerant; Phase 5 marks none.
5. **A higher minor never changes the transport, device, authorisation or admission outcome** (sections 5 and 9 run before and independently of the version).
6. **Legacy unversioned firmware** is unaffected: it resolves to legacy-v1 under the exact rules below, has no `requires`, and the committed firmware's payloads stay accepted. A payload that is historically valid is never rejected more strictly because a validator exists (decision of 2026-10-18); a difference is recorded in the registry of section 8.4, not resolved by tightening legacy acceptance.
7. **`contractVersion` applies only where the message format has a place for it.** Telemetry, command and acknowledgement are JSON objects and may carry it. The MQTT status message is the bare text `online` or `offline`: it has no JSON envelope and no version field, none is added, and it always resolves to legacy-v1 semantics. Over HTTP the version of a posted status travels only in the `X-Nexus-Contract-Version` header.

**Legacy-v1 compatibility policy (explicit).** Messages without `contractVersion` are interpreted exactly as the committed code interprets them today: the legacy numeric telemetry shape (`deviceId`, `timestamp`, `sensorType`, `value`, `unit`; numeric value; the topic and payload device ids must agree), the generic shape with `capabilityKey`, the optional `messageId` and `readbackCommandId`; the ACK shape `commandId`, `status` of `ACCEPTED` or `REJECTED`, epoch-second `timestamp`, optional `reason`; status as the plain text `online` or `offline`; commands as `commandId`, `capability`, `command`, `value`, epoch-second `timestamp`. Legacy-v1 acceptance rules never become stricter than today's, and never looser: the golden fixtures (section 13) pin both directions. New producers (SDKs, the reference device) emit `contractVersion`.

**Backward-compatible evolution rules.** Allowed in a minor: a new **optional** field; a new enum value **only** where the contract states consumers must tolerate unknown values; a relaxed maximum. Forbidden in a minor (needs a major): removing or renaming a field, changing a type, making an optional field required, changing the meaning of a value, narrowing an accepted range, changing a topic or an endpoint path. A schema-evolution test proves every fixture valid under 1.m stays valid under every later 1.x.

## 7. Wire contract v1 (baseline, from the committed firmware and backend)

The baseline is what the committed firmware sends and accepts; it was read from `firmware/src/main.cpp` and the backend validators at `e29e215`.

| Message | Direction | Content (v1) | Notes |
|---|---|---|---|
| Telemetry | device to NEXUS | `deviceId` (UUID), `timestamp` (ISO-8601 UTC), `sensorType`, `value` (number), `unit`; optional `capabilityKey`, `readbackCommandId`, `messageId` (1 to 64 chars from `A-Z a-z 0-9 . _ : -`) | the committed firmware sends the five legacy fields and **no** `messageId`, so firmware telemetry is not deduplicated; SDK producers add one |
| Command | NEXUS to device | `commandId` (UUID text), `capability`, `command`, `value`, `timestamp` (integer epoch seconds, acceptance time). **Generic contract:** `command` is `SET_STATE` and `value` is whatever the capability type's backend validator (`CommandValueValidator`) accepts today: a boolean for LED, FAN and BUZZER, an integer 0 to 180 for SERVO, an object with `channel` and `state` for RELAY. **ESP32 legacy profile** (section 8.2): boolean `value` only, integer `timestamp`, and the firmware's own freshness window | the real ESP32 firmware rejects if `abs(now - timestamp) > 30 s` with reason `STALE`, missing fields `MALFORMED`, unknown capability or command `UNSUPPORTED`; this describes the firmware, not the whole contract |
| Acknowledgement | device to NEXUS | `commandId`, `status` (`ACCEPTED` or `REJECTED`), `timestamp` (integer epoch seconds, device clock), optional `reason` | a device claim: never used for latency (Phase 4 R13) |
| Status | device to NEXUS | the plain text `online` or `offline` (retained birth and last will on MQTT). The backend trims surrounding whitespace and then compares **case-sensitively** (`DeviceStatusMessageHandler.parseStatusOrNull`: `trim()` then `"online".equals`); `Online`, `ONLINE`, empty text and JSON are ignored as not a status | no JSON envelope, no `contractVersion`; over HTTP a posted `status` is recorded as a device claim; it does not drive the connectivity registry (section 10.8) |

**Rejection vocabulary (closed, fixed codes, also the metric tags):** `MALFORMED_JSON`, `INVALID_FIELDS`, `INVALID_MESSAGE_ID`, `INVALID_LEGACY_PAYLOAD`, `TOPIC_DEVICE_MISMATCH` (MQTT), `DEVICE_MISMATCH`, `TRANSPORT_MISMATCH`, `INVALID_CONTRACT_VERSION`, `CONTRACT_VERSION_CONFLICT`, `UNSUPPORTED_CONTRACT_MAJOR`, `PAYLOAD_TOO_LARGE`, `UNAUTHENTICATED`, `FORBIDDEN_DEVICE`, `UNSUPPORTED_REQUIRED_FEATURE` (section 6.1), plus the core's existing `ONBOARDING_REJECTED`, `INVALID_TELEMETRY`, `DUPLICATE_CONFLICT`. The first group already exists as the Phase 4 `nexus.telemetry.permanent.rejections` reasons and keeps its spelling.

## 8. Schemas and the local validator (exit criterion E2)

- One authoritative directory `contracts/v1/` holds JSON Schema (draft 2020-12) files for **telemetry, command, acknowledgement and status**, and `contracts/versions.json` listing the supported versions of section 6. **There is no `connectivity.schema.json`:** the only connectivity wire format is the plain-text status, which the status contract models as it is (section 8.1); connectivity as such is an internal core-facing observation addressed in the adapter interface work (sections 3 and 4), and a schema would be added only if source inspection finds an independent connectivity wire protocol (none exists at `e29e215`). No schema is duplicated: the Python validator reads `contracts/` directly, the backend differential test runs through that validator (the backend loads no schema and no Java JSON Schema library is added, decision of 2026-10-18), and the ESP32 library's host tests read the same files. A test proves that no other `*.schema.json` exists under `contracts/` and that each schema's hash is recorded in `versions.json` and `CHANGELOG.md` (ledger P5-014).
- `python -m nexus_contract validate --kind telemetry payload.json` (and `--contract-version`) validates a payload locally without a backend, printing a fixed error code per failure and exiting non-zero on any. A `--legacy` flag applies the legacy-v1 policy.
- The backend differential test (`WireContractParityTest`) and the Python validator run **the same real-payload corpus** and must agree on every verdict except registered, classified differences (section 8.3); a disagreement fails the build.
- JSON Schema cannot express every rule (the topic and payload device ids must agree; the 64-character `messageId` alphabet is expressible; the 30 s freshness depends on a clock). Rules a schema cannot hold are listed in the schema's `x-nexus-rules` annotation and implemented once in a small shared rule module whose cases are in the fixture corpus.

### 8.1 Status contract (plain text)

The status contract models the real MQTT payload and changes nothing about it. The validator treats the raw payload bytes as text, applies the backend's own preprocessing (UTF-8 decode, `trim()`), and then checks the result against `status.schema.json`, a string schema whose only valid instances are `online` and `offline`. The wrapping into a JSON string exists only inside the validator so that the same schema machinery applies; the production MQTT payload is never wrapped, enveloped or versioned. Case-sensitive comparison and trimming are documented behaviour of the baseline (legacy-v1, class D, section 8.4) and the corpus pins them in both directions: `online`, `offline` and the same words with surrounding whitespace are accepted; `Online`, `OFFLINE`, empty text, whitespace only, `{"status":"online"}` and `online\u0000` are rejected as not a status.

### 8.2 Validation layers, the generic command contract and the ESP32 legacy profile

Validation is reported in separate layers so that one of them never silently stands in for another. The validator output names each layer with `PASS`, `FAIL` (with a fixed code) or `NOT_PERFORMED` (with the missing input named):

| Layer | What it checks | Runtime input needed |
|---|---|---|
| 1. Structural | the JSON Schema of the message kind and version (types, required fields, lengths, alphabets, enums) | none |
| 2. Legacy compatibility profile | the stricter shape that one real firmware accepts, selected explicitly (`--profile esp32-legacy-v1`); the generic contract is never narrowed to it | none |
| 3. Identity consistency | topic device id equals payload `deviceId` (telemetry); topic device id and `commandId` shape for the ACK and command | the topic (`--topic`); otherwise `NOT_PERFORMED` |
| 4. Freshness | command age against a window | an injected reference time (`--reference-time`) and the window of the selected profile; otherwise `NOT_PERFORMED`. The validator never reads the wall clock implicitly |
| 5. Capability semantics | the value against the capability type (boolean, servo range, relay channel and state, binding constraints) | a capability profile supplied by the caller; otherwise `NOT_PERFORMED` |

A `NOT_PERFORMED` layer is never reported as passed and is never an error by itself; `--require-all-layers` turns it into a failure for a caller that wants completeness.

**Generic command contract (layer 1).** It represents the commands the backend can actually emit: required `commandId` (UUID text), `capability` (text), `command` (text, `SET_STATE` for every capability type the backend writes today), `value` (any non-null, non-array JSON value: the built-in capability types use a boolean, an integer or an object as listed in section 7, and their meaning is the layer 5 check) and `timestamp` (integer epoch seconds). It is not restricted to the LED-only firmware.

**ESP32 legacy profile (layer 2).** It additionally represents exactly what `firmware/src/main.cpp` accepts: `commandId` a string, `capability` and `command` strings, `value` a **boolean**, `timestamp` an integer that fits a C `long`; the firmware's freshness rule `abs(now - timestamp) > 30` is `STALE` (the absolute difference, so a timestamp up to 30 s in the future is accepted); the reasons the firmware emits are `MALFORMED`, `STALE` and `UNSUPPORTED`. A SERVO or RELAY command therefore passes layer 1 and fails layer 2, which is the correct statement about that firmware and not a defect of the generic contract.

### 8.3 Differential test plan: Python validator against the real backend decoders

Matching Python results to Python tests proves nothing about the backend. The parity tests therefore run the same real payloads through both implementations and compare normalised verdicts (`ACCEPT` or `REJECT:<fixed code>` per layer):

1. **Corpus.** Real payloads only: the firmware golden fixtures derived from the source (section 13), payloads produced by the simulator's own publisher and command-handler code paths (offline, no broker), payloads produced by the backend's own `OutboxPayload` and `CommandValueValidator` paths, and reject cases built by mutating them. Each item records its origin.
2. **Backend side.** Test-only code drives the existing decoder and validation paths, unmodified: `DeviceStatusMessageHandler` for status, `DeviceCommandAckMessageHandler` for acknowledgements (with recording fakes for its collaborators), `TelemetryRequest` with `StrictMessageIdDeserializer` and Bean Validation, and the telemetry listener's decoding, and for commands the backend-emitted payloads and `CommandValueValidator` verdicts. No production class is changed to make this possible; where a private parse step cannot be driven from outside, the test observes the handler's externally visible effect and says so.
3. **Python side.** The same corpus is submitted to the validator in **one bounded batch** per test class (a `validate-corpus` mode that reads a manifest and writes one result file; `python -I`, no network, a hard timeout, process reuse inside the class), never one subprocess per payload. A missing interpreter or validator **fails** the gate instead of skipping.
4. **Comparison and the registry.** Every disagreement must be a registered difference in `contracts/parity/known-differences.json` (class A to E, clause, rationale, owning slice). An unregistered difference fails; a registered difference that no longer exists also fails, so the registry cannot rot. A schema is never loosened or tightened merely to make an implementation agree.
5. **Direction.** Both directions are exercised: what the backend accepts the validator must also accept unless the difference is registered, and the reverse. The Python package is the schema authority; no Java JSON Schema dependency is added in Phase 5.

### 8.4 Discrepancy classes and the inventory

Definitions (decision of 2026-10-18):

- **A. Schema mistake:** the proposed schema incorrectly models established valid or invalid payload behaviour. A is fixed in the schema; it is never a registered difference.
- **B. Existing backend/firmware inconsistency:** their parsers, supported fields or validation semantics differ. Recorded; not automatically a defect.
- **C. Simulator parity gap:** simulator behaviour differs from the authoritative device/contract semantics.
- **D. Intentional legacy compatibility:** existing unversioned payloads, missing optional fields, precision rules and raw status handling that must remain supported.
- **E. Unsupported input requiring rejection:** inputs forbidden by the approved contract.

Inventory. "Verified" means read in the source at `e29e215` for this document; "to probe" means a candidate that P5.1 must establish with the differential corpus before it is registered:

| # | Finding | Class | State |
|---|---|---|---|
| 1 | Firmware telemetry timestamp has seconds precision and a `Z` suffix (`currentIsoTimestampUtc`) | D | verified |
| 2 | Firmware telemetry carries no `messageId`, so it is not deduplicated by the inbox | D | verified |
| 3 | Messages without `contractVersion` resolve to legacy-v1 | D | verified (contract 6) |
| 4 | Status is bare text; the backend trims and then compares case-sensitively; anything else is not a status | D | verified. The preparation report of 2026-10-18 said "case-insensitive": that was wrong, corrected here |
| 5 | Firmware command acceptance: `value` must be a boolean, `timestamp` a `long`, freshness is `abs(age) > 30` | B (with the backend, which can emit servo, relay and non-boolean values) | verified. Expressed as the ESP32 profile, not as the generic contract |
| 6 | The simulator's command freshness is one-sided (`clock - timestamp > stale_window`) and its default window is 300 s, against the firmware's absolute 30 s | C | verified; scheduled for P5.5 (300 s to 30 s) and **not changed in P5.1**. The fixture verdicts are the firmware's; the simulator disagreement is registered, not hidden |
| 7 | The backend ACK path requires a textual UUID `commandId`, a textual `status` that matches the enum case-sensitively (`ACCEPTED` or `REJECTED`) and an integral `timestamp`; `reason` is read with `asText()`, so a non-text JSON value is stored as its text form | D for the legacy path (today's acceptance is preserved); the 1.0 schema types `reason` as a bounded string. Whether a 1.0 message may be stricter than legacy-v1 here is decided by the P5.1 probe and registered | verified; the differential run registered the non-text `reason` values and the 256-character bound as class E (section 8.5) |
| 8 | Telemetry `timestamp` forms the backend accepts beyond the documented ISO-8601 instant: a numeric epoch, lower-case `t`/`z`, surrounding whitespace (Jackson trims UUID and Instant text) | E | verified by the differential run and registered (section 8.5) |
| 9 | The simulator accepts command fields the firmware and the contract reject: a fractional or numeric-string `timestamp` (it coerces with `float()`), a non-UUID `commandId` | C | verified by running the simulator's own CommandHandler and registered (section 8.5); corrected in P5.5 |
| 10 | Unsupported major, malformed version, header/body version conflict, non-empty `requires`, unknown enum value in a known enum field, `messageId` outside the alphabet or length, topic/payload device mismatch | E | verified as contract rules (6, 6.1, 7); the fixed codes are pinned by the corpus |
| 11 | Documentation errors found by this review: P5-009 omitted trimming; the preparation report called status handling case-insensitive; a connectivity schema was listed although no connectivity wire message exists | A (the modelling mistake class, caught before any schema existed) | corrected in this change |

Lenient backend handling is not automatically a production defect: it is recorded under B, D or E with its reason, and changes to it are a separate decision.

### 8.5 Result of the P5.1 differential run

Corpus `contracts/parity/corpus.json`: 219 real-payload items (frozen firmware and simulator fixtures, backend-emitted commands, single-fault mutations) run through the unmodified `TelemetryMQTTListener`, `DeviceCommandAckMessageHandler`, `DeviceStatusMessageHandler` and `CommandValueValidator`, and through the Python validator. Result: the two sides agree on all but 29 items, every one of them registered in `contracts/parity/known-differences.json`:

- **Class E (23 cases):** forms the contract forbids and the backend tolerates: a byte-order mark, trailing content, a repeated member name; number or boolean text members and non-text or over-long `reason`; a short Java-style UUID, an empty `readbackCommandId`, a numeric epoch or lower-case timestamp, surrounding whitespace on a UUID or timestamp. No real producer sends them; the backend is unchanged.
- **Class B, defect candidates (6 cases):** (1) an acknowledgement whose integral `timestamp` is outside the `java.time.Instant` range makes `DeviceCommandAckMessageHandler` throw `DateTimeException`; (2) the JSON text `null` as telemetry makes `TelemetryMQTTListener` throw `IllegalArgumentException`; both escape unacknowledged, so the broker redelivers the same message; (3) `CommandValueValidator.requireWholeNumber` narrows a 64-bit integer to 32 bits, so the SERVO value 4294967386 is accepted as angle 90. The Phase 4 safety semantics are frozen, so these are recorded for a separate decision, not fixed in P5.1.
- **Class C (7 simulator cases, `simulatorEntries`):** the 300-second one-sided freshness rule and the lenient `timestamp` and `commandId` handling; corrected in P5.5.
- **Class A (schema mistakes found and fixed during the run):** the telemetry timestamp pattern first rejected the simulator's `+00:00` offset form; the patterns first accepted a trailing newline because `$` matches before it in Python; the empty string for a UUID or timestamp member is `null` to Jackson (so `INVALID_FIELDS`, not `MALFORMED_JSON`); the acknowledgement timestamp had no range; repeated member names were accepted. None of these is registered: they were corrected in the schemas and rules.



## 9. HTTP/webhook adapter: ingest and device credentials (D2)

Endpoints (all under `/api/v1/device-adapter/`, none in the existing `/api/v1/telemetry` namespace, which stays as it is): `POST /devices/{deviceId}/telemetry`, `POST /devices/{deviceId}/acknowledgements`, `POST /devices/{deviceId}/status`.

1. **Credential presentation.** `Authorization: Bearer <key>` only. A key in a URL, a query string, a path segment or a log line is a defect. The server never logs the header or the key, and never echoes it.
2. **Key format.** `nxk1_<lookupId>_<secret>`: a public, non-secret lookup id (so verification is one indexed read) and a 256-bit random secret (base64url). The server stores the lookup id and a one-way hash of the secret only (section 12); the plaintext key is shown **once**, at creation or rotation.
3. **Verification.** Compare the stored hash with the hash of the presented secret using a constant-time comparison; an unknown lookup id performs a dummy comparison so timing does not reveal existence. A revoked, expired or retired credential is `UNAUTHENTICATED`. Failures are counted per fixed reason with no device identifier as a tag.
4. **Per-device authorisation and impersonation.** The authenticated credential belongs to exactly one device. The path device id and the payload device id must both equal it; any other combination is `FORBIDDEN_DEVICE`, counted, and nothing is processed. A credential of device A can never submit for device B, even if A and B share a template.
5. **Transport assignment.** The device must be assigned `HTTP_WEBHOOK` (section 5). A device assigned `MQTT_JSON` receives `403` with `TRANSPORT_MISMATCH`; HTTP never silently becomes a second path for an MQTT device.
6. **Bounds.** Maximum request body 8 KiB (rejected with `413` and `PAYLOAD_TOO_LARGE` before parsing), JSON only, strict content type, a bounded read timeout, and a per-device concurrency cap of 2 in-flight requests. Rate limiting beyond that is a deployment concern (reverse proxy) in Phase 5 and is stated as such; the in-application limit protects the core, not the network.
7. **Rotation and revocation.** A device may hold at most two non-revoked credentials (one active, one retiring) so a device can rotate without an outage. Rotation creates the new credential and sets the old one to retire at a stated instant (default 24 h, maximum 7 days); revocation is immediate and final. Both are recorded in an append-only credential event table (section 12). A revoked credential is rejected on the very next request (no cache longer than 5 seconds; the cache is keyed by lookup id and holds only the hash).
8. **Credential administration surface (D7, approved).** Credentials are created, rotated and revoked **only** by a local administrative command-line tool (`nexus-credentials`, a Python entry point sharing the backend's hashing code through a small shared specification and a cross-implementation test, or a Spring-free Java main; the choice is made in the P5.4 design). The backend exposes **no** REST endpoint, WebSocket message, actuator endpoint or MQTT topic that creates, rotates, revokes or lists credentials, and the tool opens **no** listening socket and starts no HTTP server of any kind; both are architecture tests. Section 9.8.1 specifies the tool.
9. **TLS.** The application does not terminate TLS in Phase 5; real deployments terminate it in front of NEXUS and the documentation says so. Tests run over plain HTTP on loopback and say so; a test proves the adapter refuses to start with credentials enabled on a non-loopback bind address unless `nexus.adapter.http.require-forwarded-https` is set and the `X-Forwarded-Proto: https` header is present on each request (a documented, deliberately conservative guard).
10. **Idempotency.** Telemetry redelivery uses the existing `messageId` inbox. An ACK is idempotent by the first-ACK-wins rule. No other HTTP request changes state twice.
11. **Not claimed.** This does not authenticate the platform, does not authorise users, does not protect against a compromised device that holds its own valid credential, and offers no confidentiality without TLS in front.

### 9.8.1 Credential administration CLI (D7)

| Aspect | Specification |
|---|---|
| Operations | `create --device <id>`, `rotate --device <id> [--retire-after PT24H]`, `revoke --credential <lookupId>`, `list --device <id>` (metadata only: lookup id, status, timestamps; never a secret or a hash), `audit --device <id>` (the event rows) |
| Who may run it | A person with an operating-system account that can run the tool **and** the database login the tool is configured with. There is no application-level user model in NEXUS; this is therefore an operations privilege, stated as such, not authentication of an administrator |
| Database privileges | A dedicated database role `nexus_credential_admin` with `SELECT` on `devices`, `SELECT, INSERT, UPDATE` on `device_ingestion_credentials` and `INSERT` on `device_credential_events`, and nothing else (no `DELETE`, no access to telemetry, commands or rules). The backend's own role gets `SELECT` on the credentials table and `UPDATE` of only the last-contact column. Creating the roles is part of the V41 operator procedure and is documented, not done by the migration silently |
| Secret generation | 32 bytes from the operating system's cryptographically secure generator (`SecureRandom` / `secrets.token_bytes`), base64url encoded; the lookup id is 12 independent random bytes. No timestamp, counter, device id or other predictable material enters either value |
| One-time display | The full key `nxk1_<lookupId>_<secret>` is printed once to the terminal (or written once, with `--output-file`, to a new file that the tool creates with owner-only permissions and refuses to overwrite). It is never printed again, never stored, never placed in the process environment or in a command-line argument, and never passed to a logger; the tool turns off its own debug logging for these operations and tests assert the key does not appear in captured output other than the one intended place |
| Verification material | Only the lookup id and `HMAC-SHA-256(pepper, secret)` are stored (contract section 12). The pepper is read from the environment variable `NEXUS_DEVICE_KEY_PEPPER` (or a file named by `NEXUS_DEVICE_KEY_PEPPER_FILE`) by both the tool and the backend; neither starts without it when credentials are used; it is never in the database, the repository or a log. Changing the pepper invalidates every credential and requires re-issuing them, which is documented |
| Rotation | `rotate` creates the new ACTIVE credential and moves the previous one to RETIRING with a `retire_at` (default 24 hours, at most 7 days); a device therefore keeps working while it is updated. A device with a RETIRING credential cannot rotate again until it is gone or revoked |
| Revocation | `revoke` is immediate and final: status REVOKED, `revoked_at` set. The backend's credential cache holds the hash for at most 5 seconds |
| Audit | Every operation appends a `device_credential_events` row (event type, credential id, time, actor). The actor is a fixed label plus the operating-system account name of the person who ran the tool (`cli:<account>`); no secret material and no free text is recorded |
| Failure behaviour | Any error leaves no partially created credential (one transaction per operation); a failed create never prints a key |
| What it is not | Not a network service, not an API, not an authorisation model for people |

## 10. HTTP command delivery: the pull safety contract (D5, D8, D9)

**Status.** HTTP command pull remains a Phase 5 objective (D8). It is **designed** here and not yet proven: no code exists, and the adapter does **not** declare `CommandEgress` until section 20's spikes confirm this section, the section F scenarios of the ledger are implemented and covered, and the owner has authorised P5.4's outbound part. If any rule below cannot be met, HTTP stays ingest-only and the affected scenarios are recorded DEFERRED with the reason; the omission is never reported as conformance. There is no push (webhook-to-device) delivery in Phase 5: a device behind NAT cannot be reached and a push to a device-controlled URL is a server-side-request-forgery risk.

**10.1 Endpoint and authorisation.** `POST /api/v1/device-adapter/devices/{deviceId}/commands/pull`. It is a state-changing operation and therefore POST; a `GET` (or any other method) on that path answers `405`. *Amendment: the earlier chat summary of this plan said `GET`; the contract text is and remains POST.* Request body optional, `waitSeconds` 0 to 10 (default 0). The credential travels only in `Authorization: Bearer`. Authentication, per-device authorisation, transport assignment (`HTTP_WEBHOOK`), the size and concurrency bounds of section 9, and the D9 eligibility recording of 10.8 apply. One pull in flight per device (a second concurrent pull answers `204` without claiming).

**10.2 No new obligation.** A pull creates no command. It can only serve a command that Phase 4 acceptance already created in `command_outbox`, after every acceptance-time guard (rule authorisation, device state, admission caps, the transport eligibility of 10.8). The adapter cannot widen what is accepted.

**10.3 The durable claim and the exact state transitions (D8 points 1, 5, 6).** A pull is exactly one delivery attempt of the existing Phase 4 state machine. Every write below is in the transaction named, using the existing `OutboxDeliveryOperations` methods; the adapter adds none.

| Step | Where | Transaction and writes | Resulting state |
|---|---|---|---|
| 1. authenticate, authorise, bound | adapter + `adapter.core` | none | n/a |
| 2. find the device's next candidate | `adapter.core` calling the dispatcher's candidate query for this one device | read only | n/a |
| 3. **claim** | `OutboxDeliveryOperations.tryClaim` (the publish gate permit, then the fenced UPDATE) | one short transaction T2: `state = IN_FLIGHT`, `attempt_count + 1`, `lease_owner`, `lease_expires_at` from the database clock, history `ATTEMPT_STARTED`; fails closed if the gate refuses | `IN_FLIGHT` (no response bytes exist yet) |
| 4. build the whole response in memory | adapter | none; the stored payload bytes verbatim, the contract-version header, the SHA-256 header | n/a |
| 5. write and flush the response | adapter, outside any transaction and holding no database connection | none | `IN_FLIGHT` |
| 6a. nothing was written (see 10.5) | `OutboxDeliveryOperations` NOT_SENT outcome | transaction T4: `RETRY_WAIT` (attempts and window left) or `DEAD_LETTER` `RETRY_EXHAUSTED`/`EXPIRED`, history `ATTEMPT_FAILED_TRANSIENT` / `DEAD_LETTERED` | as Phase 4 |
| 6b. the response was fully written and flushed | `OutboxDeliveryOperations` confirmed outcome | transaction T4: `PUBLISHED`, `first_publish_confirmed_at`, `terminal_at` from the database clock, the ACK deadline set, history `PUBLISHED` with `reason_code = HTTP_SERVED` | `PUBLISHED` (evidence kind `HTTP_SERVED`) |
| 6c. anything else after the first byte | `OutboxDeliveryOperations` uncertain outcome | transaction T4: `DEAD_LETTER` `UNCERTAIN_EXPIRED`, `terminal_at = clock_timestamp()`, history `ATTEMPT_UNCERTAIN`, `DEAD_LETTERED`; the R32 hold applies to later commands of the device | `DEAD_LETTER` |
| 7. the device's ACK | `AcknowledgementIntake` -> `CommandAckFacts` | unchanged first-ACK-wins transaction, ACK facts, persisted ACK timeout | unchanged |

Fencing: every T4 write is conditional on `lease_owner` and `attempt_count` exactly as in Phase 4, so a pull handler that stalls past its lease cannot overwrite a recovery. Ordering: the single in-flight-per-device unique index and the "an older unresolved command of the device blocks a newer one" claim rule decide which command a pull can get; a command waiting in `RETRY_WAIT` or held by an uncertain predecessor is not served. The deadline: a claim is refused after `expires_at`, and the expiry sweep dead-letters it `EXPIRED`. Retry: only `NOT_SENT` (6a) is ever retried, exactly as R33; `UNCERTAIN` (6c) is never retried or re-served.

**10.4 Payload verbatim.** The response body is the stored payload unchanged: same `commandId`, same acceptance-time `timestamp`, same SHA-256, with `X-Nexus-Contract-Version`. The timestamp is never refreshed at serve time, so waiting cannot make a command look fresh to the firmware's 30-second check.

**10.5 What the HTTP framework can observe, and the conservative classification (D8 points 3, 4, 5).** The servlet container reports to the application only: an `IOException` thrown by a write or by `flushBuffer()`, whether the response is committed, and (for the asynchronous API) completion or error callbacks. It does **not** report whether the peer read the bytes: a successful write means the bytes were handed to the operating system's socket buffer, and a successful flush means the container flushed its own buffers; neither is a receipt, and a client that disconnects is usually detected only on a later write. Therefore:

- *Nothing written* (`NOT_SENT`): a failure strictly before the first body byte is handed to the container (serialisation, header construction, an exception in steps 1 to 4, or the handler being cancelled before step 5). Proof is structural: the adapter writes the whole body with a single call after it has built it, and records "step 5 entered" in a local variable before the call.
- *Fully written and flushed* (`CONFIRMED`, evidence `HTTP_SERVED`): step 5 returned normally, `flushBuffer()` returned normally, and the response was not already marked failed. This asserts **only** that NEXUS handed the complete response to its own network stack. It is deliberately weaker than the broker's QoS 1 completion, which involves a remote acknowledgement.
- *Everything else* (`UNCERTAIN`): any exception, reset, timeout, cancellation or thread interruption once step 5 was entered, including an exception from the flush; a handler that does not return within the attempt timeout; a process that dies. The default is uncertain, never success and never "not sent".

**10.6 What `HTTP_SERVED` means (D8 point 2).** The complete response was written and flushed to the device's HTTP connection by NEXUS. It does **not** mean the device read, parsed, accepted or executed the command, and it is not the same fact as broker publication. The Phase 4 state name `PUBLISHED` is retained for the state machine (changing the state vocabulary would need a migration of the V37 constraints and every consumer), but the *evidence kind* is recorded and shown with it (section 11). The ACK is the device's own statement; convergence is a separate verified fact; actuation is not established by any of them.

**10.7 Process-kill behaviour (D8 point 7).** All rows assume the Phase 4 lease and recovery (`recoverExpiredLeases`) and are the cases of the fault-injection scenarios.

| Kill point | Durable state at the kill | What recovery does | What a device can observe | Why this is safe |
|---|---|---|---|---|
| Before the claim | `QUEUED`, attempt 0 | another instance (or the restarted one) claims it and a later pull serves it, inside the window | nothing yet | no attempt happened |
| After the claim, before any byte | `IN_FLIGHT`, lease held, attempt 1 | the lease expires; recovery treats the attempt as **uncertain** (`LEASE_EXPIRED_UNCERTAIN`): `DEAD_LETTER UNCERTAIN_EXPIRED` | no response | recovery cannot know whether bytes were written, so it does not retry or re-serve (the Phase 4 rule; it costs the command, never a duplicate) |
| During the response writing | the same `IN_FLIGHT` row | the same uncertain dead letter | possibly a truncated body | the device must treat a body that fails the hash/JSON check as no command; a truncated command is rejected `MALFORMED` by the device's own check |
| After the response, before the outcome transaction T4 | `IN_FLIGHT` | the same uncertain dead letter even though the device may have the full command | a complete command | an ACK that follows is recorded as `ACK_ACCEPTED` then `ACK_AFTER_TERMINAL`; the command is not resurrected or re-served |
| After `PUBLISHED`, before the ACK | `PUBLISHED`, ACK deadline set | the persisted ACK-timeout scanner records `ACK_TIMED_OUT` after the deadline, restart included (the P4-065 behaviour) | a command with no recorded ACK | a timeout does not say the device did not act |

**10.8 Transport eligibility (D9).** *Amendment.* Phase 4 command acceptance requires the device to be ONLINE in the MQTT connectivity registry and the MQTT client to be connected. These checks are transport-specific and move behind a core port `TransportEligibility.eligible(deviceId, at) -> Eligible | Ineligible(code)`, implemented per adapter and called by the acceptance guard; the Twin and the Rule engines are not involved and keep reading the unchanged connectivity registry.

- **MQTT JSON:** exactly today's rule (registry ONLINE and client connected). Behaviour unchanged.
- **Simulator:** eligible while the in-process device is running.
- **HTTP/webhook:** eligible when the device has a **valid authenticated contact** whose backend-authoritative instant is within the eligibility window of "now". A valid contact is recorded **only** when (a) the credential authenticated and is ACTIVE or RETIRING and not expired, (b) the device is ACTIVE and assigned `HTTP_WEBHOOK`, (c) the request passed the size, content-type and JSON-validity checks and decoded to a valid message of its kind (a telemetry, acknowledgement or status message, or a well-formed pull). A missing, invalid, revoked or expired credential, a malformed or oversized request, a mismatched device, and an inactive device **never** record a contact and never extend availability. The contact instant is written from the database clock (`clock_timestamp()`), at most once every 5 seconds per device, into `last_valid_contact_at` (section 12), so the eligibility test is a single indexed read on the database clock and never trusts a device-supplied time.
- **Window:** configuration `nexus.adapter.http.contact-eligibility-window`, **initial value 60 seconds**, bounded 15 to 300 seconds at startup. It is to be finalised against the documented polling cadence in spike S6: with a long poll of at most 10 seconds and a re-poll within 5 seconds, 60 seconds tolerates several missed cycles without making a long-gone device look reachable. A larger window means a command can be accepted for a device that has gone away and then expires visibly (the delivery window is at most 20 seconds, so the cost is a dead letter, never a late delivery).
- **It is an eligibility signal, not connectivity.** It is not written to the connectivity registry, produces no connectivity transition and fires no connectivity rule trigger; the device's `status` message, if it posts one, remains a device claim recorded as such. Documentation, API text and UI must not call a recently-contacting HTTP device "online".
- If spike S6 shows that the acceptance guard cannot be made transport-aware without changing Rule or Twin behaviour, HTTP devices cannot receive commands in Phase 5 and the outbound scenarios are DEFERRED.
- **S6 inventory result (P5.2).** The acceptance-time reachability guard exists at exactly one site, `CommandExecutor.prepare` (registry ONLINE, then broker client connected); the other readers of the connectivity registry are reporting only (`DeviceController` connectivity endpoint, `TwinStateQualityEvaluator` quality) and decide nothing about acceptance. The guard now calls `TransportEligibility` (`adapter.core.TransportEligibilityRouter` picks the rule by the device's assignment); the MQTT rule (`MqttTransportEligibility`) is the former code with the former wording, and no Twin or Rule class changed. A device assigned to a transport without a registered rule is refused (`NO_ADAPTER`): fail closed.
- **Where the MQTT rule lives.** It reads the connectivity registry and the broker client, so by the dependency rule of section 3 it cannot sit in the `adapter.mqtt` transport package; it is in `adapter.core`. Likewise the telemetry source label of an in-process simulator stays `MQTT` until a migration widens the V40 CHECK `('MQTT','HTTP')`.

**10.9 Polling retries and duplicate unsafe delivery (D8 point 8).** A device that loses a pull response, or whose pull times out, will poll again. Safety rests on four rules, none of which needs a client-supplied token: (1) the claim makes the pull a single durable attempt, so a repeated pull never creates a second attempt for the same command while the first is `IN_FLIGHT`; (2) once the response was handed to the transport (6b) the command is `PUBLISHED` and is never selected by a later pull; (3) if the outcome was uncertain (6c) the command is dead-lettered and held, never re-served; (4) a pull that finds a command in `RETRY_WAIT` serves it only when its scheduled retry time arrives and only because the previous attempt was proven not sent. The honest consequence is that a lost response loses the command: it ends `PUBLISHED` with no ACK (then an ACK timeout) or `UNCERTAIN_EXPIRED`, visibly, and the operator or rule engine decides what to do. The rejected alternative, re-serving "the same attempt" on a repeated pull, would replay a possibly delivered command and is prohibited by the uncertainty barrier.

**10.10 Freshness arithmetic.** The delivery window is at most 20 s and the firmware accepts a command up to 30 s old. A device that polls less often than the window cannot receive commands (they expire visibly). The documented operating mode is a long poll of up to 10 s with an immediate re-poll; the SDK helpers enforce a maximum re-poll delay of 5 s. The pull handler must not hold a database connection or a lock while it waits (it polls a cheap indexed query on a bounded schedule and holds only a thread; the per-device concurrency cap of one bounds the threads).

**10.11 Bounds.** Response at most 4096 bytes (the V37 payload bound), at most one command per pull, no batching in Phase 5, `waitSeconds` at most 10.

**10.12 Schema extension check (D8 point 10).** The design needs **no** change to `command_outbox`, `command_state_history` or `action_history`: the state names, the lease and attempt columns, the `reason_code` format (`^[A-Z0-9_]{1,64}$`, which holds `HTTP_SERVED`) and the fact types are used as they are, and the logical topic string stays `nexus/devices/<id>/commands`. The Phase 5 schema extension is only the V41 of section 12 (transport assignment, credentials, credential events, and the `last_valid_contact_at` column of D9). Three assumptions must be confirmed by spikes before this statement is relied on: that the V37 topic CHECK and the V37/V39 guard triggers accept an HTTP-assigned device's rows (S1); that the outcome methods set `reason_code` from an argument rather than a constant, or can accept an evidence kind without changing their callers (S2); and that the API/lifecycle projections can carry the evidence kind additively (section 11). If any fails, the finding is reported before code and HTTP stays ingest-only until a reviewed extension is approved.

**10.13 Failure of the design.** If P5.4's review finds that any rule above cannot be implemented and tested, the adapter ships ingest-only, the omission is recorded in the ledger as DEFERRED with the reason, and the conformance report says "command egress: not supported".

## 11. Delivery evidence kinds: the Phase 4 extension

Phase 4 states that `PUBLISHED` means broker publication and nothing more. That statement stays true **for the MQTT adapter**. Phase 5 widens the *vocabulary*, not the MQTT guarantee:

*Amendment: backward-compatible evidence without misleading Phase 4 clients (D8 point 9).*

- The **state** vocabulary is unchanged, so no Phase 4 consumer breaks. Changing it would need a migration of the V37 constraints and of every reader.
- A Phase 4 client that reads only `deliveryState = PUBLISHED` would label an HTTP-served command "Published to MQTT". To prevent that, the evidence kind is added **additively and prominently**: the lifecycle API gains `delivery.evidenceKind` and the history endpoint gains `deliveryEvidence` (both derived from the `reason_code` of the command's `PUBLISHED` fact; absent for commands without one, so existing responses are byte-identical for MQTT commands except for the two new members, which carry `BROKER_CONFIRMED`); the human-readable `explanation` sentences and the `ActionHistory.message` of an HTTP-served command say "served over HTTP" and never "published to MQTT"; this repository's frontend labels by evidence kind in the same slice (P5.4).
- *Residual risk, accepted and recorded:* an **external** client built against Phase 4 that ignores the new members will still see `PUBLISHED`. There is no external client of this API in the repository, HTTP-assigned devices exist only after an operator assigns the transport, and the release notes and the operator guide state the change. This is listed as an open question for the owner (ticket section 6).

1. The state `PUBLISHED` means "the adapter's transport reported the command handed over, with the evidence kind stated". The evidence kind is recorded as the `reason_code` of the `PUBLISHED` history fact: `BROKER_CONFIRMED` (existing, MQTT QoS 1 completion), `HTTP_SERVED` (section 10), `SIMULATOR_DELIVERED` (the in-process adapter). No schema change is required: `reason_code` is `^[A-Z0-9_]{1,64}$` and the fact type is unchanged.
2. Every platform sentence about delivery (the lifecycle statements, the API explanation, the frontend labels, the rule-match explanation) names the evidence kind. The existing MQTT wording is unchanged: "Published to the MQTT broker. Publication does not prove that the device received, accepted or executed the command." For HTTP: "Served over HTTP: the complete response was written to the device's connection. This does not prove the device read or acted on it."
3. The Phase 4 uncertain rules apply to every adapter unchanged: an ambiguous attempt is UNCERTAIN, ends `UNCERTAIN_EXPIRED`, is never resent, and holds later commands of the device for the R32 interval.
4. Nothing in Phase 5 may claim exactly-once delivery, device receipt or physical actuation; the architecture tests that guard those claims (Phase 4 P4-123) are extended to the new documents, strings and UI labels.

## 12. Migration V41 (implemented in P5.V41: `V41__device_transport_and_ingestion_credentials.sql`)

Amendment of 2026-10-18: V41 is its own work package (ledger section J, `P5.V41`), not part of P5.1. **Implemented 2026-10-18** after spike S1 passed against a disposable database (`HttpTransportSchemaCompatibilityTest`). The SQL below is the design; the committed file is authoritative. Differences from the proposal: `lookup_id` is constrained to 8 to 24 alphanumeric characters and `hash_algorithm` to a name pattern; credential events also index `(credential_id, occurred_at)`; `device_templates.protocol_type` (CHECK `MQTT`) is deliberately unchanged, so a template's `default_transport` is the only template-level transport statement. The two database roles of section 9.8.1 are not created here (P5.4).

Forward-only, checksummed, tested on an empty and on a populated disposable database, never touching V1 to V40. Estimated size under 100 lines.

```sql
-- V41__device_transport_and_ingestion_credentials.sql  (PROPOSAL)

-- D3: server-controlled transport assignment. Existing rows keep MQTT_JSON by default, so no behaviour changes.
ALTER TABLE devices ADD COLUMN transport VARCHAR(24) NOT NULL DEFAULT 'MQTT_JSON';
ALTER TABLE devices ADD CONSTRAINT ck_devices_transport CHECK (transport IN ('MQTT_JSON', 'HTTP_WEBHOOK', 'SIMULATOR'));
ALTER TABLE device_templates ADD COLUMN default_transport VARCHAR(24) NOT NULL DEFAULT 'MQTT_JSON';
ALTER TABLE device_templates ADD CONSTRAINT ck_templates_transport CHECK (default_transport IN ('MQTT_JSON', 'HTTP_WEBHOOK', 'SIMULATOR'));

-- D2: per-device ingestion credentials (hash only; the plaintext key is shown once and never stored).
CREATE TABLE device_ingestion_credentials (
    credential_id  UUID PRIMARY KEY,
    device_id      UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    lookup_id      VARCHAR(24) NOT NULL UNIQUE,          -- public, non-secret; one indexed read per request
    secret_hash    BYTEA NOT NULL,                       -- one-way (see below); 32 bytes
    hash_algorithm VARCHAR(24) NOT NULL,                 -- named, so it can change without ambiguity
    status         VARCHAR(12) NOT NULL,                 -- ACTIVE | RETIRING | REVOKED
    created_at     TIMESTAMPTZ NOT NULL,
    retire_at      TIMESTAMPTZ,                          -- set when RETIRING; the credential stops working at that instant
    revoked_at     TIMESTAMPTZ,
    last_valid_contact_at TIMESTAMPTZ,                   -- D9: database clock of the last VALID authenticated contact; written at most every 5 s per device
    CONSTRAINT ck_credential_status CHECK (status IN ('ACTIVE', 'RETIRING', 'REVOKED')),
    CONSTRAINT ck_credential_hash_len CHECK (octet_length(secret_hash) = 32),
    CONSTRAINT ck_credential_retire CHECK (status <> 'RETIRING' OR retire_at IS NOT NULL),
    CONSTRAINT ck_credential_revoke CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_one_active_credential ON device_ingestion_credentials (device_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_credentials_device ON device_ingestion_credentials (device_id) WHERE status <> 'REVOKED';
-- at most one RETIRING credential per device, so at most two non-revoked in total
CREATE UNIQUE INDEX uq_one_retiring_credential ON device_ingestion_credentials (device_id) WHERE status = 'RETIRING';

-- Append-only record of credential lifecycle events (creation, rotation, retirement, revocation). No secret material.
CREATE TABLE device_credential_events (
    event_id      UUID PRIMARY KEY,
    credential_id UUID NOT NULL REFERENCES device_ingestion_credentials(credential_id) ON DELETE CASCADE,
    event_type    VARCHAR(16) NOT NULL,                  -- CREATED | ROTATED_IN | RETIRING | REVOKED | EXPIRED
    occurred_at   TIMESTAMPTZ NOT NULL,
    actor         VARCHAR(80) NOT NULL                   -- 'cli:<operating-system account>' (section 9.8.1)
);
-- update and delete guard triggers like command_state_history (V39): UPDATE rejected; DELETE only with the parent.
```

Design notes and open points (each becomes a ledger scenario):

- **The hash.** The key secret is 256 bits of randomness, so a fast one-way hash is sufficient (a slow password hash protects low-entropy secrets and would only cost CPU per request). The proposal is `HMAC-SHA-256(pepper, secret)` with a server-side pepper supplied from the environment, not the database, so a database leak alone does not allow offline verification. The hash is **one-way and is not an HMAC signing secret**; request signing, if ever added, needs a separate design (D2).
- **Migration cost.** Adding a `NOT NULL DEFAULT` column to `devices` and `device_templates` rewrites no rows on modern PostgreSQL (a constant default is stored in the catalogue). The populated-database migration test proves existing devices read `MQTT_JSON`.
- **D9 column.** `last_valid_contact_at` is the only mutable column the backend's role may update; the eligibility test reads it. It lives on the credential row because a contact is authenticated by a credential; the eligibility window is evaluated per device over its non-revoked credentials (a RETIRING credential counts until `retire_at`).
- **No change to V37 to V40 tables.** `command_outbox.topic` keeps its logical address `nexus/devices/<id>/commands` for every transport (the CHECK in V37 binds the string, not the protocol). This must be re-verified in the P5.1 spike; if the CHECK blocks it, the finding is reported and the design is adjusted before any code.
- **Retention.** Credential events follow the credential (`ON DELETE CASCADE`); deleting a device removes its credentials. No credential row is purged by the retention job.
- **Rollback.** The migration is forward-only; the application ignores the new columns when the HTTP adapter is disabled, so disabling the feature is the rollback (the Phase 4 pattern).
- **Not in V41:** no mailbox table (the outbox is the mailbox), no new history fact type (section 11 uses `reason_code`), no nonce table.

## 13. Firmware golden-fixture strategy

1. **Provenance.** Fixtures live in `contracts/fixtures/firmware-v1/`, each with a sidecar recording the source: the git blob id of `firmware/src/main.cpp` at `e29e215` (already pinned by the Phase 4 protected-path hashes) and the line that builds it. Fixtures are *derived by reading the firmware source*, never by editing it.
2. **Drift detector.** A test parses `firmware/src/main.cpp` for the JSON keys it builds and the keys it requires in a command (`doc["..."]` patterns) and compares them with the fixture set and the schemas; if the firmware ever changes its key set, the test fails and names the key. The firmware tree hash is already immutable, so this is a guard for a future approved change.
3. **Both directions.** Accept fixtures (what the firmware sends today must validate under legacy-v1 and under the 1.0 schemas with `contractVersion` added) and reject fixtures (malformed JSON, wrong types, a non-UUID device id, a stale command) with the exact reason code the backend and the validator both produce.
4. **Semantic equality, not byte identity.** "Byte-compatible interpretation" means the same bytes parse to the same typed value; field order and whitespace do not matter. The ESP32 library's host build must produce documents that parse to the same typed values as the fixtures.
5. **Clock and freshness fixtures.** Command fixtures include the boundary cases (age 29, 30, 31 seconds; future-dated by 29, 30, 31 seconds) with the verdict the **firmware** computes; the ESP32 library and the backend freshness helper must agree with the firmware's verdicts. The simulator's current 300-second one-sided rule is a registered class C difference until P5.5; the fixtures record the firmware's verdict and P5.1 does not change the simulator (this is also the regression test for D4 once P5.5 lands).
6. **Frozen corpus, extended only by addition.** A new fixture may be added; an existing one changes only through a deliberate, reviewed contract change that is recorded in the ledger.

## 14. Conformance suite and extensibility proof

A reusable kit takes an adapter factory and runs the same scenarios for each declared capability: valid and invalid decoding with the exact rejection codes, version resolution, duplicate and conflicting redelivery, transport and device mismatch, bounds, command delivery classification (`CONFIRMED`, `NOT_SENT` with a code, `UNCERTAIN`), verbatim payload bytes, ACK decode, connectivity, diagnostics counters, and the dependency rules of section 3. The report lists every capability as `PASS`, `FAIL` or `NOT SUPPORTED` and an adapter is never reported conformant for a capability it does not declare. It runs against MQTT JSON, HTTP/webhook and Simulator. A deliberately minimal in-test adapter (not registered in production, not selectable by assignment) proves that a new transport is added with **no change** to the Twin or Rule packages, checked by running the dependency rules and by the git diff of those packages being empty for the adapter's slice.

## 15. Adapter responsibility matrix (initial adapters)

| Concern | Owner | MQTT JSON | HTTP/webhook | Simulator |
|---|---|---|---|---|
| Subscribe, listen, connect, reconnect | adapter | Paho client and reconnect controller (existing) | servlet endpoints | in-process scheduler |
| Frame, size-bound and decode | adapter | JSON; the broker's own size limit; poison rules unchanged | JSON, 8 KiB bound | in-process objects |
| Device identity | adapter proves, core decides | topic scoping | per-device credential | in-process |
| Transport assignment check | core (`adapter.core`) | yes | yes | yes |
| Transport eligibility for command acceptance | core port, adapter-specific rule (section 10.8) | registry ONLINE and client connected (unchanged) | recent valid authenticated contact (60 s initial window) | in-process device running |
| Schema and version resolution | shared contract module | yes | yes | yes |
| Telemetry acceptance, dedupe, Twin, rules | core | unchanged | unchanged | unchanged |
| Command acceptance, authorisation, caps | core | unchanged | unchanged | unchanged |
| Claim, lease, retry, expiry, hold, history | core (dispatcher) | unchanged | unchanged | unchanged |
| Put the bytes on the transport and classify | adapter (`CommandEgress`) | Paho publish, QoS 1 | pull serving (section 10) | in-process handler |
| ACK decode | adapter | topic `.../commands/ack` | `POST .../acknowledgements` | in-process |
| ACK truthfulness, first-wins, timeout | core | unchanged | unchanged | unchanged |
| Connectivity source | adapter | retained birth and will | none; contact recency is an eligibility signal only (section 10.8) | simulated |
| Manual-acknowledge or poison semantics | adapter (transport) | acknowledge after processing; permanent versus transient exactly as today | HTTP status mapping: 4xx permanent, 5xx transient | n/a |
| Diagnostics | adapter | counters and connection state | counters and credential-failure reasons | counters |
| Verification, Twin, rules, AI | core | untouched | untouched | untouched |

HTTP status mapping for ingest: `200` accepted or exact duplicate (with `duplicate: true`, as P4.5), `400` malformed, `401` unauthenticated, `403` forbidden device or transport, `409` conflicting duplicate, `413` too large, `415` wrong content type, `422` invalid fields, `429` bounded concurrency exceeded, `503` transient core failure (the device should retry). A `4xx` is permanent for that message; a `5xx` or `429` is retryable.

## 16. Preserved Phase 4 guarantees (the "do not break" list)

Atomic acceptance; frozen payload and timestamp; claim, lease and fencing; the NOT_SENT-only retry policy; uncertain is never resent and holds later commands; bounded attempts and window; the database clock for lifecycle facts; first-ACK-wins and the persisted ACK timeout; convergence separate from ACK separate from actuation; the messageId inbox semantics; queue caps and the shared publish rate; the read-only lifecycle API; the trace projection. The Phase 3 rule suite must stay green with the outbox OFF and ON. No Phase 5 slice may change an accepted Phase 4 assertion.

## 17. Observability of adapters

`nexus.adapter.received{adapter,result}`, `nexus.adapter.rejected{adapter,reason}` (reason from the closed vocabulary), `nexus.adapter.contract{version}`, `nexus.adapter.egress{adapter,outcome}`, `nexus.adapter.auth.failures{reason}`; fixed tags, never a device or command id. `AdapterDescriptor.diagnostics()` is exposed read-only at `GET /api/v1/adapters` (adapter id, declared capabilities, supported contract majors, started or stopped, counters), with no credential material. Lifecycle traces for HTTP-served commands reuse the Phase 4 projector (the `PUBLISHED` fact carries the evidence kind in its reason code).

## 18. Security statements and limits

Device-adapter authentication only (D2); no user or role model; credentials never in URLs or logs; TLS terminated in front of NEXUS; credential administration only through a local administrative command (D7, approved); a device holding a valid credential can submit false telemetry for itself (the device is trusted for its own data); there is no non-repudiation. None of this is a claim that NEXUS is secure.

## 19. Not claimed

Exactly-once publication or delivery; device receipt or physical actuation from any transport; hardware validation of the ESP32 library; confidentiality without TLS; platform-wide authentication; production load, soak or scale; support for any transport beyond the three initial adapters; HTTP command delivery before section 10 is implemented and proven.

## 20. Required design spikes before the dependent code (P5.1 and P5.4 entry conditions)

A spike is a time-boxed, disposable experiment whose result is written into the ledger evidence; none produces production code. Each names the claim it can falsify.

| ID | Spike | Falsifies | When |
|---|---|---|---|
| **S1 (PASSED 2026-10-18, P5.V41)** | Apply the proposed V41 to a disposable copy of the V40 schema. Insert an outbox row for a device assigned `HTTP_WEBHOOK` with the logical topic; run the V37 CHECKs and the V37/V39 guard triggers through claim, PUBLISHED, retry and dead-letter transitions | "no change to V37 to V40 is needed" (10.12, section 12) | P5.1 records the on-paper analysis only (the V37 topic CHECK and guards do not reference transport); the empirical run is part of the separately authorised V41 stage and the compatibility stays **unproven until then** |
| **S2** | Read `OutboxDeliveryOperations`/`CommandStateHistory` for how `reason_code`, the message text and the history `PUBLISHED` fact are chosen; prove an evidence kind can be passed without altering existing MQTT assertions | "the evidence kind needs no schema change" | P5.1 |
| **S3** | A throwaway servlet and a real Tomcat: write and flush a 1 KB response while the client (a) reads it, (b) never reads, (c) closes before the write, (d) resets mid-body; record what the application can observe in each case | the classification of 10.5 | before P5.4 outbound |
| **S4** | The Phase 4 process-kill harness extended with a pull handler stub: kill before the claim, after the claim, during the write and after the write; check the five rows of 10.7 against the durable state | the kill-behaviour table | before P5.4 outbound |
| **S5** | Long-poll cost: N concurrent waiting pulls on the bounded executor with no database connection held; measure threads and pool use under the F1-shaped load | 10.10 (no connection or lock while waiting) | before P5.4 outbound |
| **S6 (inventory PASSED 2026-10-18, P5.2; the window half waits for P5.4)** | Find every acceptance-time connectivity and client-connected check (`CommandExecutor.prepare` and callers) and prove they can sit behind `TransportEligibility` without changing Twin or Rule behaviour; measure the polling cadence and finalise the 60-second window | 10.8 and D9 | P5.2 (inventory), before P5.4 outbound (window) |
| **S7** | Cross-implementation hashing test: the CLI and the backend compute the same `HMAC-SHA-256(pepper, secret)` for a corpus, constant-time compare, pepper missing behaviour | 9.8.1 | P5.4 |
| **S8** | Measure the current per-class cost of a PostgreSQL-only integration class with and without a shared container, and project the segmented backend check | section 21 estimates | P5.1 |

Results are recorded in the P5-FINAL-style evidence section of the ledger before the slice that depends on them starts coding. A spike that falsifies its claim stops the dependent work and is reported.

## 21. Validation: memory-safe complete backend check (D10)

Phase 4 already ships `backend_batches.py` (discovery by the Surefire name patterns, deterministic sequential segments, bounded heap and Spring-context cache, a free-memory floor, per-segment report parsing). Phase 5 **uses it unchanged in principle and extends it only by addition**: (1) every discovered class must have a report, none may repeat, none unexpected may run; (2) a failing, crashed, timed-out or report-less segment fails the whole backend check, and the official full gate cannot pass with a missing segment; (3) the gate output and the ledger evidence record the number of segments, classes, tests, the per-segment and total seconds, and the lowest free memory observed; (4) the new Phase 5 classes join the same discovery automatically and no class is excluded to save time; (5) the existing resource limits (1,400 MB heap per segment, 8 cached contexts, 1,800 s budget, 1,200 MB free-memory floor) stay unless a separate measured justification is approved by the owner (decision D10 budget clause). The measured baseline is 1,541 s for 219 classes and 1,746 tests; every Phase 5 slice's evidence states the new total and the difference from that baseline, so the cost of Phase 5 is visible slice by slice.
