package com.nexus.adapter.spi;

/** The only way an adapter hands telemetry to the core. Implemented in {@code adapter.core}; delegates to the unchanged telemetry service. */
public interface TelemetryIntake {
    IntakeResult submit(TelemetryPayload payload, TransportContext context);
}
