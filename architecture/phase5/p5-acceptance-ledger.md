# Phase 5 acceptance ledger

Date: 2026-10-18 (P5.0).

Status: **P5.0 DRAFT. 128 scenario IDs defined; 128 PLANNED, 0 COVERED, 0 DEFERRED. Nothing is implemented; no scenario is proven.**

Normative requirements: [adapter-contract.md](adapter-contract.md). Execution and dependency plan: [p5-implementation-ticket.md](p5-implementation-ticket.md). This ledger owns testable scenarios and evidence status; it does not redefine the contract. The accepted Phase 4 ledger ([p4-acceptance-ledger.md](../phase4/p4-acceptance-ledger.md)) is preserved unchanged and its guarantees are listed in contract section 16.

## Status discipline

- PLANNED: the required scenario or test has not been implemented and verified. Every scenario starts here.
- COVERED: only after the exact automated test and its assertions have been inspected and a successful result is recorded in the "Exact test" column and the evidence section.
- DEFERRED: an explicitly approved deferral with reason and milestone; it is not a pass.
- Failed or incomplete evidence stays visible and is never counted complete. A planned test or a passing suite does not close an untested scenario. Assertions, timeouts, isolation and skips must not be weakened to obtain green results.
- Evidence comes only from disposable PostgreSQL and Mosquitto and host-side builds. The development database is never queried, migrated or repaired. Hardware validation is never claimed from a software test.
- Section F (HTTP command delivery) scenarios cannot be marked COVERED until the section 10 safety design is separately authorised and implemented; if it cannot be, they become DEFERRED with the reason, never silently dropped.

## Totals

| Status | Count |
|---|---|
| PLANNED | 128 |
| COVERED | 0 |
| DEFERRED | 0 |
| **Total** | **128** |


## A. Wire contract and JSON Schemas

| ID | Scenario | Intended layer | Required assertion / evidence | Clauses | Slice | Status | Exact test |
|---|---|---|---|---|---|---|---|
| P5-001 | Schemas exist for telemetry, command, acknowledgement and status | Unit | contracts/v1 holds one draft 2020-12 schema per message kind and contracts/versions.json lists the supported versions; every schema is itself valid. | §6, §8 | P5.1 | PLANNED | not yet written |
| P5-002 | The backend and the CLI use the same schema files | Validation gate / audit | A build test proves the classpath copy of every schema is byte-identical to contracts/v1; the CLI reads contracts/v1 directly; no second copy exists. | §8 | P5.1 | PLANNED | not yet written |
| P5-003 | Backend and CLI agree on every fixture | Differential test (backend vs CLI) | The same fixture corpus produces the same verdict and the same fixed error code in the backend contract test and in the CLI; any disagreement fails. | §8 | P5.1 | PLANNED | not yet written |
| P5-004 | The CLI validates locally with no backend | SDK host test | python -m nexus_contract validate runs with no network and no database, exits non-zero on failure and prints fixed codes only. | §8 | P5.1 | PLANNED | not yet written |
| P5-005 | Telemetry schema covers legacy and generic shapes | Unit | Legacy (sensorType, unit, numeric value) and generic (capabilityKey) telemetry validate; a non-numeric legacy value and a payload with neither shape are rejected with INVALID_LEGACY_PAYLOAD. | §7 | P5.1 | PLANNED | not yet written |
| P5-006 | messageId alphabet and length are enforced identically everywhere | Unit | 1 to 64 characters from A-Z a-z 0-9 . _ : - ; 0, 65 and a character outside the set are INVALID_MESSAGE_ID in schema, backend and CLI. | §7 | P5.1 | PLANNED | not yet written |
| P5-007 | Command schema pins the wire payload | Unit | commandId, capability, command, value and an integer epoch-second timestamp are required; a float or ISO timestamp is rejected. | §7 | P5.1 | PLANNED | not yet written |
| P5-008 | ACK schema pins status and reason | Unit | status is ACCEPTED or REJECTED; reason is optional; the timestamp is an integer; an unknown status is rejected. | §7 | P5.1 | PLANNED | not yet written |
| P5-009 | Status message accepts only online and offline | Unit | The plain texts online and offline are accepted case-sensitively as today; anything else is rejected without being treated as a state change. | §7 | P5.1 | PLANNED | not yet written |
| P5-010 | Rules a schema cannot express are in a shared rule module | Unit | Topic-versus-payload device equality and command freshness are implemented once, annotated in x-nexus-rules, and exercised by the shared corpus. | §8 | P5.1 | PLANNED | not yet written |
| P5-011 | Unknown optional fields are tolerated within a major | Unit | A payload with an extra optional field validates under the schemas of the same major. | §6 | P5.1 | PLANNED | not yet written |
| P5-012 | Required-field removal is rejected | Mutation check | A mutated schema that makes a required field optional fails the corpus. | §6, §8 | P5.1 | PLANNED | not yet written |
| P5-013 | Closed rejection vocabulary | Unit | Every rejection produced by the decoders is one of the fixed codes of section 7; a test enumerates them and fails on a new, undocumented code. | §7 | P5.2 | PLANNED | not yet written |
| P5-014 | Schema change process is recorded | Validation gate / audit | A test fails if a published schema file changes without a matching entry in the versions file and the ledger. | §6 | P5.1 | PLANNED | not yet written |


## B. Versioning and legacy-v1 compatibility

| ID | Scenario | Intended layer | Required assertion / evidence | Clauses | Slice | Status | Exact test |
|---|---|---|---|---|---|---|---|
| P5-015 | Unversioned messages resolve to legacy-v1 | Unit | No contractVersion resolves to legacy-v1 and is counted nexus.adapter.contract{version=legacy-v1}. | §6 | P5.1 | PLANNED | not yet written |
| P5-016 | Version resolution is a total function | Unit | A table-driven test covers: absent, empty, non-string, malformed, 1.0, 1.m known, 1.m above the known minor, 2.0, 0.9, 10.0, negative numbers, leading zeros. | §6 | P5.1 | PLANNED | not yet written |
| P5-017 | Unsupported major is rejected permanently, never reinterpreted | Unit | 2.0 and 0.9 give UNSUPPORTED_CONTRACT_MAJOR; the message is not decoded as an older major; the counter and diagnostics show it. | §6 | P5.1 | PLANNED | not yet written |
| P5-018 | A higher minor within the supported major is accepted with unknown optional fields ignored | Unit | 1.5 under a backend that knows 1.0 resolves to 1.0, ignores unknown optional fields, and is counted forward-minor. | §6 | P5.1 | PLANNED | not yet written |
| P5-019 | Header and body version conflict is rejected | API (MockMvc) | X-Nexus-Contract-Version differing from the body contractVersion gives CONTRACT_VERSION_CONFLICT. | §6 | P5.4 | PLANNED | not yet written |
| P5-020 | The support window lists only the actual versions | Validation gate / audit | versions.json lists exactly legacy-v1 and 1.0 with major 1 only; a test fails if the code accepts a version not listed or lists one the code rejects. | §6 | P5.1 | PLANNED | not yet written |
| P5-021 | Every firmware fixture validates as legacy-v1 and as 1.0 | Unit | The golden accept fixtures pass both resolutions. | §13 | P5.1 | PLANNED | not yet written |
| P5-022 | Every reject fixture produces the same code in backend and CLI | Differential test (backend vs CLI) | Malformed JSON, wrong types, a non-UUID device id and a stale command give the identical fixed code in both. | §13 | P5.1 | PLANNED | not yet written |
| P5-023 | Legacy acceptance is never stricter or looser than the code at the baseline | Unit | A differential test runs the baseline decoder (kept as a reference in test code) against the new legacy-v1 path over a generated corpus and finds no verdict difference. | §6, §13 | P5.3 | PLANNED | not yet written |
| P5-024 | Schema evolution: 1.0 fixtures stay valid in every later 1.x | Unit | A test loads every 1.0 fixture and validates it against every 1.x schema present. | §6 | P5.1 | PLANNED | not yet written |
| P5-025 | Breaking-change detection | Mutation check | Renaming a field, changing a type or narrowing a range in a schema copy is detected by the evolution test. | §6 | P5.1 | PLANNED | not yet written |
| P5-026 | Backend accepts a new producer's contractVersion on MQTT and HTTP alike | PostgreSQL + Mosquitto integration | A telemetry message with contractVersion 1.0 is accepted over MQTT and over HTTP and produces the same stored record. | §6 | P5.4 | PLANNED | not yet written |


## C. Adapter SPI, capability split and dependency boundaries

| ID | Scenario | Intended layer | Required assertion / evidence | Clauses | Slice | Status | Exact test |
|---|---|---|---|---|---|---|---|
| P5-027 | No Twin or Rule class depends on an adapter transport package | Architecture test | Reflection or ArchUnit-style test over the compiled classes finds no dependency from twin, rule, action or telemetry packages onto com.nexus.adapter.<transport>. | §3 | P5.2 | PLANNED | not yet written |
| P5-028 | No transport package depends on the core services | Architecture test | adapter.mqtt, adapter.http and adapter.simulator import nothing from twin, rule, action.application, action.outbox, telemetry.application, a repository or JdbcTemplate. | §3 | P5.2 | PLANNED | not yet written |
| P5-029 | Only adapter.core bridges both sides | Architecture test | The set of classes that reference both adapter.spi and a core service is exactly the adapter.core package. | §3 | P5.2 | PLANNED | not yet written |
| P5-030 | adapter.spi has no framework or persistence dependency | Architecture test | adapter.spi imports no Spring, JPA, JDBC or servlet class. | §4 | P5.2 | PLANNED | not yet written |
| P5-031 | Capabilities are separate interfaces | Unit | TelemetryDecoder, AcknowledgementDecoder, ConnectivitySource, CommandEgress, DeviceAuthenticator and AdapterLifecycle are distinct interfaces; no interface contains a method that some adapter must implement as a no-op or an exception. | §4 | P5.2 | PLANNED | not yet written |
| P5-032 | An ingest-only adapter declares no CommandEgress | Unit | An adapter without the capability is registered, appears in diagnostics as such, and the egress router refuses to route to it. | §4 | P5.2 | PLANNED | not yet written |
| P5-033 | Decoding never throws for bad input | Unit | Fuzzed and malformed input to every decoder returns a Rejection with a fixed code and a classification, never an exception. | §4 | P5.2 | PLANNED | not yet written |
| P5-034 | Rejections carry no payload text | Unit | A rejection object and its metric tags contain only the fixed code. | §4 | P5.2 | PLANNED | not yet written |
| P5-035 | The intake ports delegate to the unchanged core entry points | PostgreSQL integration | A spy proves TelemetryIntake calls TelemetryService.processTelemetry exactly once with the decoded request and the right source label; ACK and connectivity likewise. | §5 | P5.2 | PLANNED | not yet written |
| P5-036 | The transport assignment is checked before any port call | Unit | A decoded message from an adapter other than the device's assigned transport is rejected TRANSPORT_MISMATCH and no port is called. | §5 | P5.2 | PLANNED | not yet written |
| P5-037 | A payload can never select an adapter | PostgreSQL integration | A payload that claims another transport, or a contractVersion naming one, does not change routing; the device's server-side assignment decides. | §2 D3 | P5.2 | PLANNED | not yet written |
| P5-038 | Device id mismatch is rejected | Unit | Payload device id differing from the authenticated or topic-derived id is rejected permanently with the right code and nothing is processed. | §5 | P5.2 | PLANNED | not yet written |


## D. MQTT JSON adapter equivalence (no behaviour change)

| ID | Scenario | Intended layer | Required assertion / evidence | Clauses | Slice | Status | Exact test |
|---|---|---|---|---|---|---|---|
| P5-039 | MQTT telemetry results are identical before and after the extraction | PostgreSQL + Mosquitto integration | The existing TelemetryMQTTListener tests pass unchanged; a differential run of the old listener (kept as a reference in test code) and the adapter over a corpus gives identical stored rows and identical acknowledge-or-drop decisions. | §10 preserved, §16 | P5.3 | PLANNED | not yet written |
| P5-040 | Poison classification is preserved | PostgreSQL + Mosquitto integration | Malformed JSON, invalid messageId, invalid fields, topic mismatch, onboarding rejection, invalid telemetry and conflicting duplicates are acknowledged and dropped; infrastructure failures are not acknowledged. | §16 | P5.3 | PLANNED | not yet written |
| P5-041 | Manual acknowledge-after-processing is preserved | PostgreSQL + Mosquitto integration | A message processed but not acknowledged is redelivered and deduplicated (P4.5 behaviour). | §16 | P5.3 | PLANNED | not yet written |
| P5-042 | Status and ACK routing is preserved | PostgreSQL + Mosquitto integration | Retained online and will offline messages and ACKs on the existing topics reach the same handlers with the same results. | §16 | P5.3 | PLANNED | not yet written |
| P5-043 | The publisher classification table is preserved | Unit | PahoClassificationTableTest passes unchanged: four codes NOT_SENT, everything else UNCERTAIN. | §4, §16 | P5.3 | PLANNED | not yet written |
| P5-044 | MQTT CommandEgress delivers the stored bytes verbatim | PostgreSQL + Mosquitto integration | A captured publication on a real Mosquitto equals the stored payload byte for byte and carries evidence kind BROKER_CONFIRMED. | §4, §11 | P5.3 | PLANNED | not yet written |
| P5-045 | Phase 3 suite passes with the outbox OFF and ON after the extraction | Validation gate / audit | The 83 Phase 3 rule classes (776 tests) pass in both modes with no assertion changed. | §16 | P5.3 | PLANNED | not yet written |
| P5-046 | Phase 4 crash smoke and lifecycle suites pass unchanged | Validation gate / audit | CommandCrashRecoveryTest smoke, the lifecycle API, trace projector and outbox suites pass with no assertion changed. | §16 | P5.3 | PLANNED | not yet written |
| P5-047 | Adapter diagnostics expose the MQTT connection state | API (MockMvc) | GET /api/v1/adapters shows mqtt-json started, its capabilities and counters, and no secret. | §17 | P5.3 | PLANNED | not yet written |
| P5-048 | Metrics keep their names and tags | Unit | nexus.telemetry.permanent.rejections reasons and the other Phase 4 meters are unchanged; new adapter meters use fixed tags only. | §17 | P5.3 | PLANNED | not yet written |


## E. HTTP/webhook ingest and device credentials

| ID | Scenario | Intended layer | Required assertion / evidence | Clauses | Slice | Status | Exact test |
|---|---|---|---|---|---|---|---|
| P5-049 | Missing credential is rejected | API (MockMvc) | No Authorization header gives 401 UNAUTHENTICATED and nothing is processed. | §9.1 | P5.4 | PLANNED | not yet written |
| P5-050 | Invalid credential is rejected | API (MockMvc) | A wrong secret, a wrong lookup id and a malformed key give 401 with identical bodies and comparable timing class. | §9.3 | P5.4 | PLANNED | not yet written |
| P5-051 | Revoked credential is rejected on the next request | API (MockMvc) | After revocation the same key gives 401 within the cache bound of 5 seconds. | §9.7 | P5.4 | PLANNED | not yet written |
| P5-052 | Expired and retired credentials stop at their instant | API (MockMvc) | A RETIRING credential works until retire_at and not after; an injected clock proves the boundary. | §9.7 | P5.4 | PLANNED | not yet written |
| P5-053 | Credentials never appear in URLs or logs | Validation gate / audit | A test scans captured logs and the request-logging output for the key and its secret; a request with the key in a URL is rejected and the URL is not logged with it. | §9.1 | P5.4 | PLANNED | not yet written |
| P5-054 | Plaintext is shown once and never stored | PostgreSQL integration | After creation only the lookup id and the hash exist in the database; a database dump contains no recoverable secret. | §9.2 | P5.4 | PLANNED | not yet written |
| P5-055 | Verification is constant time | Unit | The comparison uses MessageDigest.isEqual or equivalent; an unknown lookup id performs a dummy comparison; a test asserts the code path. | §9.3 | P5.4 | PLANNED | not yet written |
| P5-056 | Cross-device impersonation is rejected | API (MockMvc) | A valid credential of device A submitting for device B (path or payload) gives 403 FORBIDDEN_DEVICE and nothing is processed. | §9.4 | P5.4 | PLANNED | not yet written |
| P5-057 | A device assigned MQTT cannot use the HTTP path | API (MockMvc) | An MQTT_JSON device with a valid credential gets 403 TRANSPORT_MISMATCH. | §9.5 | P5.4 | PLANNED | not yet written |
| P5-058 | Oversized body is rejected before parsing | API (MockMvc) | A body above 8 KiB gives 413 PAYLOAD_TOO_LARGE without being read to the end. | §9.6 | P5.4 | PLANNED | not yet written |
| P5-059 | Per-device concurrency is bounded | Concurrency (multiple connections / instances) | More than two simultaneous requests for one device get 429; other devices are unaffected. | §9.6 | P5.4 | PLANNED | not yet written |
| P5-060 | Rotation keeps the device online | PostgreSQL integration | Creating a new credential retires the old one at a stated instant; both work in the overlap; at most two non-revoked exist. | §9.7 | P5.4 | PLANNED | not yet written |
| P5-061 | Revocation is final | PostgreSQL integration | A revoked credential never becomes active again; the event table records it. | §9.7 | P5.4 | PLANNED | not yet written |
| P5-062 | Redelivered telemetry is deduplicated by messageId over HTTP | PostgreSQL integration | Identical resend gives 200 with duplicate true and one stored row; a conflicting resend gives 409. | §9.10 | P5.4 | PLANNED | not yet written |
| P5-063 | HTTP status mapping is complete and stable | API (MockMvc) | Each rejection code maps to exactly the status of section 15; 4xx are permanent, 5xx and 429 are retryable. | §15 | P5.4 | PLANNED | not yet written |
| P5-064 | The adapter refuses an unsafe bind | Validation gate / audit | With credentials enabled on a non-loopback address and no forwarded-https guard the adapter does not start. | §9.9 | P5.4 | PLANNED | not yet written |
| P5-065 | Credential administration is not exposed on the open API | Architecture test | No REST mapping creates, rotates or revokes a credential (decision D7); the administrative command works and is audited. | §9.8 | P5.4 | PLANNED | not yet written |
| P5-066 | Authentication claims are limited | Validation gate / audit | A documentation and UI-string audit finds no statement that the platform is authenticated or secure. | §18 | P5.8 | PLANNED | not yet written |


## F. HTTP command delivery by device pull (only after section 10 is approved and implemented)

| ID | Scenario | Intended layer | Required assertion / evidence | Clauses | Slice | Status | Exact test |
|---|---|---|---|---|---|---|---|
| P5-067 | Pull creates no obligation | PostgreSQL integration | A pull with nothing pending returns 204 and writes no outbox, history or action row. | §10.2 | P5.4 | PLANNED | not yet written |
| P5-068 | Pull serves only an already-accepted command through the unchanged claim | PostgreSQL integration | The served command is an existing outbox row claimed through the publish gate, lease and single-in-flight index; the attempt count rises by one. | §10.3 | P5.4 | PLANNED | not yet written |
| P5-069 | An expired command is never served | PostgreSQL integration | A command past its window is dead-lettered EXPIRED by the sweep and a pull returns 204. | §10.3 | P5.4 | PLANNED | not yet written |
| P5-070 | The payload is served verbatim with the acceptance timestamp | PostgreSQL integration | The response body equals the stored bytes (hash equal) and its timestamp is the acceptance second, not the serve time. | §10.4 | P5.4 | PLANNED | not yet written |
| P5-071 | A complete write is CONFIRMED with evidence HTTP_SERVED | PostgreSQL integration | The PUBLISHED fact's reason code is HTTP_SERVED and the lifecycle statement names that evidence. | §10.5, §11 | P5.4 | PLANNED | not yet written |
| P5-072 | A failure before the first byte is NOT_SENT and may retry | Fault injection | An injected serialisation failure before writing gives NOT_SENT, RETRY_WAIT, and a later pull succeeds within the window. | §10.5 | P5.4 | PLANNED | not yet written |
| P5-073 | A failure after the first byte is UNCERTAIN and never re-served | Fault injection | An injected connection reset mid-body gives UNCERTAIN, dead letter UNCERTAIN_EXPIRED, and no later pull returns the command. | §10.5 | P5.4 | PLANNED | not yet written |
| P5-074 | A served command is never served twice | Concurrency (multiple connections / instances) | Two simultaneous pulls for one device yield at most one response with the command; the other gets 204. | §10.5 | P5.4 | PLANNED | not yet written |
| P5-075 | Per-device ordering is preserved | PostgreSQL integration | A later command is not served while an earlier one of the device is unresolved. | §10.3 | P5.4 | PLANNED | not yet written |
| P5-076 | The uncertain hold applies to HTTP | PostgreSQL integration | After an UNCERTAIN serve, later commands of the device are held for the R32 interval. | §10.5 | P5.4 | PLANNED | not yet written |
| P5-077 | Serving is not an ACK and an ACK is not convergence | PostgreSQL integration | After a serve the ACK fields are empty and verification is unchanged; a posted ACK is recorded by the unchanged core. | §10.7 | P5.4 | PLANNED | not yet written |
| P5-078 | The HTTP ACK is correlated by command and device | API (MockMvc) | An ACK for another device's command is rejected; first-ACK-wins, conflict and late-ACK facts behave as over MQTT. | §10.7 | P5.4 | PLANNED | not yet written |
| P5-079 | A device that polls too rarely sees visible expiry | PostgreSQL integration | A command not pulled within its window ends as a dead letter EXPIRED and appears in the dead-letter listing. | §10.9 | P5.4 | PLANNED | not yet written |
| P5-080 | Pull is authenticated, authorised and transport-checked | API (MockMvc) | Missing, revoked and cross-device credentials and a non-HTTP device are rejected as in section 9. | §10.1 | P5.4 | PLANNED | not yet written |
| P5-081 | Statements name the evidence kind | Unit | The lifecycle statements and the rule-match explanation say 'served over HTTP' and never 'published to the broker' for an HTTP command; no sentence claims receipt or actuation. | §11 | P5.4 | PLANNED | not yet written |
| P5-082 | HTTP command delivery is declared only when proven | Validation gate / audit | The adapter's descriptor lacks CommandEgress until every scenario of this section is COVERED; the conformance report reflects it. | §10.11 | P5.7 | PLANNED | not yet written |


## G. Simulator adapter and Python SDK

| ID | Scenario | Intended layer | Required assertion / evidence | Clauses | Slice | Status | Exact test |
|---|---|---|---|---|---|---|---|
| P5-083 | Simulator freshness uses the firmware's 30-second rule | Simulator (pytest) | A command older than 30 s is REJECTED STALE; 30 s is accepted; 31 s is rejected; boundaries use an injected clock. | §2 D4 | P5.5 | PLANNED | not yet written |
| P5-084 | Simulator freshness uses the absolute difference | Simulator (pytest) | A command dated 31 s in the future is STALE exactly as the firmware computes; 29 s in the future is accepted. | §2 D4 | P5.5 | PLANNED | not yet written |
| P5-085 | Simulator, ESP32 library and backend agree on freshness | Differential test (backend vs CLI) | The boundary fixtures give the same verdict in all three. | §13 | P5.6 | PLANNED | not yet written |
| P5-086 | The default stale window is 30 seconds | Simulator (pytest) | The configuration default and the handler default are 30; a deliberately larger value must be explicit and is logged. | §2 D4 | P5.5 | PLANNED | not yet written |
| P5-087 | Existing simulator tests keep their meaning | Simulator (pytest) | The suite passes; tests that relied on the 300 s default now set the window explicitly and say why. | §2 D4 | P5.5 | PLANNED | not yet written |
| P5-088 | The SDK emits contractVersion and a stable messageId | Simulator (pytest) | Every telemetry message carries contractVersion 1.0 and a UUID messageId; a replayed message keeps the same id. | §6 | P5.5 | PLANNED | not yet written |
| P5-089 | The SDK validates payloads before sending | Simulator (pytest) | An invalid payload is refused locally with the CLI's fixed code. | §8 | P5.5 | PLANNED | not yet written |
| P5-090 | Simulator adapter delivers in process | PostgreSQL integration | The backend simulator adapter satisfies the conformance scenarios without a broker. | §14 | P5.5 | PLANNED | not yet written |
| P5-091 | Duplicate-redelivery hook models QoS 1 | Simulator (pytest) | The test hook republishes an identical message; the backend stores one row. | §14 | P5.5 | PLANNED | not yet written |
| P5-092 | SIMULATED mode is distinct from the SIMULATOR transport | PostgreSQL integration | A SIMULATED-mode device with transport MQTT_JSON and a REAL-mode device with transport SIMULATOR both behave by their transport and are reported by their mode. | §2 D3 | P5.5 | PLANNED | not yet written |


## H. ESP32 helper library and reference device

| ID | Scenario | Intended layer | Required assertion / evidence | Clauses | Slice | Status | Exact test |
|---|---|---|---|---|---|---|---|
| P5-093 | Library layout is PlatformIO-compatible with Arduino compatibility | SDK host test | library.json, src/ and examples/ exist; a host build with PlatformIO's native environment compiles. | §2 D1 | P5.6 | PLANNED | not yet written |
| P5-094 | firmware/ is untouched | Validation gate / audit | The firmware tree hash equals its baseline; the library imports nothing from firmware/. | §2 D1 | P5.6 | PLANNED | not yet written |
| P5-095 | The library builds telemetry that parses to the golden values | SDK host test | Host tests build telemetry documents and the contract validator accepts them; they parse to the same typed values as the fixtures. | §13 | P5.6 | PLANNED | not yet written |
| P5-096 | Stable message ids | SDK host test | The library generates a message id from a device counter and boot id that is stable across a retry of the same sample and unique across samples. | §2 D1 | P5.6 | PLANNED | not yet written |
| P5-097 | Command validation matches the firmware | SDK host test | MALFORMED, UNSUPPORTED and STALE verdicts equal the firmware's on the fixture corpus, including the absolute-difference freshness rule. | §13 | P5.6 | PLANNED | not yet written |
| P5-098 | ACK construction | SDK host test | The ACK document has commandId, status, integer timestamp and the fixed reason codes, and validates against the schema. | §7 | P5.6 | PLANNED | not yet written |
| P5-099 | Clock-not-synced behaviour is explicit | SDK host test | Before time sync the library refuses to produce a timestamped message and reports why, as the firmware does. | §7 | P5.6 | PLANNED | not yet written |
| P5-100 | Sample sketches compile on the host | SDK host test | Each sample compiles in the native environment against stubs. | §2 D1 | P5.6 | PLANNED | not yet written |
| P5-101 | The reference device passes the conformance corpus | SDK host test | The reference implementation, driven by the corpus on the host, produces and accepts exactly the expected messages. | §14 | P5.6 | PLANNED | not yet written |
| P5-102 | No hardware claim is made | Validation gate / audit | Documentation and README state host-side validation only; an audit finds no statement of hardware validation. | §2 D1, §19 | P5.8 | PLANNED | not yet written |


## I. Conformance suite and extensibility

| ID | Scenario | Intended layer | Required assertion / evidence | Clauses | Slice | Status | Exact test |
|---|---|---|---|---|---|---|---|
| P5-103 | The kit runs the same scenarios for every declared capability | Unit | A reusable kit takes an adapter factory and runs the capability scenarios; an adapter is tested only for what it declares. | §14 | P5.7 | PLANNED | not yet written |
| P5-104 | MQTT JSON passes the kit | PostgreSQL + Mosquitto integration | All declared capabilities PASS. | §14 | P5.7 | PLANNED | not yet written |
| P5-105 | HTTP/webhook passes the kit | PostgreSQL integration | Ingest capabilities PASS; command egress is NOT SUPPORTED until section F is covered. | §14 | P5.7 | PLANNED | not yet written |
| P5-106 | Simulator passes the kit | PostgreSQL integration | All declared capabilities PASS. | §14 | P5.7 | PLANNED | not yet written |
| P5-107 | The report distinguishes PASS, FAIL and NOT SUPPORTED | Unit | An adapter lacking a capability is never reported conformant for it. | §14 | P5.7 | PLANNED | not yet written |
| P5-108 | A minimal test adapter proves extensibility without touching the core | Architecture test | A throwaway adapter is added in test code; the dependency rules hold; the git diff of the twin and rule packages for that change is empty. | §14 | P5.7 | PLANNED | not yet written |
| P5-109 | The test adapter is not selectable in production | PostgreSQL integration | Assigning the test transport is rejected as an unsupported adapter type. | §2 D5 | P5.7 | PLANNED | not yet written |
| P5-110 | Mutation: a decoder that accepts invalid input fails the kit | Mutation check | Weakening a decoder in a copy makes the kit fail for the right scenario. | §14 | P5.8 | PLANNED | not yet written |


## J. Migration V41 (design only now; scenarios apply when it is authorised)

| ID | Scenario | Intended layer | Required assertion / evidence | Clauses | Slice | Status | Exact test |
|---|---|---|---|---|---|---|---|
| P5-111 | V41 applies on an empty database | Migration on populated database | Flyway applies V41 after V40 with the recorded checksum. | §12 | P5.1 | PLANNED | not yet written |
| P5-112 | V41 applies on a populated database and defaults existing devices to MQTT_JSON | Migration on populated database | A database with devices and templates upgraded to V41 reads every existing device as MQTT_JSON and loses no row. | §12 | P5.1 | PLANNED | not yet written |
| P5-113 | The transport CHECK rejects unknown values | PostgreSQL integration | An unknown transport in devices or templates is refused by the database. | §12 | P5.1 | PLANNED | not yet written |
| P5-114 | Credential constraints hold | PostgreSQL integration | Hash length, status, retire and revoke consistency, and at most one ACTIVE and one RETIRING credential per device are enforced by the database. | §12 | P5.1 | PLANNED | not yet written |
| P5-115 | Credential events are append-only | PostgreSQL integration | UPDATE is refused; DELETE is allowed only with the parent credential. | §12 | P5.1 | PLANNED | not yet written |
| P5-116 | Deleting a device removes its credentials and events | PostgreSQL integration | The cascade removes both; no credential survives its device. | §12 | P5.1 | PLANNED | not yet written |
| P5-117 | V37 to V40 are unchanged and the logical topic CHECK is compatible | Migration on populated database | The checksums of V1 to V40 are unchanged; an HTTP-assigned device's command row satisfies the V37 topic CHECK. | §12 | P5.1 | PLANNED | not yet written |
| P5-118 | Disabling the adapters is the rollback | PostgreSQL integration | With the HTTP adapter disabled the application ignores the new columns and behaves as before V41. | §12 | P5.1 | PLANNED | not yet written |


## K. Observability, operations, documentation and gate

| ID | Scenario | Intended layer | Required assertion / evidence | Clauses | Slice | Status | Exact test |
|---|---|---|---|---|---|---|---|
| P5-119 | Adapter metrics use fixed tags | Unit | Every nexus.adapter.* meter uses only the tag keys of section 17 and fixed values, never a device or command id. | §17 | P5.2 | PLANNED | not yet written |
| P5-120 | Diagnostics endpoint is read-only and secret-free | API (MockMvc) | GET /api/v1/adapters answers GET only and exposes no credential, hash or key material. | §17 | P5.3 | PLANNED | not yet written |
| P5-121 | Delivery statements name evidence kinds and never claim exactly-once or actuation | Validation gate / audit | The claims audit covers the Phase 5 documents, the SDK READMEs, API descriptions and UI strings. | §11, §19 | P5.8 | PLANNED | not yet written |
| P5-122 | Mixed fleet works | PostgreSQL + Mosquitto integration | An MQTT device, an HTTP device and a simulator device run side by side; each is routed by assignment and none affects another. | §2 D3 | P5.7 | PLANNED | not yet written |
| P5-123 | Unsupported adapter type is refused | API (MockMvc) | Assigning an unknown transport is rejected 400 and the device keeps its previous assignment. | §2 D3 | P5.2 | PLANNED | not yet written |
| P5-124 | Legacy devices with no assignment keep working | PostgreSQL integration | A device created before V41 and never assigned continues over MQTT exactly as before. | §2 D3 | P5.3 | PLANNED | not yet written |
| P5-125 | Protected paths are unchanged by every Phase 5 slice | Validation gate / audit | The firmware, hardware-tests and results tree hashes and the user-owned document hashes equal their baseline. | §1 | P5.8 | PLANNED | not yet written |
| P5-126 | The full gate includes the Phase 5 checks within its budget | Validation gate / audit | validate_nexus.py --full runs the contract, SDK, conformance and Phase 5 smoke checks; the backend check stays within the 1,800 s budget or a justified, approved change is recorded. | ticket section 7 | P5.8 | PLANNED | not yet written |
| P5-127 | Phase 3 suite passes with the outbox OFF and ON at the end of Phase 5 | Validation gate / audit | 776 tests in both modes with no assertion changed. | §16 | P5.8 | PLANNED | not yet written |
| P5-128 | Mutation campaign of Phase 5 | Mutation check | At least ten recorded mutations (schema, version resolution, authentication, transport check, device match, evidence kind, uncertain re-serve, size bound, freshness direction, dependency rule) are each killed by the intended tests and restored byte for byte. | §14 | P5.8 | PLANNED | not yet written |

## Coverage mapping

### By slice

| Slice | Scenario count | Scenario IDs |
|---|---|---|
| P5.1 | 30 | P5-001, P5-002, P5-003, P5-004, P5-005, P5-006, P5-007, P5-008, P5-009, P5-010, P5-011, P5-012, P5-014, P5-015, P5-016, P5-017, P5-018, P5-020, P5-021, P5-022, P5-024, P5-025, P5-111, P5-112, P5-113, P5-114, P5-115, P5-116, P5-117, P5-118 |
| P5.2 | 15 | P5-013, P5-027, P5-028, P5-029, P5-030, P5-031, P5-032, P5-033, P5-034, P5-035, P5-036, P5-037, P5-038, P5-119, P5-123 |
| P5.3 | 13 | P5-023, P5-039, P5-040, P5-041, P5-042, P5-043, P5-044, P5-045, P5-046, P5-047, P5-048, P5-120, P5-124 |
| P5.4 | 34 | P5-019, P5-026, P5-049, P5-050, P5-051, P5-052, P5-053, P5-054, P5-055, P5-056, P5-057, P5-058, P5-059, P5-060, P5-061, P5-062, P5-063, P5-064, P5-065, P5-067, P5-068, P5-069, P5-070, P5-071, P5-072, P5-073, P5-074, P5-075, P5-076, P5-077, P5-078, P5-079, P5-080, P5-081 |
| P5.5 | 9 | P5-083, P5-084, P5-086, P5-087, P5-088, P5-089, P5-090, P5-091, P5-092 |
| P5.6 | 10 | P5-085, P5-093, P5-094, P5-095, P5-096, P5-097, P5-098, P5-099, P5-100, P5-101 |
| P5.7 | 9 | P5-082, P5-103, P5-104, P5-105, P5-106, P5-107, P5-108, P5-109, P5-122 |
| P5.8 | 8 | P5-066, P5-102, P5-110, P5-121, P5-125, P5-126, P5-127, P5-128 |

### By intended evidence layer

| Layer | Count |
|---|---|
| Unit | 29 |
| PostgreSQL integration | 26 |
| Validation gate / audit | 15 |
| API (MockMvc) | 14 |
| SDK host test | 9 |
| PostgreSQL + Mosquitto integration | 8 |
| Simulator (pytest) | 7 |
| Architecture test | 6 |
| Mutation check | 4 |
| Differential test (backend vs CLI) | 3 |
| Migration on populated database | 3 |
| Concurrency (multiple connections / instances) | 2 |
| Fault injection | 2 |

## Evidence not yet produced

Everything. P5.0 produces documents only. The first evidence will come with P5.1 (schemas, fixtures, the CLI and the V41 spike) after a separate authorisation.
