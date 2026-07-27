package com.nexus.building.application;

import java.util.UUID;

public class BuildingNotFoundException extends RuntimeException {
    public BuildingNotFoundException(UUID id) {
        super("Building not found with ID: " + id);
    }
}
