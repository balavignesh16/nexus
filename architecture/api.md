# NEXUS API Overview

## Base URL
`http://localhost:8080/api/v1`

## Device Management
- `POST /sites` - Create a Site
- `GET /sites` - List all Sites
- `POST /sites/{siteId}/buildings` - Create a Building
- `GET /sites/{siteId}/buildings` - List a Site's Buildings
- `POST /buildings/{buildingId}/spaces` - Create a Space
- `GET /buildings/{buildingId}/spaces` - List a Building's Spaces
- `POST /spaces/{spaceId}/devices` - Provision a Device
- `GET /devices` - List all Devices
- `GET /devices/{id}` - Get one Device
- `PUT /devices/{id}` - Update a Device
- `GET /devices/{id}/capabilities` - List declared read/write capabilities
- `POST /devices/{id}/capabilities` - Add a capability to an existing Device
- `DELETE /devices/{id}/capabilities/{capabilityId}` - Remove a capability
- `GET /devices/{id}/connectivity` - Get live MQTT connectivity
- `POST /devices/{id}/commands` - Dispatch a manual actuator command

## Framework onboarding and contracts (P1.6)

- `GET/POST /capability-definitions` and `GET/PUT /capability-definitions/{id}` - Manage typed definitions; identity fields lock after creation.
- `GET/POST /device-templates` and `GET/PUT /device-templates/{id}` - Manage MQTT templates.
- `GET/POST /device-templates/{id}/capabilities` and `PUT /device-templates/{id}/capabilities/{associationId}` - Manage template composition before it is referenced by devices.
- `POST /device-onboarding` - Atomically create Device, bindings, and persistent onboarding session. `requestKey` makes identical retries idempotent; a conflicting serial or request returns conflict.
- `GET /device-onboarding` and `GET /device-onboarding/{sessionId}` - Read server-owned progress/failures.
- `POST /device-onboarding/{sessionId}/retry` and `POST /device-onboarding/{sessionId}/disable` - Explicit lifecycle operations.
- `GET /devices/{deviceId}/capability-bindings` and `POST /devices/{deviceId}/capability-bindings` - Read/add per-device bindings.
- `PUT /devices/{deviceId}/capability-bindings/{bindingId}` - Narrow/update a binding; active Rule references are guarded.
- `GET /devices/{deviceId}/contract` - Derive exact MQTT topics, typed capabilities, effective instance constraints, and examples from backend state. This is not credential management.

The MQTT topic family is `nexus/devices/{deviceId}/{telemetry|status|commands}`
with command acknowledgements at `nexus/devices/{deviceId}/commands/ack`.

## Telemetry
- `POST /telemetry` - Submit Telemetry (HTTP Ingestion)
- `GET /telemetry` - Query telemetry
- `GET /devices/{id}/telemetry` - Query a Device's telemetry history
- `GET /devices/{id}/telemetry/latest` - Get its latest telemetry

## Digital Twin
- `GET /devices/{id}/twin` - Get one device-level Twin
- `GET /twins?limit=100` - List device-level Twins

Each Twin contains `reportedState`, keyed by capability type. Every capability
keeps its own latest `value`, `unit`, `timestamp`, `lastSeen`, `quality`, and
`metadata`; telemetry from one capability never overwrites another. The top-level
`latest*` fields summarize the most recently updated capability and are retained
for backwards compatibility.

## Event Engine
- `GET /events` - Query persisted domain events

## Rules Engine
- `POST /rules` - Create a new rule
- `GET /rules` - List all rules
- `GET /rules/{id}` - Get one rule
- `PUT /rules/{id}` - Update a rule without losing its identity/history
- `DELETE /rules/{id}` - Delete a rule
- `POST /rules/{id}/enable` - Enable a rule
- `POST /rules/{id}/disable` - Disable a rule
- `GET /rule-matches` - Query rule matches
- `GET /rule-matches/history` - Query persisted match history
- `GET /rule-matches/history/{matchId}` - Get one persisted match

Capability-typed conditions read current state from the Digital Twin registry for
the resolved device. This permits rules such as `GAS > 500 AND MOTION == true`
even when those readings arrived in different MQTT messages.

## Actions, Pipeline, Analytics, and AI
- `GET /actions/history` - Query command/action execution history
- `GET /actions/executors` - List registered action executors
- `GET /pipeline/history` - Query pipeline traces, optionally by correlation ID
- `GET /pipeline/{correlationId}` - Get an end-to-end trace
- `GET /pipeline/metrics` - Get pipeline metrics
- `GET /analytics/summary` - Get aggregate operational metrics
- `GET /analytics/timeseries` - Get time-series metrics
- `GET /ai/explanations/rule-match/{matchId}` - Get a grounded rule-match explanation
- `GET /platform-health` - Get platform subsystem health
