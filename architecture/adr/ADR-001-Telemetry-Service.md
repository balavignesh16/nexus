# ADR 001: Telemetry Service

## Context
NEXUS requires a high-throughput ingestion endpoint for IoT device telemetry. The platform needs a way to validate payloads, persist history, and forward data to downstream systems (like the Digital Twin) in real-time.

## Decision
We chose a layered, synchronous processing model: `Controller -> Service -> Repository`. The `TelemetryService` acts as the orchestrator, performing validation against the `DeviceRepository` and persisting data synchronously to an `InMemoryTelemetryRepository` before forwarding it to the `DigitalTwinService`.

## Alternatives Considered
- **Asynchronous Queueing (Kafka/RabbitMQ)**: Placed an event bus immediately after the controller. *Rejected* for M3 because it introduced too much infrastructure overhead too early. We favored a simpler synchronous HTTP flow to establish the data contract first.
- **Direct Database Writes**: Have the controller write straight to a timeseries database. *Rejected* because it bypasses business validation and downstream Twin updates.

## Consequences
- **Pros**: Simple to test, easy to reason about, strict validation before persistence.
- **Cons**: Synchronous flow means high latency in downstream systems (like Twin or Event Engine) could backpressure the HTTP ingestion. Future milestones (like M11 Analytics) may require replacing the `InMemoryTelemetryRepository` with a dedicated Time-Series DB (e.g., InfluxDB or TimescaleDB) and potentially introducing async queueing.
