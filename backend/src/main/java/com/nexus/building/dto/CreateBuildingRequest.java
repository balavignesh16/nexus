package com.nexus.building.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateBuildingRequest(
    @NotBlank(message = "Building name cannot be blank")
    @Size(max = 100, message = "Building name cannot exceed 100 characters")
    String name,

    @Size(max = 500, message = "Building description cannot exceed 500 characters")
    String description
) {
}
