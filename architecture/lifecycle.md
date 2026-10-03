# Telemetry Lifecycle

This document describes the flow of a telemetry message as it traverses NEXUS.

## P1.6 onboarding gate

Provisioning transactionally creates a `REGISTERED` Device, capability bindings,
and a persistent session in `WAITING_FOR_STATUS`. A valid online status advances
the session to `WAITING_FOR_TELEMETRY`. Only that precise Device/session pair is
admitted while still `REGISTERED`; all other non-ACTIVE devices retain the
ordinary rejection rule. The first valid reading goes through the normal
resolver, value validator, typed telemetry persistence, and Twin update. Only
then does the same transaction activate the Device and mark the session
`OPERATIONAL`. Invalid device data records `TELEMETRY_REJECTED` without producing
history, Twin, Rule, or action output. Transient infrastructure failure is not
classified as invalid data. Persisted deadlines support `STATUS_TIMEOUT` and
explicit retry/disable. Duplicate status/replayed provisioning do not duplicate
state transitions. The backend-generated MQTT contract describes this device's
effective bindings; it does not provision broker credentials.

## The Flow

```text
Telemetry
    ↓
Validation
    ↓
History
    ↓
Twin
    ↓
Event
    ↓
Rule
    ↓
RuleMatchedEvent
```

### 1. Telemetry Ingestion
A device emits a sensor reading (e.g., Temperature: 22.5 C). It arrives either via the `POST /api/v1/telemetry` HTTP endpoint or via the MQTT Topic `nexus/devices/{deviceId}/telemetry`. Both endpoints route into the `TelemetryService`.

### 2. Edge Validation
The `TelemetryService` first validates the device context:
- Does the device exist in the Device Registry?
- Is the device `ACTIVE`, or is it the narrowly admitted onboarding Device in
  `WAITING_FOR_TELEMETRY`?
- Is the payload syntactically valid?

If any check fails, the payload is immediately dropped (or 400 Bad Request returned).

### 3. History Persistence
Valid telemetry is transformed into a `TelemetryRecord` and saved directly to the `TelemetryRepository` (currently in-memory, pending time-series DB implementation). This provides a historical audit trail.

### 4. Digital Twin Update
The same payload is forwarded to the `DigitalTwinService`. The Twin engine updates
the latest state for the exact `(deviceId, sensorType)` pair. A multi-capability
device therefore retains temperature, humidity, motion, gas, and any other
reported fields independently; a reading from one capability cannot overwrite or
make an older reading from another capability look out of order. The REST API
aggregates these records into the device Twin's `reportedState` map.

### 5. Domain Event Generation
Because the Twin's state changed, the `DigitalTwinService` emits a standard Spring `TwinUpdatedEvent`. The `EventDetector` intercepts this transition, identifies exactly *what* changed, and emits a standard `DomainEvent` (e.g., `SENSOR_VALUE_CHANGED`) onto the `EventDispatcher`.

### 6. Rule Evaluation
The `RuleMatcher` (listening on the `EventDispatcher`) receives the `DomainEvent`. It loads all enabled rules from the `RuleRegistry`, ordered by priority. It passes the event and the rule to the `RuleEvaluator`. Conditions carrying a `capabilityType` are evaluated against that capability's current Twin state for the resolved device, allowing multi-capability AND reasoning across separately arriving messages.

### 7. Rule Match
If the `RuleEvaluator` confirms the payload satisfies the condition (e.g., `VALUE > 22.0`), it returns a positive match. The `RuleMatcher` generates a `RuleMatchedEvent` and pushes it to the `BoundedRuleMatchStore`. (In M8, this event will trigger real-world consequences via the Action Execution Framework).
