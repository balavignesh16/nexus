# ADR 002: MQTT Integration

## Context
IoT devices predominantly communicate over MQTT due to its lightweight protocol and publish-subscribe nature. NEXUS needed to support MQTT ingestion in addition to the existing HTTP REST APIs.

## Decision
We implemented a direct Eclipse Paho MQTT client connecting to an external Mosquitto broker, acting as a thin adapter layer over the existing `TelemetryService`. We avoided complex integration frameworks and mapped the MQTT topic (`nexus/devices/{deviceId}/telemetry`) directly to the device ID.

## Alternatives Considered
- **Spring Integration MQTT**: A robust Spring framework module for MQTT. *Rejected* for M4 because it introduced unnecessary framework complexity and abstractions that made debugging harder. A thin Paho client adapter was preferred.
- **Embedded MQTT Broker**: Running Moquette or HiveMQ embedded in the Spring Boot app. *Rejected* because real-world deployments scale the broker independently of the backend platform.

## Consequences
- **Pros**: The integration is extremely thin and completely reuses the HTTP validation and business logic. Easy to debug.
- **Cons**: Connection management (reconnects, lost connections) must be manually handled by the Paho client wrapper.
