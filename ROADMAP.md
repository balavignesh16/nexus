# NEXUS Physical Intelligence Framework Roadmap

This document is the shared implementation roadmap for evolving NEXUS from a
three-ESP demonstration into a reusable physical-intelligence framework. Update
the status checkboxes and decision log as work progresses.

## Roadmap status

| Phase | Objective | Status |
|---|---|---|
| 0 | Clean baseline and reliable development workflow | Complete |
| 1 | Generic device and capability framework | Complete |
| 2 | Complete Digital Twin engine | In progress |
| 3 | Production-grade Rule Engine | Not started |
| 4 | Reliable event and action pipeline | Not started |
| 5 | Device adapter and SDK layer | Not started |
| 6 | Safe AI intelligence | Not started |
| 7 | Operations, security, and governance | Not started |
| 8 | Framework-grade frontend | Not started |
| 9 | Validation and release engineering | Not started |

Allowed status values: `Not started`, `In progress`, `Blocked`, and `Complete`.

## Current baseline

NEXUS currently provides:

- Device provisioning with multiple capabilities.
- HTTP and MQTT telemetry ingestion.
- Per-device/per-capability Twin persistence.
- Device-level Twin responses containing complete `reportedState` maps.
- Multi-capability rule evaluation.
- Event detection, action dispatch, MQTT commands, and acknowledgement handling.
- Correlation IDs and pipeline traces.
- Simulated sensors and actuators covering the three current ESP32 profiles.
- Evidence-grounded AI explanations.
- Operational frontend views.
- A passing frontend test suite and TypeScript build.

Phase 1 added configuration-driven generic capabilities and typed values.
Legacy capability types and the current ESP simulator profiles remain
code-defined compatibility paths. Twin intent, durable state history, and
capability quality are implemented; convergence and generic readback mapping
remain the next Twin gaps.

---

## Phase 0 — Establish a clean baseline

### Goal

Preserve the working system and make development reproducible before introducing
deeper architectural changes.

### Work

- [x] Review and classify all current uncommitted changes.
- [ ] Separate framework code, review artifacts, and hardware experiments.
- [ ] Create a release baseline such as `v0.1-framework-foundation`.
- [x] Add one validation command covering backend, frontend, simulator, and tooling tests.
- [x] Document supported environment versions.
- [ ] Improve `start_nexus.py` with:
  - [x] Real `--help` handling.
  - [x] `--no-simulator`.
  - [x] `--no-frontend`.
  - [x] `--duration`.
  - [x] Service readiness checks.
  - [x] Duplicate-process detection.
  - [x] Predictable shutdown and cleanup.

Phase 0 acceptance is complete: fast/full validation is green, the deterministic
isolated MQTT-to-Twin-to-Rule-to-Command-to-ACK smoke is part of full validation,
and startup/shutdown safety is covered by tooling tests and a live duration run.

### Exit criteria

- [ ] A fresh clone starts with one documented command.
- [ ] Repeated starts cannot create duplicate backend, frontend, or simulator processes.
- [ ] All established tests pass.
- [ ] The database can be built from an empty instance using migrations alone.

---

## Phase 1 — Generic device and capability framework

### Goal

Make device integration configuration-driven instead of tied to the three current
ESP32 profiles.

### 1.1 Capability definitions

Implementation status:

- [x] P1.1 current-contract inventory and three-device fixtures.
- [x] P1.2 persistent CapabilityDefinition foundation and seeded definitions.
- [x] Generic typed value, unit, direction, object, and command validation.
- [x] Legacy CapabilityType mapping and backend definition API.
- [x] Empty and existing-schema migration coverage.
- [x] P1.3 DeviceTemplate and instance capability bindings.
- [x] P1.4 Central generic runtime resolution and additive typed telemetry/Twin state.
- [x] P1.5 Generic typed Rule conditions/actions with legacy compatibility.
- [x] P1.6 Dynamic MQTT onboarding backend, generated contract, binding management,
  lifecycle and restart coverage (implementation and automated backend gate).
- [x] P1.7 Framework management UI and live browser-to-MQTT acceptance.

Introduce a capability-definition model containing:

- Stable capability key, such as `temperature`, `motion`, or `motor_speed`.
- Direction: currently `READ` or `WRITE` (`READ_WRITE` is not implemented).
- Value type: number, boolean, string, enum, or object.
- Unit and allowed units.
- Numeric minimum and maximum.
- Enum choices.
- Validation constraints.
- Supported command operations.
- Display metadata.
- Optional semantic category.

Example:

```json
{
  "key": "motor_speed",
  "direction": "WRITE",
  "valueType": "NUMBER",
  "unit": "RPM",
  "minimum": 0,
  "maximum": 5000,
  "commands": ["SET", "STOP"]
}
```

### 1.2 Device templates

Support reusable templates such as:

- Environmental ESP32.
- Safety ESP32.
- Actuator ESP32.
- Custom MQTT device.
- Custom Modbus device.

A template defines capabilities, while every provisioned device remains an
independent instance.

### 1.3 Dynamic onboarding

The onboarding workflow should:

1. Create or select capability definitions.
2. Create or select a device template.
3. Provision a device instance.
4. Generate its MQTT topics and payload contract.
5. Receive its first status message.
6. Validate its first telemetry message.
7. Mark the device operational.

### Exit criteria

- [x] Multiple instances of any template can be provisioned.
- [x] A new numeric or boolean component does not require changing a Java enum.
- [x] Invalid onboarding telemetry is rejected and recorded without activating the device.
- [x] The UI can provision and inspect a new device in a real browser.
- [x] Existing ESP32 devices remain backward compatible in automated regression.

P1.6 uses Flyway V20 and a persistent onboarding session. Only a `REGISTERED`
device in `WAITING_FOR_TELEMETRY` is admitted to the normal typed telemetry path;
activation follows successful telemetry persistence and Twin update. Provisioning
is transactional and request-key idempotent. Binding overrides can narrow, never
expand, a definition/template constraint; active Rule references are protected.
The generated contract is backend-owned and MQTT-only. It is not credential
management. P1.7 adds capability, template, onboarding, contract, typed Twin, and
generic Rule screens. The 2026-10-03 isolated live browser acceptance created a
new numeric capability and two-capability template, provisioned a simulated
device, received status and typed telemetry through real MQTT, reached
`OPERATIONAL`/`ACTIVE`, created a generic Rule in the UI, and correlated the
resulting real MQTT command with an `ACCEPTED` simulated-device ACK. This is
software/simulator evidence, not a physical LED hardware test.

---

## Phase 2 — Complete the Digital Twin engine

### Goal

Represent the operational state and intent of each physical device, not only its
latest measurements.

### 2.1 Twin state model

Separate:

- `reportedState`: what the device reports.
- `desiredState`: what NEXUS wants the device to become.
- `effectiveState`: a derived structured resolution showing observed fact,
  desired intent, and verification separately; never a third writable truth.
- Connectivity state.
- Twin metadata.
- Per-capability quality and freshness.

Example actuator state:

```json
{
  "reportedState": {
    "fan": { "value": false }
  },
  "desiredState": {
    "fan": { "value": true }
  }
}
```

A difference between desired and reported state is exposed without hiding the
observation. P2.4 will classify convergence; P2.1 does not infer it from ACKs
or simple value equality.

### 2.2 State history

Implementation status: **Complete (committed).** V22 provides stable
binding/legacy identity, deterministic alias reconciliation with an audit
archive, durable typed transitions, after-commit projection/publication, a
cursor history API, and a database-backed Twin diff UI.

Persist Twin transitions so the framework can answer:

- What changed?
- When did it change?
- What caused it?
- Was the source telemetry, a rule, a manual command, or recovery?
- What was the previous state?

This history will enable real Twin diff mode.

### 2.3 Quality and freshness

Implementation status: **Complete (committed).** V23 captures exact
backend receipt time, adds a narrowing-only binding override, derives
GOOD/STALE/OFFLINE/UNKNOWN evidence, gates capability-backed Rules on GOOD, and
surfaces policy and quality in the framework UI.

Support configurable per-capability quality:

- `GOOD`
- `STALE`
- `OFFLINE`
- `UNKNOWN`

Rejected input is tracked as ingestion health, not automatically as invalid
current Twin state. The existing age-only `quality` response field stays
compatible; P2.3 proposes additive `stateQuality`.

Freshness thresholds must be capability-specific. A motion signal and a firmware
version should not use the same expiration policy.

### 2.4 Convergence and reconciliation

Implementation status: **V24 implemented and committed; acceptance matrix and forensic final review complete.**
The five independent axes are desired intent, MQTT publication, software ACK,
qualified reported readback, and current verification. The additive derived
`CommandStatus` does not change `ExecutionStatus.SUCCESS` (publish success).
V24 adds explicit validated WRITE-binding/subtarget to READ-binding mappings,
backend ACK receipt/deadline timestamps, and durable verification with
`PENDING`, `CONVERGED`, `DIVERGED`, `TIMED_OUT`, and `UNVERIFIED` states.
`NOT_APPLICABLE` and `NO_DESIRED_STATE` are derived presentation states.
Only a GOOD mapped readback satisfying the configured qualification can
establish convergence; current P2.4 qualification supports `COMMAND_ECHO`
and `NONE` (the latter cannot establish convergence). The simulator can emit
correlated echo telemetry for mapped simulated devices; existing physical ESP
telemetry is not physical convergence proof. Deadline reconciliation survives
restart, while late qualified observations can show drift or recovery. Desired
state is never automatically cleared. ACK timeout is derived from the
published/deadline/ACK facts; there is no retry, outbox, or delivery recovery.
An isolated real-MQTT software loop covers mapped command/ACK/readback
convergence; it does not substitute for physical-device evidence.

For writable capabilities:

1. Desired state changes.
2. A command is generated.
3. The device acknowledges it.
4. An explicitly mapped READ capability reports state (where supported).
5. A valid, fresh matching readback may establish current-state convergence;
   ACK alone never does. Write-only capabilities remain `UNVERIFIED`.

### Exit criteria

- [ ] A multi-capability Twin survives restart without losing state.
- [ ] Desired and reported state are separately inspectable.
- [x] State changes include history and correlation IDs.
- [ ] Failed convergence is visible with persisted evidence; operational alerting/action workflow remains future work.
- [x] Stale state cannot silently drive safety-critical rules.

P2.1 foundation is implemented and committed: V21 desired intent,
pre-publish durable command acceptance, and derived effective state. Final
implementation review passed on 2026-10-05. P2.2 history and P2.3 quality are
implemented together, final-review approved, and committed; P2.4 core is
implemented and committed with the mandatory acceptance matrix closed and
forensic final review complete.

---

## Phase 3 — Production-grade Rule Engine

### Goal

Evolve the current matcher into a configurable and deterministic reasoning
system.

### 3.1 Strong rule validation

Validate before activation that:

- Referenced devices exist.
- Referenced capabilities belong to those devices.
- Operators match capability value types.
- Command targets are writable.
- Values are within capability constraints.
- Device scoping is unambiguous.
- Temporal parameters are valid.

### 3.2 Explicit triggers

Support triggers for:

- Telemetry received.
- Twin state changed.
- Connectivity changed.
- Command failed.
- Schedule reached.
- Time window elapsed.
- Another rule matched.
- Manual evaluation requested.

### 3.3 Temporal reasoning

Support conditions such as:

- Temperature above 35 °C for five minutes.
- Motion detected three times within one minute.
- Gas increasing continuously.
- Device offline for more than 30 seconds.
- Expected telemetry not received within an interval.

### 3.4 Rule lifecycle and versioning

Support the states:

- Draft.
- Validated.
- Active.
- Paused.
- Archived.

Every edit should create a version so historical matches remain explainable using
the exact rule definition that produced them.

### 3.5 Conflict resolution

Define deterministic handling for:

- Priorities.
- Safety overrides.
- Mutually exclusive actions.
- Command deduplication.
- Cooldowns.
- Hysteresis.
- Optional manual approval.

Last-write-wins behavior should only be used when explicitly configured.

### 3.6 Simulation and dry-run

Allow rules to be evaluated against:

- Manually supplied state.
- Historical telemetry.
- Simulated devices.
- Current live state without executing actions.

### Exit criteria

- [ ] Invalid rules cannot be activated.
- [ ] Temporal and multi-capability rules behave deterministically.
- [ ] Every match contains condition-by-condition evidence.
- [ ] Conflicting commands are resolved predictably.
- [ ] Rules can be tested safely before activation.

---

## Phase 4 — Reliable event and action pipeline

### Goal

Prevent lost, duplicated, or untraceable physical actions.

### Work

- [ ] Introduce a transactional outbox for domain events and outgoing commands.
- [ ] Introduce inbox/idempotency records for telemetry and acknowledgements.
- [ ] Implement the command lifecycle:
  - `CREATED`
  - `QUEUED`
  - `DISPATCHED`
  - `ACKNOWLEDGED`
  - `REJECTED`
  - `TIMED_OUT`
  - `FAILED`
- [ ] Track physical-state `CONVERGED` separately as Twin verification, not a
  command-delivery lifecycle status.
- [ ] Add configurable retry policies.
- [ ] Add dead-letter handling.
- [ ] Add device command timeouts.
- [ ] Record backend receipt timestamps for accurate ACK latency.
- [ ] Add command deduplication.
- [ ] Recover pending work after backend restart.
- [ ] Add backpressure and rate limiting.

### Exit criteria

- [ ] MQTT QoS redelivery cannot duplicate an action.
- [ ] Restarting the backend cannot lose a pending command.
- [ ] Timed-out commands are visible.
- [ ] ACK and convergence latency use backend timestamps.
- [ ] Every pipeline step is traceable.

---

## Phase 5 — Device adapter and SDK layer

### Goal

Support additional protocols without changing the Twin and Rule engines.

### Adapter contract

```text
Device Adapter
├── connect/disconnect
├── decode telemetry
├── publish command
├── decode acknowledgement
├── report connectivity
└── expose diagnostics
```

### Initial adapters

- MQTT JSON adapter.
- HTTP/webhook adapter.
- Simulator adapter.

### Later adapters

- Modbus.
- BLE gateway.
- OPC UA.
- Serial.
- CAN bus.
- Vendor cloud APIs.

### Deliverables

- [ ] Versioned wire contract.
- [ ] JSON Schema definitions.
- [ ] Python simulator SDK.
- [ ] ESP32 helper library.
- [ ] Reference device implementation.
- [ ] Adapter conformance test suite.

### Exit criteria

- [ ] A new transport adapter does not modify the Twin or Rule engines.
- [ ] Firmware developers can validate payload compatibility locally.
- [ ] Contract versions support backward-compatible evolution.

---

## Phase 6 — Safe AI intelligence

### Goal

Use AI for explanation and assistance without allowing unsafe autonomous
actuation.

### Layer 1: deterministic explanations

Use stored evidence to explain:

- Why a rule matched.
- Why it did not match.
- Which capability values were used.
- Which action was generated.
- Which pipeline stage failed.

### Layer 2: AI-assisted rule creation

Natural language such as:

> If gas remains above 500 ppm for 10 seconds while motion is present, activate
> the buzzer and fan.

should produce a structured draft. The deterministic rule validator must approve
the draft before it can be activated.

### Layer 3: anomaly and recommendation services

- Detect unusual telemetry patterns.
- Recommend thresholds.
- Identify frequently failing devices.
- Detect rules that conflict repeatedly.
- Suggest maintenance.

### Safety boundary

AI may:

- Explain.
- Recommend.
- Generate a draft.
- Request approval.

AI must not publish device commands directly. Only validated rules or explicitly
authorized commands may reach the Action Engine.

### Exit criteria

- [ ] Every AI output cites platform evidence.
- [ ] Unsupported claims are rejected or marked uncertain.
- [ ] AI-created rules require deterministic validation and approval.
- [ ] AI failures cannot interrupt telemetry, Twins, rules, or actions.
- [ ] An evaluation suite measures grounding and correctness.

---

## Phase 7 — Operations, security, and governance

### Goal

Make the framework safe to operate beyond a local demonstration.

### Work

- [ ] Authentication.
- [ ] Role-based authorization.
- [ ] Device credentials and credential rotation.
- [ ] Per-device MQTT ACLs.
- [ ] Audit records for configuration changes and manual commands.
- [ ] Secret management.
- [ ] API and device rate limits.
- [ ] Payload-size limits.
- [ ] Tenant or project isolation if required.
- [ ] Backup and restore procedures.
- [ ] Data-retention configuration.
- [ ] Prometheus metrics and alerting.
- [ ] Structured logs.
- [ ] Health and readiness endpoints.
- [ ] OpenTelemetry traces.

### Suggested roles

- Viewer.
- Operator.
- Rule Author.
- Device Administrator.
- Platform Administrator.

### Exit criteria

- [ ] Devices cannot publish under another device's identity.
- [ ] Manual actuator commands are authorized and audited.
- [ ] Operational failures generate actionable alerts.
- [ ] Backups can be restored in a documented exercise.

---

## Phase 8 — Framework-grade frontend

### Goal

Make the UI a complete administration, operations, and reasoning environment.

### Features

- [ ] Capability-definition manager.
- [ ] Device-template builder.
- [ ] Guided device onboarding.
- [ ] Reported and desired Twin state.
- [ ] Historical Twin diffs.
- [ ] Visual rule builder backed by the authoritative rule schema.
- [ ] Rule validation results.
- [ ] Rule dry-run and historical replay.
- [ ] Command lifecycle timeline.
- [ ] Device convergence status.
- [ ] AI explanations with cited evidence.
- [ ] Adapter and connection diagnostics.
- [ ] Platform-wide search.

### Exit criteria

- [ ] A user can add a supported device without editing source code.
- [ ] A user can build, validate, simulate, and activate a rule.
- [ ] A failed command can be diagnosed from one screen.
- [ ] The UI never invents missing backend data.

---

## Phase 9 — Validation and release engineering

### Goal

Continuously prove that framework behavior is correct and recoverable.

### Test layers

- [ ] Domain unit tests.
- [ ] REST and MQTT contract tests.
- [ ] Database migration tests.
- [ ] Adapter conformance tests.
- [ ] Rule evaluation matrix.
- [ ] Temporal-rule tests using a controllable clock.
- [ ] Command retry and idempotency tests.
- [ ] End-to-end simulated-device tests.
- [ ] Restart and recovery tests.
- [ ] Load and soak tests.
- [ ] Security tests.
- [ ] Separately tracked real-hardware validation.

### Release gates

- [ ] Clean builds and test suites.
- [ ] Successful migration from the previous release.
- [ ] No unresolved critical security findings.
- [ ] Documented known limitations.
- [ ] Reproducible simulation results.
- [ ] Hardware results labeled by their actual validation status.

---

## Recommended implementation order

1. Baseline and startup reliability.
2. Generic capability definitions and device templates.
3. Reported/desired Twin state and history.
4. Rule validation, temporal reasoning, and versioning.
5. Reliable command lifecycle and transactional outbox.
6. Adapter SDK and contract conformance.
7. AI-assisted rule creation and anomaly recommendations.
8. Security, observability, and framework-grade UI.
9. Load, recovery, and release validation.

AI and visual builders should be built on top of stable Capability, Twin, Rule,
and Command contracts.

## First target milestone

> Provision a completely new multi-capability device from configuration, receive
> its telemetry without source-code changes, expose all its state in the Digital
> Twin, create a validated multi-capability rule, and execute a correlated command
> with acknowledgement and convergence tracking.

Completing this milestone will demonstrate that NEXUS is a reusable framework,
not only a three-device demonstration.

## Decision log

Record roadmap approvals and meaningful scope decisions here.

| Date | Decision | Status/Notes |
|---|---|---|
| 2026-09-22 | Initial detailed roadmap captured for review. | Awaiting user confirmation before implementation. |
| 2026-10-03 | Commit the completed Phase 1 work in scoped stages. | Approved by user; no tag, push, or Phase 2 implementation. |
| 2026-10-03 | Review Twin 2.0 P2.0 contract against source. | Corrected direction, transaction, quality, history, and convergence semantics; P2.1 remains subject to user approval. |
| 2026-10-05 | Perform final P2.1 implementation review. | Approved after one frontend status-contract correction and stronger rollback, uniqueness, and concurrency evidence; no P2.2 work. |

## Progress notes

Use this section for concise updates after each implementation session.

- 2026-09-22: Roadmap document created. No roadmap phase implementation started.
- 2026-10-02: P1.6 MQTT onboarding backend and P1.7 framework UI implemented
  in the uncommitted worktree. Final automated full gate passed (747 backend,
  416 frontend tests, zero failures); live browser UI-to-MQTT-to-ACK acceptance
  remains open, so P1.7 is not yet ready for sign-off. P1.5 closure checkpoint
  alone was committed as `eac296b8a0116963e261a029f79f3edbe590fc8c`.
- 2026-10-03: Closed the live browser gap in disposable PostgreSQL/Mosquitto
  containers, without attaching the normal development volume. Browser-created
  generic capability/template/device/Rule progressed through real MQTT status,
  telemetry, command, and `ACCEPTED` ACK. Read-only SQL confirmed typed
  `GENERIC_BINDING` telemetry/Twin, persisted RuleMatch evidence, correlated
  ActionHistory, and the trace stages actually present. A Rule form race that
  reset user-selected `GT` to `EQ` was fixed with regression coverage. Final
  fast/full gates passed: 747 backend and 417 frontend tests, zero failures.
  Evidence is under ignored `.nexus/e2e/1790924003/`. P1.6/P1.7 remain
  uncommitted for review; no Phase 2 work started.
- 2026-10-03: User requested staged commits of all worktree changes. Tooling,
  framework backend, frontend, simulator tests, hardware test files, and
  simulated-results artifacts were committed separately. Phase 1 acceptance
  is complete; Phase 2 implementation has not started.
- 2026-10-03: P2.0 baseline/contract work started. Isolated ACK tracing found
  a live-test device-selection defect; focused regression and 10 repeated
  LED/RELAY live runs passed after a narrow test fix. Twin 2.0 schema,
  persistence, API, UI, and P2.1+ implementation have not started. The
  supervised app was briefly stopped for full validation, which passed
  with 747 backend and 417 frontend tests; it was then restarted healthy.
  P2.0 contract is ready for review, not yet approved by the user.
