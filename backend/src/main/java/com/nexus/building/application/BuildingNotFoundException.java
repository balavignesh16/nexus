package com.nexus.building.application;

import java.util.UUID;

public class BuildingNotFoundException extends com.nexus.shared.exception.ResourceNotFoundException {
    public BuildingNotFoundException(UUID id) {
        super("Building", id.toString());
    }
}
