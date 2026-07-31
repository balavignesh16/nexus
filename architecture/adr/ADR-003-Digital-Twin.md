# ADR 003: Digital Twin

## Context
While the Telemetry layer stores historical time-series data, upstream applications (like dashboards, rules, or APIs) frequently need to know the *current* state of a device without running expensive aggregation queries over millions of historical rows.

## Decision
We introduced a Digital Twin layer that maintains an in-memory (`ConcurrentHashMap`) representation of the latest known state for every active device. It tracks properties like `lastSeen` and `currentValue`. 

## Alternatives Considered
- **Querying Telemetry History**: Running a `SELECT * ORDER BY timestamp DESC LIMIT 1` on the telemetry table. *Rejected* due to massive performance overhead at scale.
- **Redis Cache**: Using an external Redis cluster to store twin state. *Rejected* to minimize infrastructure requirements in early milestones. Can be swapped in later if horizontal scaling requires it.

## Consequences
- **Pros**: Instant, O(1) reads for current device state. Decouples runtime state from historical storage.
- **Cons**: In-memory state is lost on backend restart. A future persistence mechanism (e.g., Redis or DB sync) is required for production durability. State must be carefully synchronized in multi-node deployments.
