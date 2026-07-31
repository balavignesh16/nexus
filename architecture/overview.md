# NEXUS Architecture Overview

NEXUS is a highly decoupled, event-driven Physical Intelligence Framework designed to manage physical infrastructure, ingest high-throughput telemetry, track real-time twin states, and execute dynamic rules.

## Core Modules

- **Device (Hierarchy & Registry)**: Manages physical infrastructure (Sites, Buildings, Spaces) and provisions IoT devices securely.
- **Telemetry**: Handles high-speed ingestion (HTTP and MQTT) of sensor readings.
- **Digital Twin**: Maintains the real-time runtime state of all known devices.
- **Event Engine**: Generates immutable domain events in response to physical changes.
- **Rules Engine**: Evaluates domain events against dynamic user-defined conditions without directly executing side-effects.

## Architectural Principles

1. **One-Way Dependency Flow**: `Domain -> Shared -> Foundation`. Controllers never leak domain objects, and upstream systems (like Digital Twin) never depend on downstream systems (like Rules Engine).
2. **Decoupled Execution**: Decisions (Rules) are physically and logically separated from execution (Actions).
3. **Immutability**: Domain events are strictly immutable representations of past occurrences.
4. **Resiliency**: Validation happens at the edge. The system drops malformed data early to protect the core.
