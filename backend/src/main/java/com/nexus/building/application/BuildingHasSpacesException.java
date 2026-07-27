package com.nexus.building.application;

import java.util.UUID;

public class BuildingHasSpacesException extends RuntimeException {
    public BuildingHasSpacesException(UUID buildingId) {
        super("Cannot delete Building because it contains Spaces. Building ID: " + buildingId);
    }
}
