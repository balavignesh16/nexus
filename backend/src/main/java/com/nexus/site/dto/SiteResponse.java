package com.nexus.site.dto;

import java.time.Instant;
import java.util.UUID;

public record SiteResponse(
    UUID id,
    String name,
    String description,
    Instant createdAt,
    Instant updatedAt
) {
}
