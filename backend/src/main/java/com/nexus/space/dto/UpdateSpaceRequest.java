package com.nexus.space.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateSpaceRequest(
    @NotBlank(message = "Space name cannot be blank")
    @Size(max = 100, message = "Space name cannot exceed 100 characters")
    String name,

    @Size(max = 500, message = "Space description cannot exceed 500 characters")
    String description
) {
}
