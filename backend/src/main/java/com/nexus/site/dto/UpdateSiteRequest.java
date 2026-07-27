package com.nexus.site.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateSiteRequest(
    @NotBlank(message = "Site name cannot be blank")
    @Size(max = 100, message = "Site name cannot exceed 100 characters")
    String name,

    @Size(max = 500, message = "Site description cannot exceed 500 characters")
    String description
) {
}
