# Phase 5 device adapter and SDK contract

Date: 2026-10-18.

Status: **P5.0 DRAFT FOR REVIEW. DOCUMENTATION AND PLANNING ONLY. NO PRODUCTION CODE, NO MIGRATION, NO SCHEMA FILE AND NO SDK EXISTS YET.** Decisions D1 to D6 were approved by the project owner on 2026-10-18 (wording in section 2). Production implementation (P5.1 onward) needs a separate authorisation after the 2026-10-20 review and the integration of the Phase 4 branches into main.

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
| `ConnectivitySource` | `decode(RawMessage) -> Decoded<ConnectivityObservation> \| Rejection` | Transport-observed presence (MQTT retained birth and will; HTTP: derived from authenticated activity, see section 10.8) | yes | limited | yes |
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

`TelemetrySource` (today `HTTP`, `MQTT`) gains values for the new transports only where a label is needed; it is a diagnostic label and never part of the dedupe key (Phase 4 R17 unchanged).

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

**Legacy-v1 compatibility policy (explicit).** Messages without `contractVersion` are interpreted exactly as the committed code interprets them today: the legacy numeric telemetry shape (`deviceId`, `timestamp`, `sensorType`, `value`, `unit`; numeric value; the topic and payload device ids must agree), the generic shape with `capabilityKey`, the optional `messageId` and `readbackCommandId`; the ACK shape `commandId`, `status` of `ACCEPTED` or `REJECTED`, epoch-second `timestamp`, optional `reason`; status as the plain text `online` or `offline`; commands as `commandId`, `capability`, `command`, `value`, epoch-second `timestamp`. Legacy-v1 acceptance rules never become stricter than today's, and never looser: the golden fixtures (section 13) pin both directions. New producers (SDKs, the reference device) emit `contractVersion`.

**Backward-compatible evolution rules.** Allowed in a minor: a new **optional** field; a new enum value **only** where the contract states consumers must tolerate unknown values; a relaxed maximum. Forbidden in a minor (needs a major): removing or renaming a field, changing a type, making an optional field required, changing the meaning of a value, narrowing an accepted range, changing a topic or an endpoint path. A schema-evolution test proves every fixture valid under 1.m stays valid under every later 1.x.

## 7. Wire contract v1 (baseline, from the committed firmware and backend)

The baseline is what the committed firmware sends and accepts; it was read from `firmware/src/main.cpp` and the backend validators at `e29e215`.

| Message | Direction | Content (v1) | Notes |
|---|---|---|---|
| Telemetry | device to NEXUS | `deviceId` (UUID), `timestamp` (ISO-8601 UTC), `sensorType`, `value` (number), `unit`; optional `capabilityKey`, `readbackCommandId`, `messageId` (1 to 64 chars from `A-Z a-z 0-9 . _ : -`) | the committed firmware sends the five legacy fields and **no** `messageId`, so firmware telemetry is not deduplicated; SDK producers add one |
| Command | NEXUS to device | `commandId`, `capability`, `command`, `value`, `timestamp` (integer epoch seconds, acceptance time) | device rejects if `abs(now - timestamp) > 30 s` with reason `STALE`, missing fields `MALFORMED`, unknown capability or command `UNSUPPORTED` |
| Acknowledgement | device to NEXUS | `commandId`, `status` (`ACCEPTED` or `REJECTED`), `timestamp` (integer epoch seconds, device clock), optional `reason` | a device claim: never used for latency (Phase 4 R13) |
| Status | device to NEXUS | the plain text `online` or `offline` (retained birth and last will on MQTT) | HTTP status is derived (section 10.8) |

**Rejection vocabulary (closed, fixed codes, also the metric tags):** `MALFORMED_JSON`, `INVALID_FIELDS`, `INVALID_MESSAGE_ID`, `INVALID_LEGACY_PAYLOAD`, `TOPIC_DEVICE_MISMATCH` (MQTT), `DEVICE_MISMATCH`, `TRANSPORT_MISMATCH`, `INVALID_CONTRACT_VERSION`, `CONTRACT_VERSION_CONFLICT`, `UNSUPPORTED_CONTRACT_MAJOR`, `PAYLOAD_TOO_LARGE`, `UNAUTHENTICATED`, `FORBIDDEN_DEVICE`, plus the core's existing `ONBOARDING_REJECTED`, `INVALID_TELEMETRY`, `DUPLICATE_CONFLICT`. The first group already exists as the Phase 4 `nexus.telemetry.permanent.rejections` reasons and keeps its spelling.

## 8. Schemas and the local validator (exit criterion E2)

- One authoritative directory `contracts/v1/` holds JSON Schema (draft 2020-12) files for telemetry, command, acknowledgement and status, and `contracts/versions.json` listing the supported versions of section 6. No schema is duplicated: the backend loads these files from the classpath copy produced by the build (a build step copies, a test proves byte equality), the Python validator reads them directly, and the ESP32 library's host tests read them.
- `python -m nexus_contract validate --kind telemetry payload.json` (and `--contract-version`) validates a payload locally without a backend, printing a fixed error code per failure and exiting non-zero on any. A `--legacy` flag applies the legacy-v1 policy.
- A backend contract test and the CLI run **the same fixture corpus** and must agree on every verdict (a differential test); a disagreement fails the build.
- JSON Schema cannot express every rule (the topic and payload device ids must agree; the 64-character `messageId` alphabet is expressible; the 30 s freshness depends on a clock). Rules a schema cannot hold are listed in the schema's `x-nexus-rules` annotation and implemented once in a small shared rule module whose cases are in the fixture corpus.

## 9. HTTP/webhook adapter: ingest and device credentials (D2)

Endpoints (all under `/api/v1/device-adapter/`, none in the existing `/api/v1/telemetry` namespace, which stays as it is): `POST /devices/{deviceId}/telemetry`, `POST /devices/{deviceId}/acknowledgements`, `POST /devices/{deviceId}/status`.

1. **Credential presentation.** `Authorization: Bearer <key>` only. A key in a URL, a query string, a path segment or a log line is a defect. The server never logs the header or the key, and never echoes it.
2. **Key format.** `nxk1_<lookupId>_<secret>`: a public, non-secret lookup id (so verification is one indexed read) and a 256-bit random secret (base64url). The server stores the lookup id and a one-way hash of the secret only (section 12); the plaintext key is shown **once**, at creation or rotation.
3. **Verification.** Compare the stored hash with the hash of the presented secret using a constant-time comparison; an unknown lookup id performs a dummy comparison so timing does not reveal existence. A revoked, expired or retired credential is `UNAUTHENTICATED`. Failures are counted per fixed reason with no device identifier as a tag.
4. **Per-device authorisation and impersonation.** The authenticated credential belongs to exactly one device. The path device id and the payload device id must both equal it; any other combination is `FORBIDDEN_DEVICE`, counted, and nothing is processed. A credential of device A can never submit for device B, even if A and B share a template.
5. **Transport assignment.** The device must be assigned `HTTP_WEBHOOK` (section 5). A device assigned `MQTT_JSON` receives `403` with `TRANSPORT_MISMATCH`; HTTP never silently becomes a second path for an MQTT device.
6. **Bounds.** Maximum request body 8 KiB (rejected with `413` and `PAYLOAD_TOO_LARGE` before parsing), JSON only, strict content type, a bounded read timeout, and a per-device concurrency cap of 2 in-flight requests. Rate limiting beyond that is a deployment concern (reverse proxy) in Phase 5 and is stated as such; the in-application limit protects the core, not the network.
7. **Rotation and revocation.** A device may hold at most two non-revoked credentials (one active, one retiring) so a device can rotate without an outage. Rotation creates the new credential and sets the old one to retire at a stated instant (default 24 h, maximum 7 days); revocation is immediate and final. Both are recorded in an append-only credential event table (section 12). A revoked credential is rejected on the very next request (no cache longer than 5 seconds; the cache is keyed by lookup id and holds only the hash).
8. **Credential administration surface.** Issuing, rotating and revoking credentials is an administrative act. NEXUS has no platform-wide authentication yet, so exposing those operations on the open API would let any caller mint a credential. P5.0 therefore proposes (decision **D7**, needs the owner's approval before P5.4): the operations are available only through a local administrative command (a Python and Maven CLI entry point run against the database), and the REST endpoints are not created until the platform has an authorisation story. This is stated as a limit, not hidden.
9. **TLS.** The application does not terminate TLS in Phase 5; real deployments terminate it in front of NEXUS and the documentation says so. Tests run over plain HTTP on loopback and say so; a test proves the adapter refuses to start with credentials enabled on a non-loopback bind address unless `nexus.adapter.http.require-forwarded-https` is set and the `X-Forwarded-Proto: https` header is present on each request (a documented, deliberately conservative guard).
10. **Idempotency.** Telemetry redelivery uses the existing `messageId` inbox. An ACK is idempotent by the first-ACK-wins rule. No other HTTP request changes state twice.
11. **Not claimed.** This does not authenticate the platform, does not authorise users, does not protect against a compromised device that holds its own valid credential, and offers no confidentiality without TLS in front.

## 10. HTTP command delivery: the pull safety contract (D5)

**Decision recorded.** HTTP outbound delivery **can** fit the Phase 4 contract safely, but only with a stated contract extension (section 11) and with every rule below. Until P5.4 is separately authorised and these rules are implemented and proven, the HTTP adapter does **not** declare `CommandEgress`; the conformance report will show "command egress: not supported". There is no push (webhook-to-device) delivery in Phase 5: a device behind NAT or a firewall cannot be reached, and a push to a device-controlled URL is a server-side-request-forgery risk. A device-initiated pull is the only candidate.

1. **Endpoint.** `POST /api/v1/device-adapter/devices/{deviceId}/commands/pull` (a state-changing call, therefore POST, never GET; no body is required; optional `waitSeconds` 0 to 10, default 0). Authentication, per-device authorisation, transport assignment and the size and concurrency bounds of section 9 apply. One pull in flight per device.
2. **No new obligation.** A pull creates no command. It can only serve a command that a Phase 4 acceptance already created in `command_outbox`, after all of the acceptance-time guards (rule authorisation, device state, admission caps) ran. An adapter cannot widen what is accepted.
3. **A pull is a delivery attempt.** Serving uses the **same** claim as the MQTT dispatcher: the publish gate, the single-in-flight-per-device index, the per-device ordering rule, the lease, the attempt count and the expiry check. A command whose delivery window has ended is never served; it is dead-lettered `EXPIRED` by the existing expiry sweep. No second code path may bypass the claim.
4. **Payload verbatim.** The response body is the stored payload bytes unchanged (same `commandId`, same acceptance-time `timestamp`, same SHA-256), with `X-Nexus-Contract-Version`. The timestamp is never refreshed at serve time, so a command that waited cannot be made to look fresh to the firmware's 30-second check.
5. **Outcome classification (conservative).** The adapter builds the complete response in memory before writing. If it fails before the first byte is written, the attempt is `NOT_SENT` (retry allowed within the window, as Phase 4 R33). If the first byte was written, the outcome is decided by whether the container reports the response fully written and flushed: success is `CONFIRMED` with evidence kind `HTTP_SERVED`; any error, reset, timeout or ambiguity after the first byte is `UNCERTAIN`, which the dispatcher dead-letters `UNCERTAIN_EXPIRED` and holds per R32. **A command that was served or whose serving is uncertain is never served again** (no replay of uncertain delivery). A device that lost the response cannot recover that command; it expires or is acknowledged by other means. This is the same trade the MQTT path makes and is stated to operators.
6. **What `HTTP_SERVED` means.** NEXUS wrote the complete response to the device's HTTP connection. It does **not** mean the device read, parsed, accepted or acted on it. There is no counterpart of an MQTT broker's QoS 1 acknowledgement; this is weaker evidence than `BROKER_CONFIRMED` and every statement must say so (section 11).
7. **ACK.** The device reports the result with `POST .../acknowledgements`, decoded and correlated by the unchanged core (`commandId` plus device, first ACK wins, late and conflicting ACKs recorded as facts, the persisted ACK timeout). Serving is not an ACK; an ACK is not convergence; convergence is not actuation.
8. **Presence.** An HTTP device has no connection the broker can observe. The core's connectivity registry is **not** driven by pulls or by ingest requests in Phase 5 (a request proves reachability at one instant, not presence). HTTP devices are `UNKNOWN` for connectivity unless they post an explicit `status`, which is a device claim like any other. Command acceptance's existing connectivity guard therefore needs a stated rule for HTTP-assigned devices: the proposal is that acceptance uses "last authenticated contact within a configured recency window" for HTTP devices, recorded as a new, clearly named fact rather than as MQTT presence. This is an open design question (risk R5.6 in the ticket) that must be settled before P5.4; if it cannot be, HTTP devices simply cannot receive manual commands in Phase 5.
9. **Freshness arithmetic.** The delivery window is at most 20 s and the firmware accepts a command up to 30 s old. A device that polls less often than the window cannot receive commands (they expire visibly). The documented operating mode is a long poll of up to 10 s with an immediate re-poll, which gives a worst-case pickup delay under the window. The SDK helpers enforce a maximum poll interval of 5 s.
10. **Bounds.** Response at most 4096 bytes (the V37 payload bound), at most one command per pull, no batching in Phase 5.
11. **Failure of the extension.** If P5.4 review finds that these rules cannot all be implemented and tested, the adapter ships ingest-only, the omission is recorded in the ledger as DEFERRED with the reason, and the conformance report says so.

## 11. Delivery evidence kinds: the Phase 4 extension

Phase 4 states that `PUBLISHED` means broker publication and nothing more. That statement stays true **for the MQTT adapter**. Phase 5 widens the *vocabulary*, not the MQTT guarantee:

1. The state `PUBLISHED` means "the adapter's transport reported the command handed over, with the evidence kind stated". The evidence kind is recorded as the `reason_code` of the `PUBLISHED` history fact: `BROKER_CONFIRMED` (existing, MQTT QoS 1 completion), `HTTP_SERVED` (section 10), `SIMULATOR_DELIVERED` (the in-process adapter). No schema change is required: `reason_code` is `^[A-Z0-9_]{1,64}$` and the fact type is unchanged.
2. Every platform sentence about delivery (the lifecycle statements, the API explanation, the frontend labels, the rule-match explanation) names the evidence kind. The existing MQTT wording is unchanged: "Published to the MQTT broker. Publication does not prove that the device received, accepted or executed the command." For HTTP: "Served over HTTP: the complete response was written to the device's connection. This does not prove the device read or acted on it."
3. The Phase 4 uncertain rules apply to every adapter unchanged: an ambiguous attempt is UNCERTAIN, ends `UNCERTAIN_EXPIRED`, is never resent, and holds later commands of the device for the R32 interval.
4. Nothing in Phase 5 may claim exactly-once delivery, device receipt or physical actuation; the architecture tests that guard those claims (Phase 4 P4-123) are extended to the new documents, strings and UI labels.

## 12. Migration V41 proposal (design only: NOT created in P5.0)

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
    last_used_at   TIMESTAMPTZ,                          -- coarse (updated at most once a minute), never per request
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
    actor         VARCHAR(64) NOT NULL                   -- a fixed label such as 'admin-cli'
);
-- update and delete guard triggers like command_state_history (V39): UPDATE rejected; DELETE only with the parent.
```

Design notes and open points (each becomes a ledger scenario):

- **The hash.** The key secret is 256 bits of randomness, so a fast one-way hash is sufficient (a slow password hash protects low-entropy secrets and would only cost CPU per request). The proposal is `HMAC-SHA-256(pepper, secret)` with a server-side pepper supplied from the environment, not the database, so a database leak alone does not allow offline verification. The hash is **one-way and is not an HMAC signing secret**; request signing, if ever added, needs a separate design (D2).
- **Migration cost.** Adding a `NOT NULL DEFAULT` column to `devices` and `device_templates` rewrites no rows on modern PostgreSQL (a constant default is stored in the catalogue). The populated-database migration test proves existing devices read `MQTT_JSON`.
- **No change to V37 to V40 tables.** `command_outbox.topic` keeps its logical address `nexus/devices/<id>/commands` for every transport (the CHECK in V37 binds the string, not the protocol). This must be re-verified in the P5.1 spike; if the CHECK blocks it, the finding is reported and the design is adjusted before any code.
- **Retention.** Credential events follow the credential (`ON DELETE CASCADE`); deleting a device removes its credentials. No credential row is purged by the retention job.
- **Rollback.** The migration is forward-only; the application ignores the new columns when the HTTP adapter is disabled, so disabling the feature is the rollback (the Phase 4 pattern).
- **Not in V41:** no mailbox table (the outbox is the mailbox), no new history fact type (section 11 uses `reason_code`), no nonce table.

## 13. Firmware golden-fixture strategy

1. **Provenance.** Fixtures live in `contracts/fixtures/firmware-v1/`, each with a sidecar recording the source: the git blob id of `firmware/src/main.cpp` at `e29e215` (already pinned by the Phase 4 protected-path hashes) and the line that builds it. Fixtures are *derived by reading the firmware source*, never by editing it.
2. **Drift detector.** A test parses `firmware/src/main.cpp` for the JSON keys it builds and the keys it requires in a command (`doc["..."]` patterns) and compares them with the fixture set and the schemas; if the firmware ever changes its key set, the test fails and names the key. The firmware tree hash is already immutable, so this is a guard for a future approved change.
3. **Both directions.** Accept fixtures (what the firmware sends today must validate under legacy-v1 and under the 1.0 schemas with `contractVersion` added) and reject fixtures (malformed JSON, wrong types, a non-UUID device id, a stale command) with the exact reason code the backend and the validator both produce.
4. **Semantic equality, not byte identity.** "Byte-compatible interpretation" means the same bytes parse to the same typed value; field order and whitespace do not matter. The ESP32 library's host build must produce documents that parse to the same typed values as the fixtures.
5. **Clock and freshness fixtures.** Command fixtures include the boundary cases (age 29, 30, 31 seconds; future-dated by 29, 30, 31 seconds) with the verdict the **firmware** computes; the simulator, the ESP32 library and the backend freshness helper must all agree (this is also the regression test for D4).
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
| Schema and version resolution | shared contract module | yes | yes | yes |
| Telemetry acceptance, dedupe, Twin, rules | core | unchanged | unchanged | unchanged |
| Command acceptance, authorisation, caps | core | unchanged | unchanged | unchanged |
| Claim, lease, retry, expiry, hold, history | core (dispatcher) | unchanged | unchanged | unchanged |
| Put the bytes on the transport and classify | adapter (`CommandEgress`) | Paho publish, QoS 1 | pull serving (section 10) | in-process handler |
| ACK decode | adapter | topic `.../commands/ack` | `POST .../acknowledgements` | in-process |
| ACK truthfulness, first-wins, timeout | core | unchanged | unchanged | unchanged |
| Connectivity source | adapter | retained birth and will | not driven by ingest (section 10.8) | simulated |
| Manual-acknowledge or poison semantics | adapter (transport) | acknowledge after processing; permanent versus transient exactly as today | HTTP status mapping: 4xx permanent, 5xx transient | n/a |
| Diagnostics | adapter | counters and connection state | counters and credential-failure reasons | counters |
| Verification, Twin, rules, AI | core | untouched | untouched | untouched |

HTTP status mapping for ingest: `200` accepted or exact duplicate (with `duplicate: true`, as P4.5), `400` malformed, `401` unauthenticated, `403` forbidden device or transport, `409` conflicting duplicate, `413` too large, `415` wrong content type, `422` invalid fields, `429` bounded concurrency exceeded, `503` transient core failure (the device should retry). A `4xx` is permanent for that message; a `5xx` or `429` is retryable.

## 16. Preserved Phase 4 guarantees (the "do not break" list)

Atomic acceptance; frozen payload and timestamp; claim, lease and fencing; the NOT_SENT-only retry policy; uncertain is never resent and holds later commands; bounded attempts and window; the database clock for lifecycle facts; first-ACK-wins and the persisted ACK timeout; convergence separate from ACK separate from actuation; the messageId inbox semantics; queue caps and the shared publish rate; the read-only lifecycle API; the trace projection. The Phase 3 rule suite must stay green with the outbox OFF and ON. No Phase 5 slice may change an accepted Phase 4 assertion.

## 17. Observability of adapters

`nexus.adapter.received{adapter,result}`, `nexus.adapter.rejected{adapter,reason}` (reason from the closed vocabulary), `nexus.adapter.contract{version}`, `nexus.adapter.egress{adapter,outcome}`, `nexus.adapter.auth.failures{reason}`; fixed tags, never a device or command id. `AdapterDescriptor.diagnostics()` is exposed read-only at `GET /api/v1/adapters` (adapter id, declared capabilities, supported contract majors, started or stopped, counters), with no credential material. Lifecycle traces for HTTP-served commands reuse the Phase 4 projector (the `PUBLISHED` fact carries the evidence kind in its reason code).

## 18. Security statements and limits

Device-adapter authentication only (D2); no user or role model; credentials never in URLs or logs; TLS terminated in front of NEXUS; credential administration through a local administrative command (D7 proposal); a device holding a valid credential can submit false telemetry for itself (the device is trusted for its own data); there is no non-repudiation. None of this is a claim that NEXUS is secure.

## 19. Not claimed

Exactly-once publication or delivery; device receipt or physical actuation from any transport; hardware validation of the ESP32 library; confidentiality without TLS; platform-wide authentication; production load, soak or scale; support for any transport beyond the three initial adapters; HTTP command delivery before section 10 is implemented and proven.
