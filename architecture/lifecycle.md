# Telemetry Lifecycle

This document describes the exact flow of a single telemetry message as it traverses the NEXUS platform.

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
- Is the device `ACTIVE`?
- Is the payload syntactically valid?

If any check fails, the payload is immediately dropped (or 400 Bad Request returned).

### 3. History Persistence
Valid telemetry is transformed into a `TelemetryRecord` and saved directly to the `TelemetryRepository` (currently in-memory, pending time-series DB implementation). This provides a historical audit trail.

### 4. Digital Twin Update
The same payload is forwarded to the `DigitalTwinService`. The Twin engine identifies the target device's twin and updates its latest known state (e.g., `currentValue`, `lastSeen`, `quality = GOOD`).

### 5. Domain Event Generation
Because the Twin's state changed, the `DigitalTwinService` emits a standard Spring `TwinUpdatedEvent`. The `EventDetector` intercepts this transition, identifies exactly *what* changed, and emits a standard `DomainEvent` (e.g., `SENSOR_VALUE_CHANGED`) onto the `EventDispatcher`.

### 6. Rule Evaluation
The `RuleMatcher` (listening on the `EventDispatcher`) receives the `DomainEvent`. It loads all enabled rules from the `RuleRegistry`, ordered by priority. It passes the event and the rule to the `RuleEvaluator`.

### 7. Rule Match
If the `RuleEvaluator` confirms the payload satisfies the condition (e.g., `VALUE > 22.0`), it returns a positive match. The `RuleMatcher` generates a `RuleMatchedEvent` and pushes it to the `BoundedRuleMatchStore`. (In M8, this event will trigger real-world consequences via the Action Execution Framework).
