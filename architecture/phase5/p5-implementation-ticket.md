# Phase 5 implementation ticket

Date: 2026-10-18.

Status: **P5.1, P5.V41, P5.2, P5.3, P5.4 (ingest and credentials; outbound pull deferred), P5.5, P5.6, P5.7 and P5.8 IMPLEMENTED (2026-10-18; see the ledger evidence and its P5.8 disclosures). Phase 5 is complete; Phase 6 was not started and is in sequence by the owner's execution authorisation of 2026-10-18 and are not yet started.** The remainder of this paragraph is the P5.0 amendment text: P5.0 AMENDED (second revision, 2026-10-18; documentation only). Decisions D7 to D10 and the contract-version refinement are resolved (section 6); the design spikes that must run first are listed with P5.1 and in contract section 20. Production work needs a separate authorisation after the 2026-10-20 review and the integration of the Phase 4 branches into `main` in the order P4.5, P4.6, P4-FINAL.

Normative authority: [adapter-contract.md](adapter-contract.md). Acceptance authority: [p5-acceptance-ledger.md](p5-acceptance-ledger.md) (158 scenarios, all PLANNED: the original 128 unchanged, 24 added by the amendment and 6 by the P5.1 preparation decisions). This ticket owns slices, dependencies, estimates and the risk register; it does not override contract clauses.

## 1. Entry gate for every slice

1. Inspect branch, HEAD, status and index; preserve expected handoff-document changes and any user work.
2. Confirm the protected paths are untouched: `firmware/**`, `hardware-tests/**`, `results/**`, Review III artefacts and the user-owned documents (`CLAUDE.md`, `NEXT_TASK.md`, `PROJECT_HANDOFF.md`, `CODEX_HANDOFF.md`, `ROADMAP.md`). `tests/test_protected_paths.py` checks the first group by content hash.
3. Do not query, migrate, repair, reset or recreate the development PostgreSQL. Do not run `start.bat`, `start_nexus.py` or `seed.ps1`. Use disposable PostgreSQL and Mosquitto.
4. Never change the checksum of V1 to V40. V41 is created only by the separately authorised work package `P5.V41` (not by P5.1), and only as designed in contract section 12 after its empirical spike S1.
5. Work in a worktree on a branch created from the integrated Phase 4 head, one slice per commit series, never on `main`.
6. No staging, commit, tag or push unless explicitly requested for that operation.
7. Validate with the memory-safe procedures of Phase 4: Phase 3 rule suite in sequential batches with the outbox OFF and ON, the backend check through `backend_batches.py`, one Maven process at a time, bounded heap, the full gate launched detached.

## 2. Baseline dependency map

Paths are under `backend/src/main/java/com/nexus/` unless stated.

| Source area | Current role | Phase 5 treatment |
|---|---|---|
| `infrastructure/mqtt/TelemetryMQTTListener.java` (360 lines) | Subscription, JSON decoding, validation, topic check, poison classification, manual acknowledge, status and ACK routing, dispatch to services | Split: transport stays, decoding moves to `adapter.mqtt` behind `TelemetryDecoder`; behaviour must be identical (P5.3) |
| `infrastructure/mqtt/MQTTConfiguration.java`, `MqttReconnectController.java` | Single client, reconnect | Owned by `AdapterLifecycle` of the MQTT adapter; unchanged logic |
| `infrastructure/mqtt/PahoBrokerCommandPublisher.java`, `action/outbox/BrokerCommandPublisher.java` | The classified publish seam | Generalised to `CommandEgress`; the MQTT implementation keeps its NOT_SENT and UNCERTAIN table |
| `action/outbox/CommandOutboxDispatcher.java`, `OutboxDeliveryOperations.java` | Claim, lease, retry, expiry, hold | Calls `CommandEgressRouter` instead of the publisher bean; claim logic untouched |
| `device/command/DeviceCommandAckMessageHandler.java`, `action/outbox/CommandAckFacts.java` | ACK handling | Reached through `AcknowledgementIntake`; unchanged |
| `device/connectivity/DeviceStatusMessageHandler.java`, `DeviceConnectivityRegistry` | MQTT presence | Reached through `ConnectivityIntake`; HTTP presence is a separate, named fact (contract 10.8) |
| `telemetry/api/TelemetryController.java` | Open `POST /telemetry` | Unchanged; the authenticated HTTP adapter is a new endpoint family |
| `telemetry/application/TelemetryService.java` | Authoritative intake, dedupe | Unchanged |
| `device/domain/Device.java`, `DeviceMode` (SIMULATED, REAL), templates | Device identity and mode | Gains a separate `transport` (V41); mode is not reused |
| `action/application/executor/CommandExecutor.java` | Acceptance guards incl. MQTT connectivity and client-connected checks | The MQTT-specific "client connected" guard must become transport-aware (see risk R5.6) |
| `simulator/src/nexus_simulator/command_handler.py`, `config.py` | 300 s one-sided freshness | Corrected in P5.5 (contract D4 finding) |
| `firmware/**` | Protected | Read-only source of the golden fixtures |
| `validate_nexus.py`, `backend_batches.py`, `tests/` | Gate and tooling | Extended with the Phase 5 checks |

New packages: `adapter.spi`, `adapter.core`, `adapter.mqtt`, `adapter.http`, `adapter.simulator`; new top-level directories `contracts/`, `sdk/python/` (the Python SDK and CLI), `sdk/esp32/`.

## 3. Slice dependency graph

```text
P5.0 (this) -> P5.1 -> P5.2 -> P5.3 --+--> P5.4 (HTTP ingest; pull only after section 10 sign-off) --+
                 |                    |                                                              |
                 |                    +--> P5.5 (simulator adapter, Python SDK, D4) --> P5.6 (ESP32) |
                 |                                                                                   |
                 +-----------------------------------------------------------------------------------+--> P5.7 (conformance) --> P5.8 (integration)
```

Strict order: P5.1, P5.2, P5.3 (the risky extraction), then P5.4 and P5.5 (independent, sequential on this machine), P5.6 (needs P5.1 and P5.5), P5.7 (needs P5.3, P5.4, P5.5), P5.8 (needs all).

## 4. Slices

### P5.0 Contract, ledger and decisions (this change)

Documents only: contract, ticket, ledger. No source, no schema file, no migration, no SDK. Exit: owner review of the documents and of the open decisions of section 6.

### P5.1 Versioned wire contract, schemas, fixtures and the local validator

Authority: contract 6 to 8, 13. Ledger: sections A, B (most), L (part), M. Scenarios: 33. The eight migration scenarios of section J are **not** in this slice (work package `P5.V41`, below).

Deliverables: `contracts/v1/` JSON Schemas for **telemetry, command, acknowledgement and status** (the status schema models the existing plain-text payload; there is **no** `connectivity.schema.json`), the ESP32 legacy command profile, `contracts/versions.json`; the shared rule module (device equality, freshness with an injected reference time, status trimming); `contracts/fixtures/firmware-v1/` with provenance sidecars and the drift detector; the Python package `nexus_contract` with the `validate` CLI, the layered result of contract 8.2 and a bounded `validate-corpus` mode; the backend-side differential tests of contract 8.3 against the unmodified decoders, and `contracts/parity/known-differences.json`. **Not in this slice:** any migration (V41), any Java JSON Schema dependency, any change to backend, frontend, firmware or simulator production code.

**Design spikes (contract section 20):** S1 is **not** run in P5.1: only its on-paper result is recorded (the V37 topic CHECK and guard triggers do not mention transport) and the compatibility is unproven until the empirical run in `P5.V41`. S2 (how `reason_code` and the outcome message are chosen, to carry an evidence kind without touching MQTT assertions), S8 (cost of a PostgreSQL-only class with and without a shared container, and the projected segmented backend time). A falsified spike stops the dependent work and is reported before code. Also in this slice: the strict known-field validation and `requires` rules of contract 6.1, and the extension of `backend_batches.py` by addition only (contract 21: required reports, no omitted or duplicated class, the full gate failing on any missing or failed segment, and the transparent time record).

Verification: fixtures from the firmware source at the baseline, accept and reject, both directions; version-resolution table; evolution test; the Python validator and the **real backend decoders** agree on the whole corpus except registered differences (contract 8.3); mutation on a schema.

Gate: contract tests, tooling tests, `--fast`.

### P5.V41 Migration V41 (IMPLEMENTED 2026-10-18; not part of P5.1)

Authority: contract 12. Ledger: section J (P5-111 to P5-118, all PLANNED). Prerequisite for the P5.2 transport assignment API and for P5.4. Includes the empirical spike S1 (apply the proposed SQL to a disposable V40 copy and push an HTTP-assigned device's outbox row through every V37/V39 CHECK and guard trigger) and produces V41 only if it passes. Order: P5.1, then P5.V41, then P5.2. The owner decides the authorisation; nothing here creates a migration.

### P5.2 Adapter SPI, registry, intake ports and boundary tests (no behaviour change) (IMPLEMENTED 2026-10-18)

Authority: contract 3 to 5. Ledger: sections C, K, L (parts). Scenarios: 16.

Deliverables: `adapter.spi` (interfaces and records); `adapter.core` (registry, assignment check, intake bridges, egress router, descriptor and diagnostics); the architecture tests of section 3; the fixed rejection vocabulary; the device transport assignment API (read and a guarded update that rejects unknown transports).

Also: the inventory half of spike S6 (every acceptance-time connectivity and client-connected check, with file and line) and the `TransportEligibility` port with the unchanged MQTT rule behind it (contract 10.8); the MQTT behaviour must stay identical and no Twin or Rule class may change.

Constraint: wiring only. No existing class changes behaviour; the dispatcher still receives the MQTT publisher through the router with a single implementation.

Gate: architecture tests; the whole existing backend suite unchanged; Phase 3 suite OFF and ON.

### P5.3 Move MQTT JSON onto the SPI (IMPLEMENTED 2026-10-18; dispatcher routing deferred to P5.4, see contract 16)

Authority: contract 4, 5, 15, 16. Ledger: section D, K (parts). Scenarios: 13.

Deliverables: `adapter.mqtt` (`MqttJsonAdapter`: decoder, lifecycle, egress, connectivity) extracted from `TelemetryMQTTListener` and `PahoBrokerCommandPublisher`; the reference copy of the old decoder kept in test code for the differential run; `GET /api/v1/adapters`.

Risk and control: this is the slice most likely to change behaviour silently. Controls: the existing listener, publisher, ACK, status, outbox, crash-smoke and lifecycle tests pass **with no assertion edited**; a differential test over a generated corpus; the Phase 3 suite in both modes; manual acknowledge ordering proven on a real broker.

### P5.4 HTTP/webhook adapter: ingest, credentials, and (only if the pull design survives its spikes) pull delivery (PART 1 IMPLEMENTED 2026-10-18; PART 2 DEFERRED: ingest-only, 25 scenarios DEFERRED with reason)

Authority: contract 9, 10, 11, 9.8.1. Ledger: sections E, F, L and B (part). Scenarios: 52.

Part 1, ingest and credentials (D2, D7): the three ingest endpoints under `/api/v1/device-adapter/`; credential verification with the HMAC-SHA-256 pepper design; the `nexus-credentials` administrative CLI (create, rotate, revoke, list, audit) with its dedicated least-privilege database role, one-time secret display, owner-only output file, audit events, and the architecture tests that the backend and the CLI expose no network surface for credential administration; bounds, status mapping, the bind guard, the `last_valid_contact_at` write (at most every 5 s per device, database clock) for D9; cross-implementation hashing test (spike S7).

Part 2, outbound pull (D8, D9): **entry condition:** spikes S3 (what Tomcat can observe), S4 (process-kill behaviour of a pull handler), S5 (long-poll cost without a held connection) and S6 (eligibility window against the measured polling cadence) are complete and recorded, and the owner has authorised the outbound part. Then: `POST .../commands/pull` with the exact transitions of contract 10.3; the `HTTP_SERVED` evidence kind and the additive `delivery.evidenceKind` / `deliveryEvidence` members with the updated statements and frontend labels (contract 11); the `TransportEligibility` implementation for HTTP (a valid authenticated contact within the 60-second initial window, finalised from S6) behind the core port. If any spike falsifies the design, Part 2 is not built: the HTTP adapter is ingest-only, the section F and L pull scenarios are marked DEFERRED with the reason, and the conformance report says "command egress: not supported".

Gate: negative authentication, impersonation, ingest and credential-CLI tests; fault injection for the five kill points; the Phase 3 suite with the outbox OFF and ON in sequential batches; the Phase 4 suites and the crash smoke unchanged.

### P5.5 Simulator adapter, Python SDK and the freshness correction (D4) (IMPLEMENTED 2026-10-18)

Authority: contract 2 (D4), 6, 8, 14. Ledger: section G. Scenarios: 9.

Deliverables: the in-process simulator adapter; `nexus-device-sdk` (publisher with `contractVersion` and stable `messageId`, command and ACK handler, local validation, duplicate-redelivery hook); the freshness fix (30 s, absolute difference) with injected-clock boundary tests; the existing simulator tests adjusted only where they relied on 300 s, each stating why.

### P5.6 ESP32 helper library and reference device (IMPLEMENTED 2026-10-18)

Authority: contract 2 (D1), 13. Ledger: section H. Scenarios: 10.

Deliverables: `sdk/esp32/` (PlatformIO library with Arduino compatibility, samples, reference device); a host-side native build that runs the golden corpus; the freshness agreement test with the Python SDK and the backend. Physical flashing is out of scope; nothing claims hardware validation.

### P5.7 Adapter conformance suite (IMPLEMENTED 2026-10-18)

Authority: contract 14. Ledger: section I, K (parts). Scenarios: 9.

Deliverables: the reusable kit; the reports for the three adapters; the minimal in-test adapter and the empty-diff proof for the Twin and Rule packages; the mixed-fleet test.

### P5.8 Integrated validation, mutation campaign and documentation (IMPLEMENTED 2026-10-18)

Authority: contract 16 to 19. Ledger: section K, E and H (claims), I (mutation). Scenarios: 8.

Deliverables: the Phase 5 gate checks; the full Phase 3 suite OFF and ON in batches; the mutation campaign (at least ten); the claims audit over the new documents, READMEs and UI strings; the protected-path verification; the operator guide for adding a transport and for credential administration; the handoff draft.

## 5. Phase 5 scenario inventory

| Section | Subject | Count |
|---|---|---|
| A | Wire contract and JSON Schemas | 14 |
| B | Versioning and legacy-v1 | 12 |
| C | SPI, capability split, boundaries | 12 |
| D | MQTT JSON equivalence | 10 |
| E | HTTP ingest and credentials | 18 |
| F | HTTP command pull | 16 |
| G | Simulator adapter and Python SDK | 10 |
| H | ESP32 library | 10 |
| I | Conformance and extensibility | 8 |
| J | Migration V41 | 8 |
| K | Observability, operations, gate | 10 |
| L | Amendment scenarios (D7 to D10, version refinement) | 24 |
| M | P5.1 preparation decisions (command profiles, layers, status, parity) | 6 |
| | **Total (unique IDs P5-001 to P5-158)** | **158** |

All 158 are PLANNED. By work package: P5.1 33, P5.V41 8, P5.2 16, P5.3 13, P5.4 52, P5.5 9, P5.6 10, P5.7 9, P5.8 8. The IDs P5-001 to P5-128 are the original P5.0 set; P5-129 to P5-152 were added by the D7 to D10 amendment and P5-153 to P5-158 by the P5.1 preparation decisions. None was removed or renumbered; the eight section J scenarios changed work package only.

## 6. Decisions: resolved, and what is still open

Resolved in the amendment review (2026-10-18), with the contract section that now holds the wording:

- **D7 approved:** credentials are created, rotated and revoked only by a local administrative CLI; no unauthenticated or publicly reachable REST endpoint and no hidden HTTP interface (contract 9.8, 9.8.1).
- **D8 conditionally approved:** HTTP pull stays a Phase 5 objective as an authenticated POST; the ten pre-implementation requirements are answered in contract section 10 (exact transitions 10.3, HTTP versus broker 10.5 and 10.6, kill behaviour 10.7, polling retries 10.9, schema check 10.12) and are confirmed by spikes S1 to S5 before any outbound code; if the Phase 4 safety contract cannot be met HTTP stays ingest-only with the outbound scenarios DEFERRED (10.13).
- **D9 approved with refinement:** command eligibility from a recent valid authenticated contact on backend time, initial window 60 seconds, finalised against the measured polling cadence (spike S6); an eligibility signal, not connectivity; invalid or revoked credentials, malformed requests and inactive devices never establish it; implemented through a core `TransportEligibility` port, with no Twin or Rule change (contract 10.8).
- **D10 approved:** bounded sequential backend segments with exhaustive discovery, no passing with a missing or failed segment, existing limits kept, additional time recorded (contract 21; section 7 below).
- **Contract-version refinement:** strict known-field validation for a newer minor; unknown optional fields ignored and counted; unsupported semantics rejected through `requires` (contract 6.1); legacy firmware unchanged.

Still open (none blocks P5.1):

1. **Evidence labelling for external Phase 4 clients.** The state stays `PUBLISHED` for an HTTP-served command and the evidence kind is added as new members (contract 11). A client outside this repository that ignores the new members would still read `PUBLISHED`. Accept this residual risk with release notes, or require a new state value (which needs a migration of the V37 constraints)? The recommendation is to accept it: there is no external client in the repository and HTTP devices exist only after an explicit operator assignment.
2. **Final value of the eligibility window** (60 s initial) after the cadence measurement in spike S6, and whether a device may configure its own cadence.
3. **Credential CLI implementation language** (Python sharing the hashing specification, or a Spring-free Java main) and the operator procedure for creating the two database roles; both are decided in the P5.4 design with spike S7.
4. **Pepper management** in deployments (environment variable versus secret file) and the procedure for changing it (it invalidates every credential).
5. **Gate budget outcome** from spike S8: shared containers alone, or a split of the backend check. No timeout is raised without a separate measured justification (D10).
6. **Hardware validation of the ESP32 library** remains outside Phase 5; a later, separately authorised flashing exercise is needed before any hardware claim.

## 7. Validation cost estimates and resource impact

Measured baseline (P4-FINAL gate on the 16 GB development machine with a 5 GB WSL limit): backend check 1,541 s for 219 classes and 1,746 tests in 10 batches (about 7 s per class, dominated by starting a PostgreSQL and a Mosquitto container and a Spring context per integration class); the whole `--full` gate about 30 minutes; lowest free memory between batches 5,104 MB; Phase 3 suite 776 tests in 14 batches of about 60 s each, once per outbox mode.

| Slice | New or changed test classes (estimate) | Extra backend time (no container sharing / with sharing) | Other cost |
|---|---|---|---|
| P5.1 | 6 unit (fast), 2 migration (container each) | +30 s / +25 s | Python and Maven contract tests, seconds |
| P5.2 | 4 (architecture, unit; no containers) | +5 s | none |
| P5.3 | 3 integration (broker), the existing MQTT classes re-run | +60 s / +40 s | Phase 3 suite OFF and ON re-run: about 30 minutes, outside the gate |
| P5.4 | 8 to 10 integration (PostgreSQL only, no broker), 2 fault-injection | +130 s / +70 s | the crash campaign grows by about 4 tests (tagged `crash`, not in the backend check) |
| P5.5 | 2 integration; about 25 simulator pytest cases | +25 s | simulator suite stays under 10 s |
| P5.6 | 0 backend; host-native C++ build and about 20 cases | none in the backend check | adds a PlatformIO native build step (a new tool dependency; offline cache must be confirmed) |
| P5.7 | 4 (one parametrised kit run per adapter) | +45 s / +30 s | none |
| P5.8 | 2 | +10 s | mutation campaign: about 12 runs of 30 to 90 s each, outside the gate |
| **Total** | **about 30 backend classes** | **about +300 s without sharing, +180 s with** | |

D10 reporting rule: every slice's evidence and the gate output state the number of segments, classes and tests, the seconds per segment and in total, the lowest free memory observed, and the difference from the 1,541 s baseline, so the additional execution time of Phase 5 is visible slice by slice. A segment that is missing, failed, timed out or without a report fails the backend check and therefore the full gate.

Consequence: 1,541 s plus the estimate gives about 1,720 to 1,840 s, i.e. at or over the unchanged 1,800 s budget. The mitigation planned in P5.1 is to give the new PostgreSQL-only classes a shared container (as `CrashInfra` does for the crash campaign) and to keep the broker-dependent classes few; if the measured total still exceeds 1,750 s the gate is split into two named checks (core and adapters) rather than raising a timeout, which, as a change of the limits, needs the owner's separate approval on the measured numbers (D10).

Memory: each batch keeps its 1.4 GB heap bound; the HTTP tests need no Mosquitto container, so they are lighter than the MQTT classes; the ESP32 native build is a small C++ process. No new long-lived service is added to the gate.

Wall-clock for a Phase 5 milestone commit: about the same 30 minutes for `--full`, plus the two Phase 3 batch runs (about 30 minutes in total, only on slices that touch delivery: P5.3, P5.4, P5.8).

## 8. Phase 5 risk register

| ID | Risk | Likelihood | Impact | Control | Slice |
|---|---|---|---|---|---|
| R5.1 | The MQTT extraction changes poison handling, acknowledgement order or classification | Medium | High (silent data loss or redelivery storms) | No assertion edited; differential test over a corpus; Phase 3 and 4 suites; manual-ack proof on a real broker | P5.3 |
| R5.2 | A dependency creeps from a transport package into the core (or the reverse) | Medium | High (defeats exit criterion E1) | Architecture tests fail the build; reviewed in every slice | P5.2 on |
| R5.3 | Version policy ambiguity (what N means; silent reinterpretation of an unsupported major) | Medium | High | Total resolution function with a table test; N defined as a major; listed supported versions tested against the code | P5.1 |
| R5.4 | Schemas drift between backend, CLI and the ESP32 library | High | Medium | One authoritative directory; byte-equality build test; differential corpus; drift detector on the firmware source | P5.1 |
| R5.5 | Credential leak or timing oracle in the HTTP adapter | Medium | High | Hash only; constant-time comparison; dummy compare for unknown ids; log scan test; no credential in URLs; negative suite | P5.4 |
| R5.6 | HTTP devices have no observable presence, so the acceptance connectivity guard and the client-connected check are MQTT-shaped | High | High (blocks HTTP commands) | D9 resolved: a `TransportEligibility` core port, HTTP rule = valid authenticated contact within 60 s on the database clock; spike S6 inventories every guard site first; if it cannot be made transport-aware without touching Twin or Rule, HTTP is ingest-only | P5.2, P5.4 |
| R5.7 | Pull delivery weakens Phase 4 truthfulness (a served response read as device receipt) | Medium | High | Evidence kinds named in every statement; `HTTP_SERVED` defined as weaker than the broker; conservative UNCERTAIN; never re-serve | P5.4 |
| R5.8 | A device that polls slower than the delivery window loses commands | High | Medium | Visible EXPIRED dead letters; documented poll interval; SDK enforces at most 5 s | P5.4, P5.6 |
| R5.9 | Credential administration without platform authentication lets any caller mint a credential | High if exposed | High | D7 resolved: local CLI only, least-privilege database role, no network surface (architecture tests), audit events; residual: anyone with the operating-system account and database login can run it, stated as an operations privilege | P5.4 |
| R5.10 | V41 touches `devices`, a hot table, or conflicts with the V37 topic CHECK | Low | High | Constant-default column (no rewrite); empirical spike S1 before authoring; populated-database test; forward-only | P5.V41 |
| R5.11 | The simulator freshness fix breaks tests or users that depended on 300 s or on future-dated commands | Medium | Low | The fix is approved (D4); tests state their window explicitly; release note | P5.5 |
| R5.12 | The gate exceeds its 1,800 s budget or exhausts memory again | High | Medium | Measured plan in section 7; shared containers; split check only with approval; bounded batches | all |
| R5.13 | The ESP32 library works on the host but not on a device (timing, memory, RTC, Wi-Fi) | Medium | Medium | No hardware claim; host-only validation stated everywhere; a documented hardware checklist for later | P5.6 |
| R5.14 | PlatformIO native tooling is unavailable offline or heavy on this machine | Medium | Medium | Confirm the offline cache in P5.1; fall back to a plain CMake host build with the same corpus | P5.6 |
| R5.15 | Over-claiming: "secure", "exactly once", "hardware validated", "supports any transport" | Medium | High | Section 19 of the contract; claims audit extended to every new document, README and UI string | P5.8 |
| R5.16 | F1 (connection-pool exhaustion) interacts with a new request-handling path | Low to medium | Medium | HTTP intake uses the same transaction boundaries as MQTT; bounded per-device concurrency; F1 stays tracked and is not claimed fixed | P5.4 |
| R5.18 | An external Phase 4 client reads `PUBLISHED` for an HTTP-served command and shows it as an MQTT publication | Low | Medium | Additive `evidenceKind` members, explanation sentences and `ActionHistory.message` that name HTTP, this repository's frontend labelled by evidence, release notes; open question 1 | P5.4 |
| R5.19 | The 60-second eligibility window accepts commands for a device that has gone away (a false positive), or rejects a slow poller (a false negative) | Medium | Medium | The cost of a false positive is a visible EXPIRED dead letter, never a late delivery (window at most 20 s); window bounded 15 to 300 s and finalised from the cadence measurement (S6) | P5.4 |
| R5.20 | A framework write that succeeds is mistaken for receipt | Medium | High | `HTTP_SERVED` defined as weaker than the broker confirmation and named in every statement; uncertain by default; spike S3 turned into tests | P5.4 |
| R5.21 | A newer-minor payload smuggles semantics a receiver silently ignores | Low | Medium | Strict known-field validation, `requires` rejection, counted unknown fields (contract 6.1) | P5.1 |
| R5.17 | Scope creep into later adapters (Modbus, BLE, OPC UA, serial, CAN, vendor clouds) | Medium | Medium | D5 defers them; the conformance kit and the test adapter make them cheap later, not now | all |

## 9. What is not authorised by this ticket

Any source, schema, migration, SDK or test change; any change to `firmware/**`, `hardware-tests/**` or `results/**`; any change to the user-owned documents; any merge, push or tag; the HTTP pull implementation (until D8 and D9). P5.0 is complete when the owner has reviewed these documents.

**Budget outcome (2026-10-18):** the measured Phase 5 backend check took 1,618 s and 1,922 s; the owner approved raising the backend-check budget from 1,800 s to 2,400 s (nothing else changed). See the ledger section "Backend budget adjustment".
