# Current Twin contract (P2.0 source inventory)

This is the **implemented V20 behavior**, not the proposed Twin 2.0 contract.
Sources: `DigitalTwin`, `DigitalTwinService`, `InMemoryDigitalTwinRegistry`,
`DigitalTwinResponse`, `DigitalTwinController`, V6/V13/V19 migrations, and
their tests.

## Storage, identity, ordering

- `digital_twins` is durable current state, keyed by `(device_id,
  latest_sensor_type)` since V13. The service calls `registry.put` per accepted
  reading; the registry saves to PostgreSQL before updating its in-memory
  cache. Startup hydrates that cache from every persisted row.
- V19 added nullable `capability_definition_id`, `binding_id`, `capability_key`,
  `value_type`, typed numeric/boolean/string/object columns and
  `resolution_source`. Legacy rows were backfilled as NUMBER/numeric with
  `LEGACY_ADAPTER`. There is no independently persisted desired state.
- Generic state carries definition/binding IDs where resolved. The **current
  primary key remains the external sensor identifier**, not binding ID; a
  binding ID is metadata. Enum values use `string_value`, objects JSONB,
  numbers NUMERIC plus legacy `latest_value` DOUBLE; booleans use
  `boolean_value`. Legacy numeric 1/0 boolean wires still use numeric fields.
- `latest_timestamp` is the incoming device event timestamp; `last_seen` is
  backend update time. Equal or older timestamps for the *same external
  identifier* are ignored by `DigitalTwinService` before a Twin write/event.
  Different capabilities compare independently. Raw telemetry persistence
  precedes Twin update in the ingestion path; Twin ignore is not evidence of
  raw telemetry rejection. The per-device JVM lock prevents local
  read/check/write reordering but is not a distributed lock.
- Every accepted newer observation, even the same value, replaces current
  Twin state and emits `TwinUpdatedEvent`, realtime delta, and TWIN trace.
  No durable Twin transition history exists. Rejected telemetry does not
  reach this update path. Current `EventDetector` emits `TWIN_UPDATED` on
  each event and uses numeric legacy values for its change threshold.

## Existing HTTP/realtime contract

`GET /api/v1/devices/{deviceId}/twin` returns one device response containing
all stored capability rows in `reportedState`; no rows produces 404.
`GET /api/v1/twins?limit=100` returns one representative response per device,
also with its complete `reportedState`. Both retain `deviceId`,
`latestTimestamp`, `latestSensorType`, `latestValue`, `latestUnit`, `lastSeen`,
`quality`, and `metadata` as a most-recent-capability summary. The map is
currently keyed by external sensor type/key, not stable binding ID.

Each `reportedState` entry has `value` (legacy Double), `typedValue`,
`valueType`, `capabilityKey`, `definitionId`, `bindingId`,
`resolutionSource`, `unit`, `timestamp`, `lastSeen`, `quality`, `metadata`.
The response calculates quality at read time from `lastSeen`: under 30 seconds
GOOD, 30–120 seconds STALE, over 120 seconds OFFLINE, null UNKNOWN. These
thresholds are global and do **not** consult connectivity, binding policy,
validation state, or a device-specific clock. There is no desired/effective
state, convergence status, or Twin history endpoint. Existing `/topic/twins`
realtime messages are deltas, not the complete response and not history.

## Consumers and field disposition

| Field/group | Current consumers/meaning | P2 disposition |
|---|---|---|
| `deviceId`, `reportedState`, nested typed fields | `frontend/src/api/twinApi.ts`, `TwinCard`, framework onboarding page, Explorer DigitalTwinTab; generic rule evidence | KEEP; add stable identity without removing map |
| `latestTimestamp`, `latestSensorType`, `latestValue`, `latestUnit`, `lastSeen` | simulator integration assertion, frontend fallbacks/realtime normalization, legacy analytics/event/rule paths | KEEP as LEGACY-COMPATIBILITY; consider deprecation later, never silently reinterpret |
| top-level and per-capability `quality` | frontend Twin rendering; currently fixed-age display | KEEP field; document changed derivation/version in P2.3 |
| `metadata` | API clients may display it; no Twin 2 intent stored here | KEEP; not an internal desired-state store |
| `resolutionSource`, `definitionId`, `bindingId`, `capabilityKey` | generic framework UI and Rule evidence | KEEP; use binding ID as preferred future state identity |
| cache key and JPA columns | internal runtime/persistence | INTERNAL; additive migration only |

Backend `RuleEvaluator` reads current registry state: legacy conditions by
`capabilityType`, generic conditions by `capabilityKey`/external identifier.
It presently can evaluate an old Twin row; no freshness gate exists. The
`EventDetector` consumes `TwinUpdatedEvent`. RealtimePublisher sends deltas on
`/topic/twins`. AI explanation uses stored rule/action evidence rather than a
new Twin 2 state contract. Frontend consumers include TwinsPage,
DigitalTwinTab, TwinCard, framework onboarding, query/repository/realtime
adapters, and typed-value displays. `DigitalTwinTab`'s current Diff mode has
no previous snapshot and intentionally compares current state with itself.

The current command path is separate: manual and rule actions use
`CommandExecutor` to publish non-retained QoS-1 MQTT commands. The simulator
ACKs on `/commands/ack`; `DeviceCommandAckMessageHandler` correlates
`commandId == ActionHistory.requestId`, checks topic device ID, and stores
`ackStatus`/device-supplied ACK timestamp. ACK does not update Twin reported
state. Current command handling blocks REAL-mode devices.
