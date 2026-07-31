package com.nexus.twin.domain;

import java.time.Instant;
import java.util.UUID;

public record TwinUpdatedEvent(
    UUID deviceId,
    Instant eventTime,
    DigitalTwin previousState,
    DigitalTwin currentState
) {}
