package com.nexus.space.application;

import java.util.UUID;

public class SpaceNotFoundException extends RuntimeException {
    public SpaceNotFoundException(UUID id) {
        super("Space not found with ID: " + id);
    }
}
