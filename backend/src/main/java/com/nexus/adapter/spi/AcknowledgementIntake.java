package com.nexus.adapter.spi;

/** The only way an adapter hands an acknowledgement to the core (first-ACK-wins and the ACK facts stay in the core). */
public interface AcknowledgementIntake {
    IntakeResult submit(AckPayload payload, TransportContext context);
}
