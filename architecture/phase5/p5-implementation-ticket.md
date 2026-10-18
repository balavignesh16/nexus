# Phase 5 implementation ticket

Date: 2026-10-18.

Status: **P5.0 DRAFT FOR REVIEW (documentation only). P5.1 to P5.8 are NOT authorised.** Production work needs a separate authorisation after the 2026-10-20 review and the integration of the Phase 4 branches into `main` in the order P4.5, P4.6, P4-FINAL.

Normative authority: [adapter-contract.md](adapter-contract.md). Acceptance authority: [p5-acceptance-ledger.md](p5-acceptance-ledger.md) (128 scenarios, all PLANNED). This ticket owns slices, dependencies, estimates and the risk register; it does not override contract clauses.

## 1. Entry gate for every slice

1. Inspect branch, HEAD, status and index; preserve expected handoff-document changes and any user work.
2. Confirm the protected paths are untouched: `firmware/**`, `hardware-tests/**`, `results/**`, Review III artefacts and the user-owned documents (`CLAUDE.md`, `NEXT_TASK.md`, `PROJECT_HANDOFF.md`, `CODEX_HANDOFF.md`, `ROADMAP.md`). `tests/test_protected_paths.py` checks the first group by content hash.
3. Do not query, migrate, repair, reset or recreate the development PostgreSQL. Do not run `start.bat`, `start_nexus.py` or `seed.ps1`. Use disposable PostgreSQL and Mosquitto.
4. Never change the checksum of V1 to V40. V41 is created only by the slice that the owner authorises for it (P5.1), and only as designed in contract section 12 after its spike.
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

### P5.1 Versioned wire contract, schemas, fixtures, CLI and the V41 spike

Authority: contract 6 to 8, 12, 13. Ledger: sections A, B (most), J. Scenarios: 30.

Deliverables: `contracts/v1/*.schema.json`, `contracts/versions.json`; the shared rule module (device equality, freshness); `contracts/fixtures/firmware-v1/` with provenance sidecars and the drift detector; the Python package `nexus_contract` with the `validate` CLI; the backend contract test and the differential corpus test; the V41 spike: apply the proposed SQL to a disposable copy, prove the V37 topic CHECK accepts an HTTP-assigned device's command row, and produce V41 only if the spike passes and the owner has authorised it.

Verification: fixtures from the firmware source at the baseline, accept and reject, both directions; version-resolution table; evolution test; backend and CLI agree on the whole corpus; migration tests on an empty and a populated database; mutation on a schema.

Gate: contract tests, tooling tests, the migration tests, `--fast`.

### P5.2 Adapter SPI, registry, intake ports and boundary tests (no behaviour change)

Authority: contract 3 to 5. Ledger: sections C, K (parts). Scenarios: 15.

Deliverables: `adapter.spi` (interfaces and records); `adapter.core` (registry, assignment check, intake bridges, egress router, descriptor and diagnostics); the architecture tests of section 3; the fixed rejection vocabulary; the device transport assignment API (read and a guarded update that rejects unknown transports).

Constraint: wiring only. No existing class changes behaviour; the dispatcher still receives the MQTT publisher through the router with a single implementation.

Gate: architecture tests; the whole existing backend suite unchanged; Phase 3 suite OFF and ON.

### P5.3 Move MQTT JSON onto the SPI

Authority: contract 4, 5, 15, 16. Ledger: section D, K (parts). Scenarios: 13.

Deliverables: `adapter.mqtt` (`MqttJsonAdapter`: decoder, lifecycle, egress, connectivity) extracted from `TelemetryMQTTListener` and `PahoBrokerCommandPublisher`; the reference copy of the old decoder kept in test code for the differential run; `GET /api/v1/adapters`.

Risk and control: this is the slice most likely to change behaviour silently. Controls: the existing listener, publisher, ACK, status, outbox, crash-smoke and lifecycle tests pass **with no assertion edited**; a differential test over a generated corpus; the Phase 3 suite in both modes; manual acknowledge ordering proven on a real broker.

### P5.4 HTTP/webhook adapter: ingest, credentials, and (only if signed off) pull delivery

Authority: contract 9, 10, 11. Ledger: sections E, F, B (part). Scenarios: 34.

Deliverables: the three ingest endpoints; credential verification, rotation and revocation; the administrative command (decision D7); bounds and the bind guard; HTTP status mapping; if and only if contract section 10 is authorised separately: the pull endpoint, the `HTTP_SERVED` evidence kind and the extended statements. Without that sign-off the adapter is ingest-only and the section F scenarios become DEFERRED with the reason.

Gate: negative authentication tests, impersonation tests, fault injection for the serve outcomes, the Phase 4 suites unchanged.

### P5.5 Simulator adapter, Python SDK and the freshness correction (D4)

Authority: contract 2 (D4), 6, 8, 14. Ledger: section G. Scenarios: 9.

Deliverables: the in-process simulator adapter; `nexus-device-sdk` (publisher with `contractVersion` and stable `messageId`, command and ACK handler, local validation, duplicate-redelivery hook); the freshness fix (30 s, absolute difference) with injected-clock boundary tests; the existing simulator tests adjusted only where they relied on 300 s, each stating why.

### P5.6 ESP32 helper library and reference device

Authority: contract 2 (D1), 13. Ledger: section H. Scenarios: 10.

Deliverables: `sdk/esp32/` (PlatformIO library with Arduino compatibility, samples, reference device); a host-side native build that runs the golden corpus; the freshness agreement test with the Python SDK and the backend. Physical flashing is out of scope; nothing claims hardware validation.

### P5.7 Adapter conformance suite

Authority: contract 14. Ledger: section I, K (parts). Scenarios: 9.

Deliverables: the reusable kit; the reports for the three adapters; the minimal in-test adapter and the empty-diff proof for the Twin and Rule packages; the mixed-fleet test.

### P5.8 Integrated validation, mutation campaign and documentation

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
| | **Total (unique IDs P5-001 to P5-128)** | **128** |

All 128 are PLANNED. By slice: P5.1 30, P5.2 15, P5.3 13, P5.4 34, P5.5 9, P5.6 10, P5.7 9, P5.8 8.

## 6. Open decisions for the owner (beyond D1 to D6)

- **D7. Credential administration surface (needed before P5.4).** Proposal: a local administrative command only; no REST endpoint to create, rotate or revoke credentials until the platform has an authorisation model. Reason: with no platform authentication, an open endpoint would let any caller mint a device credential.
- **D8. HTTP command delivery sign-off (needed before the pull part of P5.4).** The design of contract section 10 and its evidence-kind extension (section 11) must be accepted as written, amended, or declined. Declining makes the HTTP adapter ingest-only; the conformance report will say so.
- **D9. Connectivity for HTTP devices (needed with D8).** Acceptance currently requires the MQTT presence of a device. Proposal: for HTTP-assigned devices acceptance uses "authenticated contact within a configured recency window", recorded as its own fact, not as MQTT presence. Alternative: HTTP devices cannot receive manual commands in Phase 5. Either must be chosen before any pull code.
- **D10. Gate budget.** Section 7 estimates that Phase 5 test classes may push the backend check towards or beyond 1,800 s. Decide in P5.1, from a measurement, whether to share containers, to split the check, or to ask for a budget change.
- **D11 (informational).** The simulator's current acceptance of future-dated commands is a behaviour change visible to existing simulator users; D4 already approves it.

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

Consequence: 1,541 s plus the estimate gives about 1,720 to 1,840 s, i.e. at or over the unchanged 1,800 s budget. The mitigation planned in P5.1 is to give the new PostgreSQL-only classes a shared container (as `CrashInfra` does for the crash campaign) and to keep the broker-dependent classes few; if the measured total still exceeds 1,750 s the gate is split into two named checks (core and adapters) rather than raising a timeout, which needs the owner's approval (D10).

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
| R5.6 | HTTP devices have no observable presence, so the acceptance connectivity guard and the client-connected check are MQTT-shaped | High | High (blocks HTTP commands) | D9 decision before pull code; explicit fact for HTTP contact; otherwise HTTP is ingest-only | P5.4 |
| R5.7 | Pull delivery weakens Phase 4 truthfulness (a served response read as device receipt) | Medium | High | Evidence kinds named in every statement; `HTTP_SERVED` defined as weaker than the broker; conservative UNCERTAIN; never re-serve | P5.4 |
| R5.8 | A device that polls slower than the delivery window loses commands | High | Medium | Visible EXPIRED dead letters; documented poll interval; SDK enforces at most 5 s | P5.4, P5.6 |
| R5.9 | Credential administration without platform authentication lets any caller mint a credential | High if exposed | High | D7: administrative command only; REST creation not built | P5.4 |
| R5.10 | V41 touches `devices`, a hot table, or conflicts with the V37 topic CHECK | Low | High | Constant-default column (no rewrite); spike before authoring; populated-database test; forward-only | P5.1 |
| R5.11 | The simulator freshness fix breaks tests or users that depended on 300 s or on future-dated commands | Medium | Low | The fix is approved (D4); tests state their window explicitly; release note | P5.5 |
| R5.12 | The gate exceeds its 1,800 s budget or exhausts memory again | High | Medium | Measured plan in section 7; shared containers; split check only with approval; bounded batches | all |
| R5.13 | The ESP32 library works on the host but not on a device (timing, memory, RTC, Wi-Fi) | Medium | Medium | No hardware claim; host-only validation stated everywhere; a documented hardware checklist for later | P5.6 |
| R5.14 | PlatformIO native tooling is unavailable offline or heavy on this machine | Medium | Medium | Confirm the offline cache in P5.1; fall back to a plain CMake host build with the same corpus | P5.6 |
| R5.15 | Over-claiming: "secure", "exactly once", "hardware validated", "supports any transport" | Medium | High | Section 19 of the contract; claims audit extended to every new document, README and UI string | P5.8 |
| R5.16 | F1 (connection-pool exhaustion) interacts with a new request-handling path | Low to medium | Medium | HTTP intake uses the same transaction boundaries as MQTT; bounded per-device concurrency; F1 stays tracked and is not claimed fixed | P5.4 |
| R5.17 | Scope creep into later adapters (Modbus, BLE, OPC UA, serial, CAN, vendor clouds) | Medium | Medium | D5 defers them; the conformance kit and the test adapter make them cheap later, not now | all |

## 9. What is not authorised by this ticket

Any source, schema, migration, SDK or test change; any change to `firmware/**`, `hardware-tests/**` or `results/**`; any change to the user-owned documents; any merge, push or tag; the HTTP pull implementation (until D8 and D9). P5.0 is complete when the owner has reviewed these documents.
