package com.nexus.building.dto;

import java.time.Instant;
import java.util.UUID;

public record BuildingResponse(
    UUID id,
    UUID siteId,
    String name,
    String description,
    Instant createdAt,
    Instant updatedAt
) {
}
