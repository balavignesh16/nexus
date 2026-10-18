package com.nexus.adapter.spi;

/** The only way an adapter hands a connectivity observation to the core (transition authority stays in the connectivity registry). */
public interface ConnectivityIntake {
    IntakeResult submit(ConnectivityObservation observation, TransportContext context);
}
