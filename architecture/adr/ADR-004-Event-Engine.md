# ADR 004: Event Engine

## Context
As Digital Twins update, the system needs a way to react to state changes without tightly coupling the Twin module to downstream consumers (like Rules or Alerts).

## Decision
We implemented a highly decoupled Event Engine. The Twin publishes raw `TwinUpdatedEvent`s to the Spring Application context. An `EventDetector` intercepts these, analyzes the state transition, and converts them into standardized, immutable `DomainEvent`s. An `EventDispatcher` then routes these to registered `EventListener`s.

## Alternatives Considered
- **Direct Method Calls**: Having the Twin call the Rule Engine directly. *Rejected* as it violates the open-closed principle and tightly couples domains.
- **External Message Broker (Kafka)**: Pushing events to Kafka for consumption. *Rejected* to keep the architecture contained within a single JVM for the initial milestones. The dispatcher pattern allows swapping to Kafka later seamlessly.

## Consequences
- **Pros**: Highly extensible. New features (Rules, Actions, Notifications) simply implement `EventListener` and subscribe to the dispatcher without modifying the Twin or Telemetry code.
- **Cons**: In-memory event dispatching means events are lost if the JVM crashes before listeners process them.
