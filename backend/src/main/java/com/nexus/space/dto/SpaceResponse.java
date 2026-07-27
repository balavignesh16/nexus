package com.nexus.space.dto;

import java.time.Instant;
import java.util.UUID;

public record SpaceResponse(
    UUID id,
    UUID buildingId,
    String name,
    String description,
    Instant createdAt,
    Instant updatedAt
) {
}
